package xiaojw.memoryFreeform.core

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

/**
 * 「设置里填的宽高」→「小窗最终像素尺寸」的**唯一**计算入口。
 *
 * ## fix40 为什么要有这个类
 *
 * 设置页滑块显示的百分比是 `scaleRatio * 100`，而服务侧真正用的是
 * `scaleRatio / 1.2`（1.2 是自初始提交就存在的「100% 基准」，见 [SCALE_REFERENCE]）。
 * 默认值 1.2 恰好让两者互相抵消，所以这个错配一直没被发现；用户在设置里把滑块
 * 拖到「100%」（scaleRatio = 1.0）后，实际只拿到 **83.3%**：
 *
 * ```
 * 设置 1000x1300 -> 下发 835x1085    （1000 / 1.2 = 833，1300 / 1.2 = 1083）
 * 设置  900x1200 -> 下发 751x1002
 * 设置 1800x1600 -> 下发 864x1336    （先 /1.2 再被 80% 上限 864 截断）
 * ```
 *
 * 用户看到的观感就是"我填的尺寸和出来的小窗对不上，是不是被系统限制了"。
 *
 * ## 真机实测结论（Xiaomi 2304FPN6DC / HyperOS 3 / 1080x2400）
 *
 * `am task resize <id> <l> <t> <r> <b>` 是**精确且持久**的，澎湃对 Freeform 几何
 * 没有任何钳制：
 *
 * | 下发 | 0.3s 后 | 15s 后 |
 * |---|---|---|
 * | `[100,400][600,1000]` | 一致 | 一致 |
 * | `[245,1189][1080,2358]`（右/下贴边） | 一致 | 一致 |
 * | `[300,900][500,1100]`（200x200 极小） | 一致 | 一致 |
 *
 * 所以"尺寸不对"从来不是系统限制，而是我们自己算错。这里把「比例换算」和
 * 「上限规则」收敛成纯函数，**设置页预览与实际下发共用同一份逻辑**，
 * 从此两边不会再各算一套。
 */
object WindowSizing {

    /**
     * 缩放滑块的「100%」基准。
     *
     * 存储值 = 真实比例 x REF，UI 展示时用 [percent] 除回来。
     * 保留这个换算（而不是直接把存储值改成真实比例）是为了让**老用户 prefs 里
     * 已经存下的 1.2 依旧等于 100%**，不必做数据迁移。
     */
    const val SCALE_REFERENCE = 1.2f

    /** 极小值兜底：再小的窗口没法用（澎湃实测 200x200 也能原样生效，这里留足余量）。 */
    const val MIN_SIDE = 160

    /**
     * ★ fix42：澎湃（HyperOS 3）给 Freeform 窗口的**图层缩放**。实测恒为 0.70。
     *
     * 这是从 SurfaceFlinger 抓到的硬数据，不是估算：
     * ```
     * am task resize <id> 0 1200 1080 2400        # 下发 1080x1200
     *   -> app 图层 displayFrame=[0 1200 756 2040] sourceCrop=[0 0 1080 1200]
     *      => scaleX=0.7000 scaleY=0.7000
     *
     * am task resize <id> 0 1200 1543 2914       # 下发 1543x1714（= 1080/0.7 x 1200/0.7）
     *   -> app 图层 displayFrame=[0 1200 1080 2400] sourceCrop=[0 0 1543 1714]
     *      => scaleX=0.6999 scaleY=0.7001   ← 视觉正好回到 1080x1200 且贴底
     * ```
     * - **锚点锁定左上角**：`displayFrame` 的左上角恒等于下发的 left/top，
     *   右侧与底部被乘上本系数"缩掉"。
     * - 与 app 无关（coolapk / 小米笔记 / 计算器 全部一致），
     *   也不是 `one_handed_mode_scale`（改过，无影响）。
     *
     * 后果（用户原话「大尺寸后小窗更加不贴底下了」）：
     * `可见底边 = top + 高度 x 0.70`，窗口越高，离屏幕底越远。
     *
     * 处理办法见 [deliverRect]：把用户想看到的矩形当意图，按本系数反推下发值。
     *
     * ★ 1.0.109（fix109）：**0.70 是对的，fix105 改错了**。用户实测（17:05）确认
     *   HyperOS 3 上小窗「实际显示会缩小到 0.7」——bounds 1080x1728 渲染出来视觉
     *   756x1210 比例正常；fix105 的探针 `mgr.getScale()=1.0000` 读的不是渲染缩放
     *   （渲染缩放在图层/SurfaceFlinger 层，fix42 的 displayFrame 数据才是硬证据）。
     *   按 1.0 下发会让窗口视觉只有意图的 70%（「高德比例不对」）。恢复 0.70 补偿。
     */
    const val MIUI_LAYER_SCALE = 0.70f

