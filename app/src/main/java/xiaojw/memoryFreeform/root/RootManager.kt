package xiaojw.memoryFreeform.root
import android.os.SystemClock
import xiaojw.memoryFreeform.core.SHLog

import java.io.DataOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellResult(
    val success: Boolean,
    val output: String = "",
    val error: String = ""
)

/**
 * RootManager：通过 su 执行 shell 命令（设备已 root）。
 * 接口与原 ShizukuManager 对齐：isReady() / execute(cmd) / init(context)。
 */
class RootManager private constructor() {

    @Volatile
    private var rootAvailable: Boolean? = null

    fun init(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
        // root 探测在首次 isReady() 时惰性执行
    }

    /** 检测 su 是否可用（结果缓存）。 */
    fun checkRoot(): Boolean {
        rootAvailable?.let { return it }
        synchronized(this) {
            rootAvailable?.let { return it }
            val ok = try {
                val p = ProcessBuilder("su", "-c", "id").start()
                val done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                val out = p.inputStream.bufferedReader().readText()
                p.destroy()
                done && out.contains("uid=0")
            } catch (e: Exception) {
                SHLog.e(TAG, "root check failed", e)
                false
            }
            rootAvailable = ok
            return ok
        }
    }

    fun isReady(): Boolean = checkRoot()

    // ===== 持久 root shell =====
    // 必须是多条独立的 su shell：
    //  - execAsync 只写不读（触摸注入兜底 / am 命令）
    //  - executeFast 写并回读（周期轮询 am stack list）
    // 原来两者共用一个 shell、一把锁，有三个后果：
    //   1) executeFast 阻塞等 marker 时持有锁，execAsync 的触摸命令被一起堵住；
    //   2) execAsync 的 `>/dev/null` 会吃掉 executeFast 正要读的输出，造成乱序；
    //   3) marker 迟迟不来时 readLine 永久挂起，整条 root 通道废掉。
    private var writeProc: Process? = null
    private var writeStdin: java.io.OutputStream? = null
    private var writeConfigured = false
    private val writeLock = Any()

