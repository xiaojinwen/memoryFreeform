package xiaojw.memoryFreeform.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import xiaojw.memoryFreeform.core.HookBridge
import xiaojw.memoryFreeform.core.SHLog
import xiaojw.memoryFreeform.core.StateManager
import xiaojw.memoryFreeform.core.WindowWatcher
import xiaojw.memoryFreeform.hook.HookContract
import xiaojw.memoryFreeform.root.RootManager
import java.util.concurrent.Executors

/**
 * ★ fix188：**调试模式浮层**（设置页开关控制，默认关）。
 *
 * 打开后在屏幕**左上角、状态栏下方**常驻一块半透明浮层，把当前每个 freeform 小窗的
 * 位置 / 大小 / 缩放 / 记忆值**并排**列出来。用途：现场排查「位置记错 / 大小记不住 /
 * 窗口莫名变大」这类问题 —— 不用再来回拔线抓日志，眼睛盯着它拖小窗就能对上号。
 *
 * 每个小窗一张卡，卡片内容：
 * ```
 * mark.via            ← 包名
 * pos  252,519        ← 下发 bounds 左上角（Task.getBounds，逻辑屏坐标）
 * size 1080x1728      ← 下发尺寸（**不是**眼睛看到的大小）
 * s    0.594          ← 记忆里的 freeformScale（读不到标「默认 0.70」）
 * view 642x1026       ← 视觉尺寸 = 下发尺寸 × scale（这才是眼睛看到的大小）
 * mem  252,519        ← 记忆文件里存的左上角，用来和 pos 对照看记没记错
 * ```
 *
 * ### 为什么「大小」要显示两行（size / view）
 * 真机定论：小窗「大小」的真身是 `freeformScale`（渲染缩放），**不是 Task bounds**
 * （见 ARCH-DETAIL）。用户拖边角改的是 scale，bounds 可以一像素不动。只盯 bounds
 * 排查会得出完全错误的结论，所以浮层同时给出下发尺寸与视觉尺寸。
 *
 * ### 触摸
 * 带 `FLAG_NOT_TOUCHABLE`：浮层**完全不接收触摸**，拖动 / 缩放小窗不受任何影响
 * （调试浮层绝不能改变被测行为）。
 *
 * ### 数据来源
 * - 小窗列表：`am stack list`（root，走 [RootManager.executeWatch] 独立通道，
 *   避免挤占共享通道把别的命令挤掉 —— 见 fix51 的教训）。
 * - 缩放记忆 / 位置记忆：system_server 写的 `/data/system/memoryfreeform_window_*`。
 */
class DebugOverlayService : Service() {

