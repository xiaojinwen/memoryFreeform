package xiaojw.memoryFreeform.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Corner { LEFT, RIGHT }

/**
 * 全局设置状态。
 *
 * ## fix52：只剩「澎湃 Freeform 一条路线」之后的字段
 *
 * 系统小窗路线下窗口几何、触摸分发、二级页归属全由澎湃接管，自造窗口时代那些开关
 * **一个都不生效**（整机模式、任务搬运兜底、轮询旋钮、触摸通道偏好、路线切换）。
 * 留着只会让设置页出现"改了没反应"的开关。
 *
 * ★ fix56 又删掉一个 `navBarEnabled`：小窗底部那条「三键条」整条不要了 ——
 *   返回交给系统（手势导航的侧边返回 / 三键导航的实体键），最近与应用改从
 *   悬浮球菜单进。压在小窗上的浮层越少，抢触摸的地方就越少。
 *
 * ★ fix57 把 `navigationMode`（手势/三键 二选一）也换成了 [AppState.bottomGapPx]
 *   （离底边间距，px）。原来那个枚举**只**决定一件事 —— 小窗底部避让多少像素，
 *   而"避让多少"本来就是个连续值（各家 ROM 的导航栏高度都不一样，还有用户故意
 *   想留点缝）。枚举把它压成 0 / 导航栏高度两档，反倒不自由：三键导航用户想微调
 *   一点都做不到。现在直接给数值，语义与观感一一对应。
 */
data class AppState(
    val isEnabled: Boolean = false,
    val isPaused: Boolean = false,
    val corner: Corner = Corner.RIGHT,
    /** 缩放滑块的存储值（= 真实比例 x [WindowSizing.SCALE_REFERENCE]，展示时用 [WindowSizing.percent] 除回来） */
    val scaleRatio: Float = 1.2f,
    val displayWidth: Int = 756,
    val displayHeight: Int = 1210,
    /**
     * ★ fix72/fix109：下发补偿比例（= [WindowSizing.MIUI_LAYER_SCALE]，现为 0.70）。
     *
     * 语义：App 开窗时把「想让人看到的尺寸」÷ 这个比例再下发（[WindowSizing.deliverRect]），
     * 因为系统会把 freeform 图层渲染成 0.70 倍。
     * ★ 不再从 prefs 读写（fix105 曾固定 1.0 且被 fix109 推翻，见 WindowSizing 注释）。
     */
    val layerScale: Float = WindowSizing.MIUI_LAYER_SCALE,
    /**
     * ★ fix57：小窗底部与屏幕底边之间留出的间距（px）。
     *
     * 0 = 贴到屏幕最底（手势导航要的就是这个：底部那条只是**透明**手势条，
     * 让出缝反而显得"没贴住"）。三键导航下底部是**实体**导航栏，必须填它的高度，
     * 否则小窗最下面一排按钮被压住点不到。
     *
     * 同时参与两件事：窗口的 y 落点，以及 [WindowSizing.resolve] 里"可用高度"的上限。
     */
    val bottomGapPx: Int = 0,
    /**
     * 悬浮球开关（持久化）。
     * ★ fix141：默认**关** —— 悬浮球是常驻浮层，装完就默认挂一个球在屏上太打扰；
     *   需要的人自己在设置里打开（打开时会顺带检查悬浮窗权限）。
     */
    val floatBallEnabled: Boolean = false,
    /**
     * ★ 1.0.102：悬浮球菜单的交互方式（持久化）。
     * false = 点击展开（默认）：点一下弹菜单，再点收起；
     * true = 按住滑动：按下即弹菜单，滑到菜单项上松手触发；按住不动 = 收菜单进入拖动。
     */
    val floatBallSlideMode: Boolean = false,
    /**
     * ★ fix137：悬浮球**空闲自动贴边**（默认 true = 开）。
     * 开：5 秒无操作自动吸附到最近边缘并收起（只留一条小 peek 不挡内容）；
     *     点一下收起的球即弹回正常停靠位。
     * 关：保持常驻，永不自动收起。
     */
    val autoDock: Boolean = true,
    /**
     * ★ fix88：**记不记「小窗大小」**（默认 false = 只记位置）。
     *
     * 记忆文件里存的是完整矩形 `l,t,r,b`，本来等于「位置 + 大小一起记」。关掉之后
     * 只用记忆里的 **left/top**，尺寸一律按当前设置算 —— 于是：
     *  - 改设置里的尺寸 / 缩放 / 离底边间距**立刻对所有 App 生效**，不再需要清空
     *    辛苦攒下的位置记忆（旧行为：不清就"改了设置没反应"，清了位置也一起没）；
     *  - 代价是"用户手动拉伸过的小窗大小"不再保留。
     * 打开（true）则完全沿用旧行为：位置 + 大小都按记忆来。
     *
     * ⚠ 只有 App 侧**读取**时按本开关分叉；system_server 里的记录钩子照旧把完整
     *   矩形写进记忆文件（尺寸信息始终留着，开关来回切不会有数据丢失）。
     */
    /**
     * ★ fix88 加、★ fix91 默认改为开：记不记「小窗大小」。
     * 开（默认）= 位置 + 大小一起记，拉伸过的尺寸下次打开能恢复（用户明确期望）；
     * 关 = 只记位置，尺寸永远按设置走（改尺寸设置立刻对所有 App 生效、且不清位置记忆）。
     */
    val rememberWindowSize: Boolean = false,
    /** 最近一次开进小窗的目标，供悬浮球一键复现 */
    val lastPackage: String? = null,
    val lastActivity: String? = null
)

