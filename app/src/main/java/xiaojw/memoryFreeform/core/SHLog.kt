package xiaojw.memoryFreeform.core

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志：logcat + 单一文本文件追加。
 *
 * 这个 APK 会在多个进程里跑（app 主进程 / CornerWindowService、root app_process 拉起的
 * InjectServer[touch] 与 InjectServer[watch]），以前日志分散在多处，排查要开终端抓
 * logcat。现在全部汇到同一个文件，手机上任意文件管理器打开即可复制。
 *
 * 落盘路径按顺序探测（第一个能开的胜出，**每个进程各自探测**）：
 *   1. /sdcard/Android/data/xiaojw.memoryFreeform/files/logs/memoryfreeform.log
 *      —— 与 app 的 getExternalFilesDir("logs") 同一位置
 *   2. /data/local/tmp/memoryfreeform.log          —— root 兜底
 *
 * 之所以要"每个进程各自探测"：root 先跑时它创建的目录/文件对 app 进程不一定可写，
 * 于是 app 的行会落到另一条路径上 —— 导出时必须把所有候选都合并进来，否则就会
 * 出现"日志里只有 root 的行、看不到 app 的触摸诊断"这种最难查的情况。
 *
 * 写策略：一行一次 write + flush、句柄常开。多进程以追加方式打开，单行远小于
 * PIPE_BUF，不会互相撕裂。超过 [MAX_BYTES] 轮转成 .1（只保留一代）。
 *
 * 约定：日志写入的任何异常都必须吞掉 —— 它绝不该影响触摸/注入主流程；
 * 但写失败后要**换下一条候选路径**，不能一直往一个写不进去的句柄上撞。
 */
object SHLog {

    const val DIR_SHARED = "/sdcard/Android/data/xiaojw.memoryFreeform/files/logs"
    const val FILE_SHARED = "$DIR_SHARED/memoryfreeform.log"
    const val FILE_FALLBACK = "/data/local/tmp/memoryfreeform.log"

    /**
     * **app 私有目录兜底**：`/data/data/<pkg>/files/logs/memoryfreeform.log`。
     * 外置存储那条路径在某些 ROM 上 app 就是写不进去（root 抢先建目录 / 存储权限 /
     * sdcardfs 标签问题），而 logcat 缓冲区在 MIUI 上几秒就被系统日志冲光 —— 两头都
     * 没证据，诊断就成了瞎猜。这个目录是 app 自己的私有目录，**一定可写**，root 也能读，
     * 导出时一并合并。app 进程优先写这里，其次才是外置路径。
     */
    const val FILE_PRIVATE = "/data/data/xiaojw.memoryFreeform/files/logs/memoryfreeform.log"

    /** 导出目标：公共目录，任何文件管理器都打得开 */
    const val FILE_EXPORT = "/sdcard/Download/memoryfreeform_log.txt"

    /** 所有统一日志候选路径（导出时全部合并） */
    val ALL_FILES = listOf(FILE_SHARED, FILE_FALLBACK)

    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val TAG = "SHLog"

    private val lock = Any()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 多进程写同一文件，行首带 pid 才能区分是 app 进程还是 root 进程 */
    private val pid = android.os.Process.myPid()

    private var writer: FileWriter? = null

    /** 当前候选下标：写不进去就换下一条，不再回头撞同一条 */
    private var candidateIndex = 0

    /** 上次"全部候选都失败后从头重试"的时刻：权限类故障事后修好时能自己恢复落盘 */
    private var lastRetryAllAt = 0L

    @Volatile
    private var current: File? = null

    /** 在 Application.onCreate / root 进程 main 开头调用一次即可，重复调用安全。 */
    fun init() {
        ensureWriter()
    }

    /**
     * app 进程专用入口：把**私有目录**那条路径顶到候选队首。
     * 只要它有 Context，就不需要赌外置存储的权限/属主是否碰巧对。
     */
    fun init(context: android.content.Context) {
        runCatching {
            val d = java.io.File(context.filesDir, "logs")
            d.mkdirs()
            extraPaths = listOf(java.io.File(d, "memoryfreeform.log").absolutePath)
        }
        ensureWriter()
    }

    /** app 进程额外优先的候选路径（私有目录），root 进程为空 */
    private var extraPaths: List<String> = emptyList()

    private fun candidates(): List<String> =
        if (extraPaths.isEmpty()) ALL_FILES else extraPaths + ALL_FILES

    /** 当前实际生效的日志文件路径；不可写时返回 null。 */
    fun path(): String? = current?.absolutePath

    fun sizeBytes(): Long = current?.let { runCatching { it.length() }.getOrDefault(0L) } ?: 0L

    fun v(tag: String, msg: String) = log('V', tag, msg)

    fun d(tag: String, msg: String) = log('D', tag, msg)

    fun i(tag: String, msg: String) = log('I', tag, msg)

    fun i(tag: String, msg: String, tr: Throwable?) = log('I', tag, withTrace(msg, tr))

    fun w(tag: String, msg: String) = log('W', tag, msg)

    fun w(tag: String, msg: String, tr: Throwable?) = log('W', tag, withTrace(msg, tr))

    fun e(tag: String, msg: String) = log('E', tag, msg)

    fun e(tag: String, msg: String, tr: Throwable?) = log('E', tag, withTrace(msg, tr))