    private var wm: WindowManager? = null
    private var rootView: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dbg-overlay").apply { isDaemon = true }
    }
    @Volatile private var stopped = false

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as? WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("记忆小窗调试模式")
            .setContentText("左上角显示各小窗的位置 / 大小 / 缩放")
            .setOngoing(true)
        runCatching { startForeground(NOTIFICATION_ID, notif.build()) }
            .onFailure { SHLog.e(TAG, "startForeground failed", it) }
        stopped = false
        attach()
        tick()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        detach()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ---------------------------------------------------------------- 浮层

    private fun attach() {
        if (rootView != null) return
        val w = wm ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // ★ 完全不吃触摸：调试浮层只负责显示，绝不能影响拖动 / 缩放小窗的手感
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // ★ overlay 的 y 原点就在状态栏下方（真机实测），所以 y 给一点边距即可
            gravity = Gravity.TOP or Gravity.START
            x = dp(6)
            y = dp(4)
        }
        runCatching { w.addView(root, lp) }
            .onFailure { SHLog.e(TAG, "debug overlay addView failed", it); return }
        rootView = root
    }

    private fun detach() {
        rootView?.let { runCatching { wm?.removeView(it) } }
        rootView = null
    }

    // ---------------------------------------------------------------- 采样 / 渲染

    private fun tick() {
        if (stopped) return
        worker.execute {
            if (stopped) return@execute
            val snap = readSnapshot()
            mainHandler.post {
                if (stopped) return@post
                render(snap)
                mainHandler.postDelayed({ tick() }, REFRESH_MS)
            }
        }
    }

    private data class Item(
        val pkg: String,
        val l: Int, val t: Int, val r: Int, val b: Int,
        val scale: Float?,
        val mem: IntArray?,
        val visible: Boolean,
        /** 文件里的值（延迟 3s 防抖兜底），与内存热层/Global 不一致时浮层标出来 */
        val scaleFile: Float? = null,
        val memFile: IntArray? = null,
        /** fix191：系统当下真实缩放（hook 事件驱动写，300ms 节流） */
        val live: Float? = null,
        /** null = 窗口还活着；非 null = 已关闭（值为关闭后经过的秒数） */
        val closedSec: Long? = null
    )

    private data class Snap(val hookActive: Boolean, val items: List<Item>) {
        fun signature(): String = buildString {
            append(if (hookActive) "1" else "0")
            for (i in items) append(
                "|${i.pkg},${i.l},${i.t},${i.r},${i.b},${i.scale},${i.mem?.joinToString("/")},${i.visible}," +
                    "${i.closedSec},${i.scaleFile},${i.memFile?.joinToString("/")},${i.live}"
            )
        }
    }

    // ---- fix189：关窗后保留最后一帧（按包名 sticky，多个小窗互不影响）----
    private val lastSeen = LinkedHashMap<String, Item>()
    private val goneAt = HashMap<String, Long>()

    /** 后台线程：root 读小窗列表 + 两份记忆文件。 */
    private fun readSnapshot(): Snap {
        val tasks = runCatching { WindowWatcher.readFreeformTasks() }.getOrDefault(emptyList())
        val (scales, scalesF) = readMap(HookContract.WINDOW_SCALE_PATH, HookContract.SCALE_GLOBAL_KEY)
        val (mems, memsF) = readMap(HookContract.WINDOW_MEMORY_PATH, HookContract.MEMORY_GLOBAL_KEY)
        val liveS = readText(HookContract.LIVE_SCALE_PATH) // fix191：系统当下真实缩放
        val now = System.currentTimeMillis()

        fun parseRect(v: String?) =
            v?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toIntArray()?.takeIf { it.size == 4 }

        val live = tasks.map { t ->
            Item(
                t.pkg, t.l, t.t, t.r, t.b,
                scales[t.pkg]?.toFloatOrNull(), parseRect(mems[t.pkg]), t.visible,
                scalesF[t.pkg]?.toFloatOrNull(), parseRect(memsF[t.pkg]),
                liveS[t.pkg]?.toFloatOrNull()
            )
        }
        // 活的：刷新快照；消失的：打上关闭时间戳（只打一次）
        val livePkgs = HashSet<String>(live.size)
        for (it in live) { livePkgs.add(it.pkg); lastSeen[it.pkg] = it; goneAt.remove(it.pkg) }
        val iter = lastSeen.keys.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            if (p in livePkgs) continue
            val since = goneAt[p] ?: now.also { goneAt[p] = it }
            if (now - since > CLOSED_KEEP_MS) { iter.remove(); goneAt.remove(p) }
        }

        // ★ 已关闭的条目也要继续刷新 mem / s：关窗瞬间 hook 还在写记忆，
        //   只有把「最后写进去的值」留在屏上，才能一眼看出这次到底记住了什么。
        val items = lastSeen.values.map { base ->
            val g = goneAt[base.pkg] ?: return@map base
            base.copy(
                mem = parseRect(mems[base.pkg]) ?: base.mem,
                scale = scales[base.pkg]?.toFloatOrNull() ?: base.scale,
                memFile = parseRect(memsF[base.pkg]) ?: base.memFile,
                scaleFile = scalesF[base.pkg]?.toFloatOrNull() ?: base.scaleFile,
                live = liveS[base.pkg]?.toFloatOrNull() ?: base.live,
                closedSec = (now - g) / 1000
            )
        }
        val hook = runCatching { HookBridge.isActive() }.getOrDefault(false)
        return Snap(hook, items)
    }

    /**
     * 读 `pkg=值` 形式的记忆。
     *
     * ★ fix190：**必须优先读 Settings.Global，不能只读 /data/system 文件。**
     *   HookContract(fix180/181) 的写入是「内存热层即时 + Settings.Global 即时 +
     *   /data/system 文件**延迟 3s 防抖**」，而防抖会被连续操作一路重置 ⇒ 文件可能
     *   长时间停在旧值。只盯文件会看到假的 mem/s（与钩子实际用的值不一致），
     *   排查出的结论也是错的。
     * 返回 Pair(global, file)：global 为空时按 file 兜底，两者不一致时浮层会标出来。
     */
    private fun readMap(path: String, globalKey: String?): Pair<Map<String, String>, Map<String, String>> {
        val file = readText(path)
        if (globalKey == null) return file to file
        val g = readText("settings get global $globalKey", true)
        return (if (g.isEmpty()) file else g) to file
    }

    /** root 执行一条命令并把输出按 `k=v` 解析成表；cmd=true 时按命令读而非按文件读。 */
    private fun readText(src: String, cmd: Boolean = false): Map<String, String> {
        val out = runCatching {
            val c = if (cmd) src else "cat $src 2>/dev/null"
            RootManager.get().executeWatch(c, 1500).output
        }.getOrNull() ?: return emptyMap()
        val m = HashMap<String, String>()
        for (line in out.lineSequence()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            if (v == "null" || v.isEmpty()) continue
            m[k] = v
        }
        return m
    }

    private var lastSig = ""

    private fun render(snap: Snap) {
        val root = rootView ?: return
        val sig = snap.signature()
        if (sig == lastSig) return // 无变化不重建，省掉每 700ms 的闪烁
        lastSig = sig

        root.removeAllViews()
        if (snap.items.isEmpty()) {
            root.addView(card("（当前没有 freeform 小窗）\nhook=${if (snap.hookActive) "已激活" else "未激活"}"))
            return
        }
        for (it in snap.items) {
            val w = it.r - it.l
            val h = it.b - it.t
            val s = it.scale
            val view = if (s != null) "${(w * s).toInt()}x${(h * s).toInt()}" else "?"
            val lines = buildString {
                // ★ fix189：关闭后照样留着 —— 否则手一松窗就没了，根本没法对数值
                if (it.closedSec != null) append("★已关闭 ${it.closedSec}s\n")
                append(it.pkg).append(if (it.visible) "" else " (隐藏)").append('\n')
                append("pos  ${it.l},${it.t}\n")
                append("size ${w}x${h}\n")
                // ★ fix190：文件是延迟 3s 防抖的兜底，跟 Global 不一致时标出来
                append("s    ${s?.let { v -> "%.3f".format(v) } ?: "默认 0.70"}")
                val sf = it.scaleFile
                if (sf != null && s != null && kotlin.math.abs(sf - s) > 0.005f)
                    append(" (文件 %.3f)".format(sf))
                append('\n')
                append("live ${it.live?.let { v -> "%.3f".format(v) } ?: "-"}\n")
                append("view $view\n")
                append("mem  ${it.mem?.let { m -> "${m[0]},${m[1]}" } ?: "-"}")
                val mf = it.memFile
                if (mf != null && it.mem != null && (mf[0] != it.mem!![0] || mf[1] != it.mem!![1]))
                    append(" (文件 ${mf[0]},${mf[1]})")
                val m = it.mem
                if (m != null) {
                    val same = m[0] == it.l && m[1] == it.t
                    val sw = (m[2] - m[0]).takeIf { it > 0 }
                    val sh = (m[3] - m[1]).takeIf { it > 0 }
                    val sameSize = sw == null || sw == w
                    append("\nrec  ${if (same) "POS-OK" else "POS-DIFF!"}" +
                        (if (sw != null) " size ${sw}x${sh}${if (sameSize) "" else " (diff)"}" else ""))
                }
            }
            root.addView(card(lines, closed = it.closedSec != null))
        }
        // hook 状态挂在最后一卡后面，便于一眼确认钩子在不在
        root.addView(card("hook\n${if (snap.hookActive) "已激活" else "未激活"}"))
    }

    /** closed=true 用琥珀底色，一眼区分「这份是关闭后的遗留值」 */
    private fun card(text: String, closed: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextColor(if (closed) Color.rgb(255, 214, 140) else Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTypeface(Typeface.MONOSPACE)
        setBackgroundColor(if (closed) Color.argb(190, 70, 45, 0) else Color.argb(190, 0, 0, 0))
        setPadding(dp(6), dp(5), dp(6), dp(5))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(5) }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID, "记忆小窗调试模式",
                    android.app.NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }

    // ---------------------------------------------------------------- 入口

    companion object {
        private const val TAG = "DebugOverlay"
        private const val CHANNEL_ID = "memoryfreeform_debug"
        private const val NOTIFICATION_ID = 1017
        private const val REFRESH_MS = 700L
        /** 关闭太久的条目清掉，免得复活的小窗之外越攒越乱 */
        private const val CLOSED_KEEP_MS = 10 * 60 * 1000L

        /** 按当前设置起停浮层（设置页开关 / App 启动时都调它）。 */
        fun sync(context: Context) {
            val on = StateManager.current.debugOverlay
            if (on) {
                if (!android.provider.Settings.canDrawOverlays(context)) {
                    SHLog.w(TAG, "调试模式需要悬浮窗权限")
                    return
                }
                runCatching { context.startForegroundService(Intent(context, DebugOverlayService::class.java)) }
                    .onFailure { SHLog.e(TAG, "start failed", it) }
            } else {
                runCatching { context.stopService(Intent(context, DebugOverlayService::class.java)) }
                    .onFailure { SHLog.e(TAG, "stop failed", it) }
            }
        }
    }
}