object StateManager {
    private const val PREFS = "memory_freeform_settings"
    /** 小窗心跳存活窗口：超过这个时长没上报 = 服务已死，enabled 作废 */
    private const val _ENABLED_TTL_MS = 6_000L
    private const val KEY_WIDTH = "display_width"
    private const val KEY_HEIGHT = "display_height"
    private const val KEY_SCALE = "scale_ratio"
    private const val KEY_CORNER = "corner"
    private const val KEY_BOTTOM_GAP = "bottom_gap_px"
    /** 老版本的导航模式键（fix57 已废弃），只用于一次性迁移 */
    private const val KEY_NAV_LEGACY = "navigation_mode"
    private const val KEY_BALL = "float_ball_enabled"
    /** ★ 1.0.102：悬浮球交互方式（false=点击展开 / true=按住滑动选择） */
    private const val KEY_BALL_SLIDE = "float_ball_slide_mode"
    /** ★ fix137：悬浮球空闲自动贴边（默认 true = 开） */
    private const val KEY_AUTO_DOCK = "float_ball_auto_dock"
    /** ★ fix88 加 / ★ fix91 默认开 / ★ fix142 改回默认关：记不记小窗大小 */
    private const val KEY_REMEMBER_SIZE = "remember_window_size"
    private const val KEY_LAST_PKG = "last_package"
    private const val KEY_LAST_ACT = "last_activity"
    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(AppState())
    val stateFlow: StateFlow<AppState> = _state.asStateFlow()
    val current: AppState get() = _state.value

    /**
     * "小窗是否真的开着"的可观察版本（= [isWindowAlive]）。
     *
     * ★ fix59：订阅方是**悬浮球**（[xiaojw.memoryFreeform.ui.BallView.alive] —— 小窗开着
     *   就高饱和、关着就发灰）。**不再是 MainActivity**：本页不再于小窗打开时退到后台
     *   （小窗是浮在它上面的 freeform 层，见 MainActivity 里的说明）。
     *
     * 必须用流而不是轮询：状态要在窗口视图挂上/摘掉的**同一刻**推出去，球当帧就变色；
     * 轮询最快也要一拍，而且"服务被系统杀掉"这条路径轮询判不出。
     *
     * ⚠ 只在**值真的变化**时才 emit：收方一变就要改外观，稳定状态下必须一次都不发。
     */
    private val _windowAlive = MutableStateFlow(false)
    val windowAliveFlow: StateFlow<Boolean> = _windowAlive.asStateFlow()