    /** 清空日志（设置页按钮）。下次写入自动重建文件。 */
    fun clear() {
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
            // 清空后重新从第一条候选开始试，避免上次的失败被永久记住
            candidateIndex = 0
            // ★ 1.0.106：删**全部候选路径**（以前只删 ALL_FILES，漏了实际生效的私有目录
            //   文件 [extraPaths] —— 关句柄重开后老内容原样还在，用户报"清理按钮没有用"）；
            //   各目录下的轮转归档 memoryfreeform-*.log 一并清。
            candidates().forEach { p ->
                val f = File(p)
                runCatching { f.delete() }
                runCatching {
                    f.parentFile?.listFiles { file ->
                        file.name.startsWith("memoryfreeform-") && file.name.endsWith(".log")
                    }?.forEach { it.delete() }
                }
            }
            ensureWriterLocked()
        }
    }

    // ---- 内部实现 ----

    private fun withTrace(msg: String, tr: Throwable?): String =
        if (tr == null) msg else "$msg\n${Log.getStackTraceString(tr)}"

    private fun log(level: Char, tag: String, msg: String) {
        // logcat 照旧保留，不损失原有调试途径（也是文件写不进去时的唯一退路）
        when (level) {
            'V' -> Log.v(tag, msg)
            'D' -> Log.d(tag, msg)
            'I' -> Log.i(tag, msg)
            'W' -> Log.w(tag, msg)
            else -> Log.e(tag, msg)
        }
        val line = "${time.format(Date())} $level [$pid] $tag: $msg\n"
        synchronized(lock) {
            if (writeLocked(line)) return
            // 写失败（句柄坏 / 权限变化）：换下一条候选路径重试一次
            runCatching { writer?.close() }
            writer = null
            current = null
            candidateIndex++
            if (writeLocked(line)) return
            // 两条都写不进去就只能靠 logcat 了。但失败可能是**暂时的**——比如日志目录
            // 先被 root 进程创建、权限没放开，事后由 root 侧 chmod 修好。原实现从此
            // 永久放弃文件，app 侧日志再也不落盘。所以每 30s 从头重试一轮：权限修好
            // 的那一刻自然收敛回文件。
            val now = System.currentTimeMillis()
            if (now - lastRetryAllAt > 30_000L) {
                lastRetryAllAt = now
                candidateIndex = 0
                if (writeLocked(line)) return
            }
        }
    }

    private fun writeLocked(line: String): Boolean {
        val w = ensureWriter() ?: return false
        return try {
            w.write(line)
            w.flush()
            if ((current?.length() ?: 0L) > MAX_BYTES) rotateLocked()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureWriter(): FileWriter? {
        synchronized(lock) {
            writer?.let { return it }
            return ensureWriterLocked()
        }
    }

    private fun ensureWriterLocked(): FileWriter? {
        val list = candidates()
        while (candidateIndex < list.size) {
            val p = list[candidateIndex]
            try {
                val f = File(p)
                f.parentFile?.mkdirs()
                writer = FileWriter(f, true)
                current = f
                // root 创建时文件属主是 0，app 进程就写不进去了 —— 放开权限。
                // **目录必须一起放开**：目录是 root 建的（属主 root、700），app 进程连
                // 遍历都进不去，文件 666 得再对也没用 —— 真机实测 app 侧日志
                // （ImeWatcher 键盘避让等）就是这样全军覆没、只剩 logcat 的。
                if (android.os.Process.myUid() == 0) {
                    runCatching {
                        f.parentFile?.let {
                            Runtime.getRuntime().exec(arrayOf("chmod", "777", it.absolutePath)).waitFor()
                        }
                        Runtime.getRuntime().exec(arrayOf("chmod", "666", f.absolutePath)).waitFor()
                    }
                }
                return writer
            } catch (_: Throwable) {
                // 换下一个候选路径
                candidateIndex++
            }
        }
        current = null
        return null
    }

    private fun rotateLocked() {
        val f = current ?: return
        // 归档必须用「拷贝 + 截断」，**绝不 rename**：app 主进程 / root server 各自
        // 持有这个路径的 O_APPEND 追加句柄，rename 之后它们的 fd 全部跟着旧 inode
        // （已改名的归档文件）走 —— 之后它们写的每一行都进了归档，主文件从此只剩
        // "最后写进来那个进程"的行。真机后果就是：导出日志里只有 root server 的行，
        // ImeWatcher 等整个 app 侧的诊断日志全军覆没（2026-09-12 实锤）。
        // FileWriter 底层是 O_APPEND：外部 truncate 后，下一次 write 自动落回新 EOF，
        // 所有进程无需重开句柄。
        runCatching {
            val ts = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
            val bak = File(f.parentFile, "memoryfreeform-$ts.log")
            f.copyTo(bak, overwrite = true)
            java.io.FileOutputStream(f).close() // 无 append 打开即截断
        }
        // 归档只留最新 3 份，防外置存储被慢慢吃满
        runCatching {
            f.parentFile?.listFiles { file -> file.name.startsWith("memoryfreeform-") && file.name.endsWith(".log") }
                ?.sortedByDescending { it.name }
                ?.drop(3)
                ?.forEach { it.delete() }
        }
        // 当前 writer 不动：O_APPEND 保证截断后继续追加到正确位置
    }
}
