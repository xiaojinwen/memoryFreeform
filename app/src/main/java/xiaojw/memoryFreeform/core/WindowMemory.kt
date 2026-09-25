package xiaojw.memoryFreeform.core

import android.content.Context
import android.content.SharedPreferences

/**
 * ★ fix48：**每个 App 各自记住小窗位置**。
 * ★ fix52：存的是**下发坐标** `left, top, right, bottom`，不再是"视觉意图"。
 *
 * ## 为什么 fix52 要改存储语义（这一版的核心 bug）
 *
 * fix48 存的是「视觉意图」`l,t,w,h`，理由是"缩放状态可能变，存意图值能自适应"。
 * 但那个"视觉 = 下发 x 图层倍率"的换算**每次都要猜倍率**，而倍率是猜不准的：
 *
 * ```
 * 记：vw = 下发宽 x effectiveScale(pkg)      // effectiveScale 靠 ourPkgs 猜
 * 用：下发 = vw / miuiLayerScale()           // 又一个猜测点
 * ```
 *
 * 真机上这两个猜测会不一致 —— 窗口的 0.70 图层缩放早被 hook 中和成 1.0（真实
 * 倍率 1.0），但 `effectiveScale` 在 `ourPkgs` 还没登记上该包时返回 0.70，于是
 * 记忆被记成 `0.7 x` 真值；下次打开又按"意图"往回算，再关一次再乘 0.7 …
 * **每开合一次小窗就缩小一圈**，而用户一旦「清空位置记忆」回到设置尺寸，
 * 看到的就是「小窗一下子大了很多、和原来的大小对不上」。
 *
 * 现在改成**只看下发值**：`WindowWatcher` 从 `am stack list` 读到的 bounds 就是
 * 下发给系统的矩形，原样存、原样用，中间**没有任何乘法**，所以不可能再出现
 * 乘法螺旋。副作用是缩放状态真的变了（hook 装上/卸掉）时窗口会整体变大或变小
 * 一次 —— 但那是系统真实变化，不是我们算出来的漂移。
 *
 * 与 `/data/system/memoryfreeform_window_memory` 的关系：那个文件本来就是这个语义
 * （`pkg=l,t,r,b` 下发坐标，system_server 里的出生钩子直接拿去设 launchBounds），
 * 现在两份记忆**同域**，不会再出现"App 侧记的"和"钩子侧用的"互相打架。
 *
 * ## 与前缀的关系
 *
 * key 前缀从 `p:` 换成 `p2:`：旧条目是另一套语义（视觉意图，且可能已经被
 * 乘法螺旋缩过），继续读会把老问题一起带进来，直接让它们失效最干净。
 *
 * ## ★ fix64：位置**按横竖屏分开记**
 *
 * 一份记忆在两个方向上是**不能共用**的：竖屏记下的 `[300,800][1100,2000]` 放到横屏
 * （本机横屏屏高只有 1080）就是"底边 2000 > 屏高" ⇒ 小窗跑到屏幕外，用户看到的是
 * **小窗不见了**（真机实测）。所以 key 里带上方向维度：
 *
 * ```
 * 竖屏  p2:<pkg>      （沿用旧 key，历史记忆不丢）
 * 横屏  p2L:<pkg>
 * ```
 *
 * 与 `/data/system/memoryfreeform_window_memory` 的行格式对应：
 * ```
 * <pkg>=l,t,r,b       竖屏
 * <pkg>@L=l,t,r,b     横屏
 * ```
 *
 * 方向由 [WindowSizing.isLandscape] 判定（直接比屏幕宽高，跟随旋转）。
 * 读不到本方向的记忆时**不会**退回另一个方向 —— 那正是"小窗摆到屏幕外"的来源；
 * 此时调用方按设置值算（见 `CornerWindowService.miuiDeliveryRect`）。
 *
 * 与设置的关系：**记忆优先于设置**。用户把某个 App 的小窗拖到别处后，下次开这个
 * App 就落在拖到的地方；而一旦用户在设置页改了尺寸/缩放/角落，说明他要的是新设置，
 * 此时调用 [clearAll] 把记忆清空（否则改了设置却看不到效果，会被当成"设置不生效"）。
 *
 * 用 SharedPreferences 而不是 /data/system 下的文件：本 App 的 Service 与 Activity
 * **同进程**（AndroidManifest 里没有 android:process），一处写立即处处可见，
 * 不需要 root shell 往返。
 */
object WindowMemory {

    private const val PREFS = "memoryfreeform_window_memory"
    private const val PREFIX = "p2:"
    /** ★ fix64：横屏条目（竖屏仍用 [PREFIX]，历史记忆继续有效）。 */
    private const val PREFIX_L = "p2L:"

    private fun keyOf(pkg: String, landscape: Boolean): String =
        (if (landscape) PREFIX_L else PREFIX) + pkg