    /** 重新计算并**按需**推送 [windowAliveFlow]（值没变则什么都不做）。 */
    private fun syncWindowAlive() {
        val alive = isWindowAlive()
        if (alive != _windowAlive.value) _windowAlive.value = alive
    }

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val p = prefs ?: return
        _state.value = _state.value.copy(
            displayWidth = p.getInt(KEY_WIDTH, 756),
            displayHeight = p.getInt(KEY_HEIGHT, 1210),
            scaleRatio = p.getFloat(KEY_SCALE, 1.2f),
            layerScale = WindowSizing.MIUI_LAYER_SCALE,
            corner = runCatching { Corner.valueOf(p.getString(KEY_CORNER, Corner.RIGHT.name)!!) }.getOrDefault(Corner.RIGHT),
            bottomGapPx = readBottomGap(p, context),
            // ★ fix141：默认关（旧版默认 true，已存过值的老用户不受影响）
            floatBallEnabled = p.getBoolean(KEY_BALL, false),
            floatBallSlideMode = p.getBoolean(KEY_BALL_SLIDE, false),
            autoDock = p.getBoolean(KEY_AUTO_DOCK, true),
            // ★ fix142：默认关（fix91 曾改为默认开；老用户已存过值的不受影响）
            rememberWindowSize = p.getBoolean(KEY_REMEMBER_SIZE, false),
            lastPackage = p.getString(KEY_LAST_PKG, null)?.takeIf { it.isNotEmpty() },
            lastActivity = p.getString(KEY_LAST_ACT, null)?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * ★ fix57：读「离底边间距」，顺带把老版本的导航模式**迁移**过来。
     *
     * 老 prefs 里存的是 `navigation_mode = GESTURE / BUTTON`。这两种取值恰好对应
     * 两个具体间距：手势 → 0（贴底），三键 → 系统导航栏高度。所以要迁移就得知道
     * 本机导航栏多高，从系统 dimen 里取一次（取不到就退回 0，最多是让用户手填）。
     *
     * 只在 [KEY_BOTTOM_GAP] **不存在**时才迁移 —— 一旦用户在新版里存过值，
     * 哪怕存的是 0，也不能再用旧键覆盖他。
     */
    private fun readBottomGap(p: SharedPreferences, context: Context): Int {
        if (p.contains(KEY_BOTTOM_GAP)) return p.getInt(KEY_BOTTOM_GAP, 0).coerceAtLeast(0)
        val legacy = p.getString(KEY_NAV_LEGACY, null)
        if (legacy == null) return 0
        val gap = if (legacy == "BUTTON") runCatching {
            val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0) else 0
        // 迁完就把旧键抹掉，免得下次又走一遍
        p.edit()?.remove(KEY_NAV_LEGACY)?.apply()
        p.edit()?.putInt(KEY_BOTTOM_GAP, gap)?.apply()
        return gap
    }

    private fun save() {
        prefs?.edit()?.putInt(KEY_WIDTH, current.displayWidth)?.putInt(KEY_HEIGHT, current.displayHeight)
            ?.putFloat(KEY_SCALE, current.scaleRatio)?.putString(KEY_CORNER, current.corner.name)
            ?.putInt(KEY_BOTTOM_GAP, current.bottomGapPx)
            ?.putBoolean(KEY_BALL, current.floatBallEnabled)
            ?.putBoolean(KEY_BALL_SLIDE, current.floatBallSlideMode)
            ?.putBoolean(KEY_AUTO_DOCK, current.autoDock)
            ?.putBoolean(KEY_REMEMBER_SIZE, current.rememberWindowSize)
            ?.putString(KEY_LAST_PKG, current.lastPackage.orEmpty())
            ?.putString(KEY_LAST_ACT, current.lastActivity.orEmpty())
            ?.apply()
    }

    fun updateEnabled(enabled: Boolean) {
        // 关闭时记下时刻：心跳自愈要在这之后静默一段时间，
        // 否则"关了又被心跳点亮"会让悬浮球反复变色（fix59 起它订阅的就是这个状态，见 [closedAt]）
        if (!enabled) closedAt = android.os.SystemClock.uptimeMillis()
        _state.value = current.copy(isEnabled = enabled)
        // 存活判据跟着变（关闭时立刻撤，不必等下一拍或心跳超时）
        syncWindowAlive()
    }
    fun updatePaused(paused: Boolean) { _state.value = current.copy(isPaused = paused) }

    /** 小窗存活心跳时刻（[android.os.SystemClock.uptimeMillis]）。0 = 从未上报。 */
    @Volatile private var enabledBeatAt = 0L

    /**
     * 明确的"已关闭"截止时刻。
     *
     * ★ 这是修一个**心跳自愈与主动关闭互相打架**的死循环：
     *   `windowHeartbeat()` 会在 `isEnabled=false` 时把它自愈回 true（用来兜住
     *   "服务被系统杀掉但标记没清"）。可一旦用户主动关窗，
     *   `onDestroy → updateEnabled(false)` 与还活着的心跳线程就会对着写：
     *     关 → 心跳自愈成 true → 本页又退后台 → 心跳超时(6s) → 恢复 → 关 → …
     *   用户看到的就是"关闭小窗后界面反复闪"。
     *
     *   有了这个时间戳：**主动关闭后 [HEARTBEAT_SELFHEAL_QUIET_MS] 内禁止自愈**，
     *   足够服务死透；之后即便真有残留心跳也不再自愈（会话已结束）。
     *   下次真正开窗时 `setWindowOpen()` 会清掉这个标记。
     */
    @Volatile private var closedAt = 0L

    /** 主动关闭后的自愈静默期：这段时间内心跳不得把 isEnabled 写回 true */
    private const val HEARTBEAT_SELFHEAL_QUIET_MS = 10_000L

    /**
     * 标记"小窗真的开起来了"（服务启动/复活时调用）。
     * 清掉 [closedAt]，让心跳自愈重新具备资格。
     *
     * ⚠ **不在这里点亮 `isEnabled`** —— 本方法在 `onStartCommand` 里被调用，
     * 那时窗口还没建。在这里点亮会让本页**先于**小窗退后台。
     * `isEnabled` 的重新点亮交给 [markViewsAttached]（视图真的进栈之后）。
     */
    fun setWindowOpen() {
        closedAt = 0L
        enabledBeatAt = android.os.SystemClock.uptimeMillis()
    }

    /**
     * 窗口视图是否**真的**在窗口栈里（`WindowManager.addView` 成功）。
     *
     * ★ 这是"小窗该不该算开着"的**唯一权威判据**，比 `isEnabled` 与心跳都准：
     *  - `isEnabled` 只是"用户想开"的意图，服务被杀时不走 `onDestroy` 就会永久停在 true；
     *  - 心跳能兜住"服务被杀"，但有 [HEARTBEAT_SELFHEAL_QUIET_MS] 的静默期互动，
     *    且**服务在自己的窗口已经摘掉、进程却还没死透的那一小段时间里仍会打心跳**。
     *  - 只有这个标记是跟着**窗口生命周期**走的：`addView` 成功置 true，
     *    `onDestroy`（任何退出路径都会走）同步置 false。
     */
    @Volatile private var viewsAttached = false

    /** 窗口视图已挂上 —— 此刻起才允许对外宣称"小窗开着"（悬浮球据此亮起来）。 */
    fun markViewsAttached() {
        viewsAttached = true
        enabledBeatAt = android.os.SystemClock.uptimeMillis()
        // 视图真的进栈了：这里才是把 isEnabled 点亮的正确时机
        // （服务被系统杀死后重建时，它会顺带把标记修回来）
        if (!current.isEnabled) _state.value = current.copy(isEnabled = true)
        syncWindowAlive()
    }

    /** 窗口视图已摘掉（`onDestroy` 第一步调用，同步生效）。 */
    fun markViewsDetached() {
        viewsAttached = false
        // 顺带把"已关闭"时刻打上：后续任何残留心跳都不得再点亮
        closedAt = android.os.SystemClock.uptimeMillis()
        // 立刻通知收方（悬浮球）—— 不等轮询、不等心跳超时
        syncWindowAlive()
    }

    /** 小窗服务每轮上报"我还活着"（[windowHeartbeat]）。
     *
     * 为什么需要心跳而不是只信 [updateEnabled]：退出小窗有三条路（App 关闭按钮 /
     * 悬浮球 / 系统直接停服务），其中"服务被系统杀掉"根本不会走 onDestroy ——
     * enabled 会永久停在 true，本页就永远退不回来。
     * 有了心跳，超过 [_ENABLED_TTL_MS] 没上报就自动当已关闭。
     *
     * ★ 心跳本身**不写 state**（只在 isEnabled 需要自愈时才写）。
     *   它每 2 秒调用一次，若无条件 `_state.value = ...`，所有 `collectAsState()`
     *   的界面就会被每 2 秒重组一次 —— MainActivity 首页有几百个图标，
     *   那是一次可感的卡顿；而心跳的语义只是"更新一个时间戳"，
     *   它是**不给 UI 看**的（UI 通过 [isWindowAlive] 主动查询）。
     */
    fun windowHeartbeat() {
        // 窗口视图不在栈里 = 小窗已经关了（或还没开）：这一拍不是"存活"证据，
        // 不要刷新时间戳、更不许自愈。
        if (!viewsAttached) {
            // 顺带确认一次存活判据：服务被杀的场景下 `onDestroy` 没跑过，
            // 心跳是唯一还在动的东西，让它顺手把超时后的状态撤掉。
            syncWindowAlive()
            return
        }
        enabledBeatAt = android.os.SystemClock.uptimeMillis()
        // 刚开始就被关掉了：不写 state（避免 UI 一秒内看到来回切换）
        val now = android.os.SystemClock.uptimeMillis()
        if (!current.isEnabled) {
            // 主动关闭后的静默期内不自愈 —— 否则就是"关了又亮起来"的死循环
            if (now - closedAt < HEARTBEAT_SELFHEAL_QUIET_MS) return
            _state.value = current.copy(isEnabled = true)
        }
        syncWindowAlive()
    }

    /**
     * 小窗是否**真的**在跑。
     *
     * 三道判据**全部**成立才算数，缺一不可：
     *  1. [viewsAttached]：窗口视图确实在窗口栈里（唯一跟窗口生命周期走的信号）。
     *     它由 `addView` 成功 / `onDestroy` 同步翻转 —— **这一条就能挡住
     *     "小窗已经关了、本页还没回来"**，因为 `onDestroy` 是三条退出路径的公共出口。
     *  2. `isEnabled`：用户没有主动关闭（关闭会同时置 false）。
     *  3. 心跳没超时：服务被系统直接杀掉时不会走 `onDestroy`，靠这条兜住。
     */
    fun isWindowAlive(): Boolean {
        if (!viewsAttached) return false
        if (!current.isEnabled) return false
        val at = enabledBeatAt
        if (at == 0L) return false
        return android.os.SystemClock.uptimeMillis() - at <= _ENABLED_TTL_MS
    }

    fun updateCorner(corner: Corner) { _state.value = current.copy(corner = corner); save() }
    fun updateScaleRatio(ratio: Float) { _state.value = current.copy(scaleRatio = ratio.coerceIn(0.2f, 4f)); save() }

    /**
     * ★ fix72：设置「澎湃图层缩放比例」（默认 0.70）。
     *
     * 范围 0.30~1.50：低于 0.30 会把下发值放大 3 倍以上（远超屏幕、没意义），
     * 高于 1.50 会反向缩小（也没有实际 ROM 会用）。改成 1.0 = 不补偿（若某 ROM 无缩放）。
     */
    fun updateDisplaySize(width: Int, height: Int) { _state.value = current.copy(displayWidth = width.coerceAtLeast(1), displayHeight = height.coerceAtLeast(1)); save() }
    /**
     * ★ fix57：设置「离底边间距」（px）。
     *
     * 上限 600px 是"手滑多打个 0"的兜底 —— 再大就不是单手模式了（本机导航栏 ~126px）。
     */
    fun updateBottomGap(px: Int) {
        _state.value = current.copy(bottomGapPx = px.coerceIn(0, 600))
        save()
    }
    fun updateFloatBall(enabled: Boolean) { _state.value = current.copy(floatBallEnabled = enabled); save() }

    /** ★ 1.0.102：切换悬浮球交互方式（点击展开 / 按住滑动选择）。 */
    fun updateFloatBallSlide(enabled: Boolean) { _state.value = current.copy(floatBallSlideMode = enabled); save() }

    /** ★ fix137：悬浮球空闲自动贴边开关。 */
    fun updateAutoDock(enabled: Boolean) { _state.value = current.copy(autoDock = enabled); save() }

    /**
     * ★ fix88：设置「记不记小窗大小」。
     *
     * 纯开关，**不动记忆**（记忆里始终存着完整矩形）：关掉只是让 App 侧重开时只取
     * left/top、尺寸按设置算。所以来回切不会有数据丢失，也不用清记忆。
     */
    fun updateRememberWindowSize(enabled: Boolean) {
        _state.value = current.copy(rememberWindowSize = enabled)
        save()
    }

    fun updateLastTarget(pkg: String?, act: String?) {
        _state.value = current.copy(lastPackage = pkg, lastActivity = act); save()
    }
}