    /**
     * ★ fix42：把「想让人看到的视觉矩形」反推成「要下发的 task bounds」。
     *
     * 因为缩放锚点在左上角，`left/top` 会**原样保留**，只有右/下会被缩掉，
     * 所以只要把宽高各除以 [MIUI_LAYER_SCALE] 下发，视觉右下角就正好落在意图位置：
     * ```
     * 意图  [left, top][left+w, top+h]
     * 下发  [left, top][left+w/S, top+h/S]
     * 视觉  left/top 不动，宽高 x S 回 w/h  -> 与意图完全重合
     * ```
     *
     * ⚠ 反推结果会**超出屏幕**（要 1080 宽就得下发 1543）。真机实测：正坐标超界
     *   会被原样接受（`[0,1200][1080,3000]`、`[0,1200][2160,2400]` 都原样生效），
     *   被拒绝的只有**负坐标**。超出屏幕的部分由系统裁掉，用户看不到，不影响观感。
     *
     * ★ fix43：[scale] 由调用方给出 —— 澎湃的 0.70 被 hook 中和掉之后这里要传
     *   1.0（否则窗口会被放大 1.43 倍）。判据见
     *   [xiaojw.memoryFreeform.core.HookBridge.miuiLayerScale]。
     *
     * @return intArrayOf(left, top, right, bottom)
     */
    fun deliverRect(
        intentLeft: Int,
        intentTop: Int,
        intentW: Int,
        intentH: Int,
        scale: Float = MIUI_LAYER_SCALE
    ): IntArray {
        val s = if (scale > 0.01f) scale else MIUI_LAYER_SCALE
        val dw = (intentW / s).toInt().coerceAtLeast(1)
        val dh = (intentH / s).toInt().coerceAtLeast(1)
        return intArrayOf(intentLeft, intentTop, intentLeft + dw, intentTop + dh)
    }

    /** 存储值 -> 真实比例。滑块拖到 1.2 时得到 1.0，即"设置多少就是多少"。 */
    fun trueRatio(scaleRatio: Float): Float =
        (scaleRatio / SCALE_REFERENCE).coerceIn(0.1f, 4f)

    /** 存储值 -> 展示用百分比（1.2 -> 100%）。 */
    fun percent(scaleRatio: Float): Int = (trueRatio(scaleRatio) * 100).toInt()

    /** 计算结果。[maxWidth]/[maxHeight] 也带出来，方便 UI 说明"是不是被收边了"。 */
    data class Resolved(
        val width: Int,
        val height: Int,
        val maxWidth: Int,
        val maxHeight: Int
    ) {
        val widthClamped: Boolean get() = width >= maxWidth
        val heightClamped: Boolean get() = height >= maxHeight
        val clamped: Boolean get() = widthClamped || heightClamped
    }

    /**
     * 上限 = 「必须能完整落在屏幕可用区」：宽就是屏宽，高度扣掉顶部与底部被占的系统条。
     * 超出只是**收边**，不是拒绝（真机实测正坐标超界也原样生效）。
     *
     * ★ fix86：`topInset` = **顶部要让出的高度**（横屏时的状态栏，见 [statusBarHeight]）。
     *   为什么必须扣：横屏屏高只有 1080，设置里填的竖屏高度（默认 1440）会被收边到
     *   "整屏高"，于是 `top = usableH - h = 0` —— 小窗顶边贴到屏幕最上沿，澎湃画在窗口
     *   顶部的**移动手势条**正好落在状态栏上（用户报的"横屏时手势条怎么在状态栏上"）。
     *   扣掉顶部之后 `h` 上限变小，`top` 也就停在状态栏下沿，手势条露出来、点得到。
     */
    fun resolve(
        displayW: Int,
        displayH: Int,
        scaleRatio: Float,
        screenW: Int,
        screenH: Int,
        navInset: Int,
        topInset: Int = 0
    ): Resolved {
        val ratio = trueRatio(scaleRatio)
        val maxW = screenW.coerceAtLeast(MIN_SIDE)
        val maxH = (screenH - navInset.coerceAtLeast(0) - topInset.coerceAtLeast(0))
            .coerceAtLeast(MIN_SIDE)
        val loW = MIN_SIDE.coerceAtMost(maxW)
        val loH = MIN_SIDE.coerceAtMost(maxH)
        val w = (displayW * ratio).toInt().coerceIn(loW, maxW)
        val h = (displayH * ratio).toInt().coerceIn(loH, maxH)
        return Resolved(w, h, maxW, maxH)
    }