    /** 所有前缀（[clearAll] / [size] 要一起处理）。 */
    private fun prefixes() = arrayOf(PREFIX, PREFIX_L)

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * 取某个包在**指定方向**记住的**下发矩形** `[left, top, right, bottom]`；没记过返回 null。
     *
     * ★ fix64：方向是**必填**参数（不给默认值）—— 默认值会让人忘记传，
     *   而忘记传的后果就是把竖屏的矩形用到横屏上（小窗直接跑到屏幕外）。
     */
    fun get(pkg: String, landscape: Boolean): IntArray? {
        if (pkg.isBlank()) return null
        val raw = prefs?.getString(keyOf(pkg, landscape), null) ?: return null
        val v = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (v.size != 4) return null
        // 防御：历史上写坏的值不能让小窗变成一个点
        if (v[2] - v[0] < 100 || v[3] - v[1] < 100) return null
        return intArrayOf(v[0], v[1], v[2], v[3])
    }

    fun put(pkg: String, landscape: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (pkg.isBlank()) return
        if (right - left < 100 || bottom - top < 100) return
        prefs?.edit()
            ?.putString(keyOf(pkg, landscape), "$left,$top,$right,$bottom")
            ?.apply()
    }

    /** 清掉某个包的**两个方向**（用户看到的是"这个 App 的记忆"，不该按方向分开清）。 */
    fun clear(pkg: String) {
        val e = prefs?.edit() ?: return
        for (p in prefixes()) e.remove(p + pkg)
        e.apply()
    }

    /** 设置页改了尺寸/缩放/角落时调用：新设置应当立刻压过所有记忆。 */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }

    /** 已记住位置的条目数量（日志/诊断用）。 */
    fun size(): Int =
        prefs?.all?.count { e -> prefixes().any { e.key.startsWith(it) } } ?: 0

    /**
     * ★ fix93：切角 = 把系统记忆**水平镜像**到新角落，而不是清空。
     *
     * 旧行为（fix63）：改角落必须 `clearAll()` + 清系统文件 —— 那时记忆的语义还是
     * 「设置几何的缓存」，新角落下旧几何必然矛盾。但 fix88/91 之后记忆已经是
     * **用户摆放的位置 + 拉伸的大小**，切角清掉等于"用户拖好的位置和大小全丢"，
     * 真机日志实证（03:21:54~55 用户点切角球）：
     * ```
     * windowMemory: 已清掉 system 侧记忆 ...
     * miuiRect: 视觉 800x1200（设置值）   ← 记忆没了，退回设置几何
     * ```
     * 之后一切从设置几何重新开始 —— 位置"失效"、大小"不恢复"都是它。
     *
     * 正确语义：切角只是把窗口搬到另一侧 → 记忆矩形水平翻转
     * `newL = D - r, newR = D - l`（D = 该方向**下发空间宽** = 屏幕边/scale，
     * fix75 定论：记忆存的是下发值，right 可 > 逻辑屏宽）。竖屏行用竖屏 D，
     * 横屏行（`@L`）用横屏 D。镜像后把文件原样写回，之后用户拖动/拉伸照常更新。
     *
     * @param onDone 写回成功后的回调（**主线程**）—— 切角流程等镜像落盘再热重摆，
     *   否则 resizeTaskToWindow 会读到旧角落的记忆，把窗摆回原侧（竞态）。
     */
    fun mirrorAllHorizontally(screenW: Int, screenH: Int, scale: Float, onDone: (() -> Unit)? = null) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            var ok = false
            runCatching {
                val rm = xiaojw.memoryFreeform.root.RootManager.get()
                val f = xiaojw.memoryFreeform.hook.HookContract.WINDOW_MEMORY_PATH
                val out = rm.executeFast("cat $f", 3000).output
                if (out.isBlank()) return@runCatching
                // 竖屏宽 / 横屏宽（调用方传的是"当前旋转"的宽高，这里按短长边归一，
                // 与 fix64「按方向分键」同一套口径：竖 D = 短边/scale，横 D = 长边/scale）
                val short = minOf(screenW, screenH)
                val long = maxOf(screenW, screenH)
                val dPort = (short / scale).toInt()
                val dLand = (long / scale).toInt()
                val lines = out.lineSequence().mapNotNull { line ->
                    val t = line.trim()
                    if (t.isEmpty() || !t.contains('=')) return@mapNotNull null
                    val key = t.substringBefore('=')
                    val nums = t.substringAfter('=').split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (nums.size != 4) return@mapNotNull null
                    val d = if (key.endsWith("@L")) dLand else dPort
                    "${key}=${d - nums[2]},${nums[1]},${d - nums[0]},${nums[3]}"
                }.toList()
                if (lines.isEmpty()) return@runCatching
                val cmd = buildString {
                    append("rm -f $f $f.tmp")
                    for (l in lines) append("; echo '").append(l).append("' >> $f")
                    append("; chmod 666 $f")
                }
                rm.executeFast(cmd, 5000)
                android.util.Log.i("WindowMemory", "切角镜像完成：${lines.joinToString(" | ")}")
                ok = true
            }.onFailure { android.util.Log.w("WindowMemory", "切角镜像失败: ${it.message}") }
            onDone?.let { cb -> main.post(cb) }
        }.start()
    }
}