    /**
     * 一条「写命令 + 回读输出」的常驻 su shell。
     *
     * 以前整机只有**一条**查询通道：键盘探测（每轮 1~3 条 dumpsys，每条 200~600ms）
     * 与周期轮询共用它，于是两边互相饿死 —— 探测拿到的常常是 busy（真机日志里
     * 每秒好几条 `executeFast busy`，一份日志 600+ 行），轮询命令则排在其后。
     * 现在拆成两条独立通道：通用走 [mainQuery]，键盘探测独占 [imeQuery]。
     */
    private inner class QueryChannel(private val threadName: String) {

        @Volatile private var proc: Process? = null
        private var out: java.io.BufferedWriter? = null
        private var inp: java.io.BufferedReader? = null
        private val lock = Any()

        /**
         * 是否已有一条查询在飞。**这是防死锁的关键**，见 [destroy]：
         * 查询 shell 是**串行**的（[runQuery] 全程持 [lock]），后来者只能在锁上排队；
         * 排队一旦超过 timeout，就会走到"超时 → 销毁重建"那条路 —— 而销毁时若先去抢
         * 那把锁，就会和"正阻塞在 readLine 上的查询线程"互相等成死锁。
         * 有了这个标志：后来者**直接放弃本轮**（下轮再来），既不死等也不销毁健康 shell。
         */
        @Volatile var busy = false
        private val skipCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var lastBusyLogAt = 0L

        fun ensure(): Boolean {
            if (inp != null && out != null) return true
            synchronized(lock) {
                if (inp != null && out != null) return true
                return try {
                    val p = ProcessBuilder("su", "-c", "sh").start()
                    proc = p
                    out = java.io.BufferedWriter(java.io.OutputStreamWriter(p.outputStream))
                    inp = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
                    true
                } catch (e: Exception) {
                    SHLog.e(TAG, "$threadName shell failed", e)
                    proc = null; out = null; inp = null
                    false
                }
            }
        }

        /**
         * ⚠ **必须先在锁外把进程杀掉**：崩溃/挂起时 `runQuery` 正阻塞在 `readLine()` 上，
         * 而它**全程持有 [lock]**。若这里先 `synchronized(lock)` 再销毁，就变成
         * 「我等它放锁，它等进程死」的经典死锁 —— 调用线程（键盘探测线程 / 轮询线程）
         * 会**永久挂住**，症状是"日志停在第一行，之后什么都不打印、功能再也不生效"。
         * `destroyForcibly()` 关掉管道后 `readLine()` 立刻返回 null，那边自己就会退出并放锁。
         */
        fun destroy() {
            runCatching { proc?.destroyForcibly() }
            synchronized(lock) { proc = null; out = null; inp = null }
        }

        fun exec(cmd: String, timeoutMs: Long): ShellResult {
            if (!ensure()) return execute(cmd)
            if (busy) {
                // busy 每秒能刷好几行，把真正有价值的判定日志全挤出去 —— 限流到 10 秒一条
                skipCount.incrementAndGet()
                val now = SystemClock.uptimeMillis()
                if (now - lastBusyLogAt > 10_000L) {
                    lastBusyLogAt = now
                    SHLog.w(
                        TAG,
                        "executeFast busy on $threadName, skipped ${skipCount.get()} round(s) " +
                            "in last 10s: ${cmd.take(80)}"
                    )
                }
                return ShellResult(false, error = "busy")
            }
            skipCount.set(0)
            busy = true
            val result = java.util.concurrent.atomic.AtomicReference<ShellResult?>(null)
            val t = Thread({ try { result.set(runQuery(cmd)) } finally { busy = false } }, threadName)
                .apply { isDaemon = true }
            t.start()
            t.join(timeoutMs)
            if (t.isAlive) {
                SHLog.w(TAG, "executeFast timeout on $threadName, kill query shell: ${cmd.take(80)}")
                destroy()
                return ShellResult(false, error = "timeout")
            }
            return result.get() ?: ShellResult(false)
        }

        private fun runQuery(cmd: String): ShellResult {
            return try {
                synchronized(lock) {
                    val po = out ?: return ShellResult(false)
                    val pi = inp ?: return ShellResult(false)
                    val marker = "__DONE_${System.nanoTime()}__"
                    drainAvailable(pi)
                    po.write(cmd)
                    po.write("\necho -n \"$marker \" ; echo $?\n")
                    po.flush()
                    val sb = StringBuilder()
                    while (true) {
                        val line = pi.readLine() ?: break
                        if (line.contains(marker)) break
                        sb.appendLine(line)
                        if (sb.length > 512 * 1024) break // 防御性上限
                    }
                    ShellResult(true, sb.toString())
                }
            } catch (e: Exception) {
                // 管道被关（shell 挂了 / 被超时方销毁）会走到这里。**故意不 fork 兜底**：
                // 调用方是周期性探测，失败这一轮返回 null 即可，下轮会重建 shell。
                SHLog.w(TAG, "query failed on $threadName, shell reset: ${e.message}")
                destroy()
                ShellResult(false, error = "query failed")
            }
        }
    }

    private val mainQuery = QueryChannel("sh-query")
    /** 键盘探测专属通道（见 [executeIme]） */
    private val imeQuery = QueryChannel("sh-query-ime")

    /**
     * ★ fix51：全局小窗监听专属通道（见 [executeWatch]）。
     *
     * 监听**一直在跑**（有 freeform 小窗时 2s 一轮，空闲 5s 一轮），放进 [mainQuery]
     * 会把关窗时的 `am stack remove` 挤成 busy —— 真机实测过：`executeFast busy on
     * sh-query, skipped 1 round(s) ... am stack remove 11240` → 关窗命令失败 →
     * 退化到 `force-stop` 兜底，把用户刚关掉的那个 App 后台进程一起杀了。
     * 独立通道后互不干扰，代价只是多一条常驻 su shell。
     */
    private val watchQuery = QueryChannel("sh-query-watch")

    private fun ensureWriteShell(): java.io.OutputStream? {
        writeStdin?.let { return it }
        synchronized(writeLock) {
            writeStdin?.let { return it }
            return try {
                val p = ProcessBuilder("su", "-c", "sh").start()
                writeProc = p
                writeConfigured = false
                p.outputStream.also { writeStdin = it }
            } catch (e: Exception) {
                SHLog.e(TAG, "write shell failed", e)
                null
            }
        }
    }