    /**
     * ★ fix64：**当前屏幕是不是横屏**（以宽 > 高判定）。
     *
     * [realScreenSize] 返回的是**当前旋转状态下**的尺寸（真机实测：竖屏 `1080x2400`，
     * 转成横屏后 `2400x1080`），所以直接比宽高就是"这一帧用户看到的屏幕"，
     * 不需要再读 `Configuration.orientation`（那个在 system_server / 非 Activity 上下文里
     * 常年是竖屏值，会误判）。
     */
    fun isLandscape(ctx: Context): Boolean = realScreenSize(ctx).let { it.first > it.second }

    /**
     * ★ fix64：**把矩形收进屏幕**（收尺寸 → 再收位置，保证整块都在屏内）。
     *
     * 为什么必须有：位置记忆是**按方向分开存**的，但记忆可能来自
     *  ① 另一个方向的屏幕（用户转屏前记住的 `[300,800][1100,2000]`，横屏高只有 1080）；
     *  ② 更早的屏幕尺寸（改了显示尺寸 / 分辨率）；
     *  ③ 澎湃重摆、动画中间帧等脏值。
     * 直接下发就会得到"小窗跑到屏幕外、用户看不见"（真机实测：横屏时小窗底边 2000 > 屏高
     * 1080 ⇒ 只剩顶上一条），所以**任何**从记忆来的几何在交付前都要过这里。
     *
     * ★ fix86：`minTop` = 允许的最小 top（横屏时 = 状态栏高度）。记忆里存着 `top=0`
     *   （横屏时"设置高度被收边到满屏高"落下的脏值）会让窗顶压在状态栏上，这里把它抬到
     *   状态栏下沿；同时高度上限也跟着减掉 `minTop`，保证抬完之后底边不越界。
     *
     * @return 收好的矩形；尺寸退化到不可用时返回 null（调用方应退回设置值）
     */
    fun clampRect(
        rect: IntArray,
        screenW: Int,
        screenH: Int,
        minTop: Int = 0
    ): IntArray? {
        if (rect.size != 4) return null
        val top = minTop.coerceIn(0, (screenH - MIN_SIDE).coerceAtLeast(0))
        val maxW = screenW.coerceAtLeast(MIN_SIDE)
        val maxH = (screenH - top).coerceAtLeast(MIN_SIDE)
        val w = (rect[2] - rect[0]).coerceIn(MIN_SIDE.coerceAtMost(maxW), maxW)
        val h = (rect[3] - rect[1]).coerceIn(MIN_SIDE.coerceAtMost(maxH), maxH)
        if (w <= 0 || h <= 0) return null
        val l = rect[0].coerceIn(0, (screenW - w).coerceAtLeast(0))
        val t = rect[1].coerceIn(top, (screenH - h).coerceAtLeast(top))
        return intArrayOf(l, t, l + w, t + h)
    }

    /** 这个矩形是不是**完整落在**屏幕内（按当前方向判）。 */
    fun isInsideScreen(rect: IntArray, screenW: Int, screenH: Int): Boolean =
        rect[0] >= 0 && rect[1] >= 0 && rect[2] <= screenW && rect[3] <= screenH

    /**
     * ★ fix86：系统状态栏高度（px）。
     *
     * 从 `android:dimen/status_bar_height` 读（它是 framework 的公开资源，只是没有
     * 生成 R 常量，只能靠 `getIdentifier`）；读不到退回 24dp。
     *
     * 用途：横屏时状态栏横贯屏幕顶部，小窗顶边贴到 y=0 的话，澎湃画在窗口顶部的
     * **移动手势条**正好被压在状态栏上（用户报的现象）。要让出这一条。
     */
    fun statusBarHeight(ctx: Context): Int {
        val fallback = (24 * ctx.resources.displayMetrics.density).toInt()
        return runCatching {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) ctx.resources.getDimensionPixelSize(id) else fallback
        }.getOrDefault(fallback).coerceIn(0, 200)
    }

    /**
     * ★ fix86：**当前方向下顶部要让出的高度**。
     *
     * 只在**横屏**让 —— 竖屏时屏高富余（2400），设置高度基本不会被收边到满屏，
     * 顶部本来就在屏幕中间，改它只会让习惯了"顶天立地大窗"的用户觉得窗缩了；
     * 横屏屏高只有 1080，设置高度一收边就顶到 y=0，正是出问题的方向。
     */
    fun topAvoidPx(ctx: Context): Int {
        val (w, h) = realScreenSize(ctx)
        return if (w > h) statusBarHeight(ctx) else 0
    }

    /**
     * 屏幕**真实**分辨率（含状态栏/导航栏区域），**跟随当前旋转**。
     *
     * 不能用 `resources.displayMetrics` —— 本机实测它给 1080x**2292**，比真实
     * 1080x2400 少一截，拿它算小窗位置会贴不到底、算上限会少给 100+px。
     */
    fun realScreenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val p = Point()
        runCatching {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealSize(p)
        }
        if (p.x > 0 && p.y > 0) return p.x to p.y
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
    }
}