    /** 向只写 shell 写入一行命令（不等待结果，最低延迟）。 */
    fun execAsync(cmd: String): Boolean {
        val out = ensureWriteShell() ?: return false
        return try {
            synchronized(writeLock) {
                // scrcpy 风格：常驻 shell 只配置一次，后续命令走无回显二进制流，减少解析和管道开销。
                if (!writeConfigured) {
                    out.write("exec 2>/dev/null\n".toByteArray())
                    writeConfigured = true
                }
                // 丢弃命令输出，避免 su shell 的 stdout/stderr 管道阻塞触摸流。
                out.write(("$cmd >/dev/null 2>&1\n").toByteArray())
                out.flush()
            }
            true
        } catch (e: Exception) {
            SHLog.e(TAG, "execAsync failed", e)
            // 通道失效则重置，下次重建
            runCatching { writeProc?.destroy() }
            writeProc = null; writeStdin = null; writeConfigured = false
            false
        }
    }

    fun destroyShell() {
        synchronized(writeLock) {
            runCatching { writeProc?.destroy() }
            writeProc = null; writeStdin = null; writeConfigured = false
        }
        mainQuery.destroy()
        imeQuery.destroy()
        watchQuery.destroy()
    }

    /**
     * 在**通用**查询 shell 上执行命令并回读结果（周期轮询等）。
     *
     * 三条防雪崩规则（都是真机上踩出来的）：
     *  1. 上一次查询还在飞 → 本轮**直接放弃**，不叠线程、不抢锁（见 [QueryChannel.busy]）；
     *  2. 超时 → destroyForcibly 关管道（**锁外**），线程自然退出；
     *  3. 失败**不再**退化成 `execute()` 那个 fork 版 —— 探测是周期性的，"这轮没探到"
     *     远好于"每秒 fork 一个 su"，后者会把 system_server 拖垮。
     */
    fun executeFast(cmd: String, timeoutMs: Long = 3000): ShellResult =
        mainQuery.exec(cmd, timeoutMs)

    /**
     * 键盘探测专用通道。
     *
     * 与 [executeFast] 分开的理由：键盘探测每轮 1~3 条 dumpsys，和周期轮询命令
     * 挤在一条 shell 上时，两边互相把对方挤成 busy —— 一边是"键盘收起后要等一两秒
     * 才落回"，另一边是"点开应用停在主屏好几秒"。各走一条常驻 shell 后，键盘探测的
     * 端到端延迟就等于**一条命令的耗时**，不再排队。
     */
    fun executeIme(cmd: String, timeoutMs: Long = 3000): ShellResult =
        imeQuery.exec(cmd, timeoutMs)

    /**
     * ★ fix51：**全局小窗监听**专用通道（[xiaojw.memoryFreeform.core.WindowWatcher]）。
     *
     * 与 [executeFast] 分开的理由同上：监听每 2~5 秒就要跑一次 `am stack list`，
     * 挤在通用通道上会把关窗 / 启动时的 resize 挤成 busy（真机实测：`am stack remove`
     * 被跳过后退化成 force-stop，把用户刚关掉小窗的 App 后台进程也杀了）。
     */
    fun executeWatch(cmd: String, timeoutMs: Long = 3000): ShellResult =
        watchQuery.exec(cmd, timeoutMs)

    /** 非阻塞排空查询 shell 输出管道中的残留数据。 */
    private fun drainAvailable(pi: java.io.BufferedReader?) {
        val r = pi ?: return
        try {
            while (r.ready()) if (r.read() < 0) return
        } catch (_: Exception) {}
    }

    /** 执行命令：`su -c sh -c cmd`，等待完成，收集 stdout/stderr。超时 8 秒。 */
    fun execute(cmd: String): ShellResult {
        if (!checkRoot()) return ShellResult(false, error = "Root not available")
        return try {
            val p = ProcessBuilder("su", "-c", "sh -c ${quote(cmd)}").start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val tOut = Thread {
                runCatching { BufferedReader(InputStreamReader(p.inputStream)).forEachLine { stdout.appendLine(it) } }
            }
            val tErr = Thread {
                runCatching { BufferedReader(InputStreamReader(p.errorStream)).forEachLine { stderr.appendLine(it) } }
            }
            tOut.start(); tErr.start()

            val done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                return ShellResult(false, error = "Command timeout: $cmd")
            }
            tOut.join(1000); tErr.join(1000)
            val code = p.exitValue()
            p.destroy()
            ShellResult(code == 0, stdout.toString(), stderr.toString())
        } catch (e: Exception) {
            SHLog.e(TAG, "exec failed: $cmd", e)
            ShellResult(false, error = e.message ?: "Unknown error")
        }
    }

    private fun quote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "RootManager"

        @Volatile private var instance: RootManager? = null
        fun get(): RootManager =
            instance ?: synchronized(this) {
                instance ?: RootManager().also { instance = it }
            }
    }
}
