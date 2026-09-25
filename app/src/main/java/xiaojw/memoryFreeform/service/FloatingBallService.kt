package xiaojw.memoryFreeform.service
import xiaojw.memoryFreeform.core.SHLog

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import xiaojw.memoryFreeform.MemoryFreeformApp
import xiaojw.memoryFreeform.core.StateManager
import xiaojw.memoryFreeform.core.WindowWatcher
import xiaojw.memoryFreeform.ui.BallGlyph
import xiaojw.memoryFreeform.ui.BallView
import kotlin.math.abs

/**
 * 全局悬浮球：单手模式的入口。
 *
 * ★ fix56 改了交互：**单击 = 展开菜单**（旧版单击是开关小窗、长按才是菜单）。
 * 菜单项（fix60 精简后）：
 *  - 小窗开着：「最近任务 / 应用 / 隐藏悬浮球」；
 *  - 没开：「最近任务 / 应用 / 打开小窗（或「接管当前小窗」）/ 隐藏悬浮球」。
 * 小窗底部那条三键条已删除，所以「最近 / 应用」列表就从这里进。
 *
 * - 拖拽：跟手移动，松手自动吸附到左/右边缘（拖过就不算点击，不开菜单）
 * 悬浮球渲染在主屏（真实屏幕）上，不受小窗影响，因此小窗开启时依然可点。
 *
 * ★ fix57 修了菜单的落点：旧公式按 `球x ± 160dp` 摆，160dp 是"菜单大概多宽"的猜测，
 *   球吸在右侧时菜单会飘到离球很远的地方。现在先 measure 菜单真实尺寸，再贴到球的
 *   内侧（左半屏在球右、右半屏在球左），间隙 8dp、垂直对齐球心，并夹进屏幕内。
 *
 * ★ fix58 三件事：
 *  ① 菜单项**恒定** ——「最近任务 / 应用」一直都在（以前只在小窗活着时才给），
 *     没有会话时由服务侧走"只开面板"那条路；
 *  ② 展开菜单后**再次点击悬浮球 = 收起菜单**（DOWN 里收掉，UP 不再弹）；
 *  ③ 菜单窗口补上 `FLAG_NOT_TOUCH_MODAL` —— 旧版没带它，而没带它的窗口按官方文档
 *     会「consume all pointer events itself, regardless of whether they are inside of
 *     the window」，即**整屏**触摸都归它。"小窗顶部拖动条和底部手势条都点不动"
 *     这类全局触摸失灵，最可能是它的残留；现在菜单不再碰这套机制。
 *
 * ★ fix59 换掉外观：球改成 [BallView] 自绘（渐变球体 + 左上高光 + 内描边 + 小窗字形），
 *   高亮/发灰由 `StateManager.windowAliveFlow` 驱动 —— 见 [watchBallState]。
 *   原来那张 `ic_float_ball`（实心蓝圆 + 白描边）以及"点完菜单延迟 400ms 猜一次
 *   状态"的补丁一起删掉。
 *
 * ★ fix60 菜单**只留最有用的几项**：删掉「切换到左/右侧」（窗口上那颗 ⇄ 球就在手边）、
 *   「打开主界面」（桌面图标本来就能进）、「关闭小窗」（点窗口上那颗 ✕ 球才是"关掉眼前
 *   这个东西"的位置）。菜单是"当前场景下能做的动作"，不该把三个场景的入口堆在一起。
 *
 */
class FloatingBallService : Service() {

    companion object {
        private const val TAG = "FloatBall"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "memoryfreeform_floatball"
        private const val CLICK_SLOP = 12

        /** ★ fix58：菜单刚收起后，这段时间内不再重新弹（见 [menuClosedAt]）。 */
        private const val MENU_REOPEN_GUARD_MS = 350L

        /** ★ 1.0.101：按住多久算"长按"，长按生效后才能拖动悬浮球。 */
        private const val LONG_PRESS_MS = 1000L

        /** ★ fix130：悬浮球直径（dp）—— 52 太小不好点，加大到 60。所有球径引用统一走这里。 */
        private const val BALL_SIZE_DP = 60

        /** ★ 1.0.95 扇形菜单图标种类（[MenuIconView] 据此自绘白色笔画）。 */
        private const val IC_RECENTS = 0   // 三条横线（列表）
        private const val IC_APPS = 1      // 2x2 方块
        private const val IC_OPEN = 2      // 画中画外框
        private const val IC_ADOPT = 3     // 画中画外框（接管）
        private const val IC_CLOSE = 5     // ✕
        private const val IC_HIDE = 6      // 电源

        fun start(context: Context) {
            if (!android.provider.Settings.canDrawOverlays(context)) return
            val i = Intent(context, FloatingBallService::class.java)
            runCatching { context.startForegroundService(i) }
                .onFailure { SHLog.e(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingBallService::class.java)) }
        }
    }

    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var menuView: View? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    /**
     * ★ 1.0.101：长按生效后才允许拖动。DOWN 起一个 [LONG_PRESS_MS] 的计时器，
     * 到点还没滑走（没超过 CLICK_SLOP）就点亮 canDrag + 震动一下提示"可以拖了"；
     * 中途滑走则取消计时 —— 既不算拖动也不算点击。
     */
    private var canDrag = false

    /**
     * ★ 1.0.101：菜单开着时按住球 = **滑动选择**模式。
     * 手指从球滑到某个菜单项上（该项放大高亮），松手即触发；原地点一下松手 = 收起菜单。
     * 取代旧版的"DOWN 立刻收菜单" —— 那套收法让"滑到菜单项"无从谈起。
     */
    private var slideSelect = false

    /** 滑动选择当前指中的菜单项下标（-1 = 没有指中任何项）。 */
    private var hotIndex = -1

    /**
     * 扇形菜单各项在**屏幕坐标系**里的命中圆（圆心 + 半径）与对应动作。
     * ★ fix126 修正认知：菜单容器窗口盖住球（z 序 = add 顺序），菜单开着时**按在球上的
     * 手势由 MenuRoot 接管**并拿 raw 坐标在这里手动命中；只有"滑动模式按下即弹菜单"
     * 那条路（DOWN 在球上、菜单尚未存在）触摸流才真正归球窗口。
     */
    private var menuTargets: List<MenuTarget> = emptyList()

    private class MenuTarget(
        val cx: Float, val cy: Float, val hitR: Float,
        val cell: View, val action: () -> Unit
    )

    private val longPressRunnable = Runnable {
        if (dragging) return@Runnable
        canDrag = true
        ballView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        // 滑动模式下按住不动 = 收起菜单、进入拖动（否则菜单开着没法挪球）
        if (slideSelect) {
            slideSelect = false
            hotIndex = -1
            hideMenu()
        }
    }

    /** 这一下按下时菜单是否已经开着 —— 决定"原地松手"是收菜单还是保持菜单展开。 */
    private var menuOpenAtDown = false

    /**
     * ★ fix58：菜单**刚收起**的时刻，用来给"重新弹菜单"加一道几百毫秒的闸。
     *
     * `ACTION_OUTSIDE` 若先被处理，球的 DOWN 里读到的 `menuView` 已经是 null，
     * UP 就会把菜单原样弹回来（用户看到的就是"点了没关掉"）。有了这道闸，
     * 无论两条路谁先到，结果都是"这一下 = 收起"。
     */
    private var menuClosedAt = 0L

    /** ★ fix59：状态订阅只拉一次（见 [watchBallState]）。 */
    private var stateWatchStarted = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val manager get() = MemoryFreeformApp.instance.singleHandManager

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * ★ fix124b：**旋转适配**。球是 TYPE_APPLICATION_OVERLAY 常驻视图，服务不随旋转重建，
     * `params.x/y` 还是旧方向的坐标 —— 竖屏 y≈1080 转到横屏（屏高只有 1080）就贴出下沿，
     * 竖屏右缘 x≈1080 在横屏（宽 2400）里又离边老远。这里在配置变化时把球收进新屏幕：
     * x 吸附到最近的左/右缘（与 [snapToEdge] 同语义），y 夹进 `[0, 屏高-球径]`。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val ball = ballView ?: return
        val p = params ?: return
        val size = p.width.takeIf { it > 0 }
            ?: (BALL_SIZE_DP * resources.displayMetrics.density).toInt()
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        p.x = if (p.x + size / 2 < w / 2) 0 else w - size
        // ★ fix126：y 钳制带上底部留距
        p.y = p.y.coerceIn(0, (h - size - bottomMargin()).coerceAtLeast(0))
        hideMenu()
        mainHandler.post { runCatching { wm?.updateViewLayout(ball, p) } }
    }

    override fun onCreate() {
        super.onCreate()
        // ★ fix51：悬浮球是常驻前台服务，顺带把全局小窗监听拉起来 —— 这样即使单手模式
        //   没开，用户用澎湃侧边栏开的小窗关掉时位置也能被记住（监听是进程级单例，幂等）。
        xiaojw.memoryFreeform.core.WindowWatcher.ensureStarted(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addBall()
        // ★ fix59 补漏：外观订阅原来**没人调用** —— `watchBallState()` 定义了却没人拉起，
        //   于是球从来没按 windowAliveFlow 变过色，只有创建那一刻的一次性状态。
        watchBallState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("记忆小窗悬浮球")
            .setContentText("点一下展开菜单")
            .setOngoing(true)
        runCatching { startForeground(NOTIFICATION_ID, notif.build()) }
            .onFailure { SHLog.e(TAG, "startForeground failed", it) }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun addBall() {
        if (ballView != null) return
        val size = (BALL_SIZE_DP * resources.displayMetrics.density).toInt()
        // ★ fix59：外观改成自绘（[BallView]）—— 渐变球体 + 左上高光 + 内描边 + 小窗字形，
        //   不再是那张扁平的 `ic_float_ball`。小窗开着时高亮、关着时发灰半透明，
        //   状态由 windowAliveFlow 驱动（见 [watchBallState]），不再靠事后补刷。
        val ball = BallView(this, BallGlyph.WINDOW, accent = true).apply {
            alive = StateManager.isWindowAlive()
            alpha = ballAlpha()
        }
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size
            y = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        }

        @Suppress("ClickableViewAccessibility")
        ball.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    downX = p.x; downY = p.y
                    dragging = false
                    canDrag = false
                    hotIndex = -1
                    menuOpenAtDown = menuView != null
                    val slideMode = StateManager.current.floatBallSlideMode
                    if (menuOpenAtDown) {
                        // 菜单开着 = 滑动选择模式。按住球滑到某项上松手触发；
                        //   原地点一下松手 = 收起菜单。此时不启动长按计时（拖动不生效）。
                        slideSelect = true
                    } else if (slideMode) {
                        // ★ 1.0.102 滑动模式：按下即弹菜单，直接滑到菜单项松手触发；
                        //   长按不动 = 收菜单进入拖动（longPressRunnable 里处理）。
                        slideSelect = true
                        showMenu(p)
                        mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    } else {
                        slideSelect = false
                        mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (slideSelect) {
                        // 手指在滑（而不是按住不动）：取消"长按收菜单进拖动"的计时
                        val dx = e.rawX - downRawX
                        val dy = e.rawY - downRawY
                        if (abs(dx) > CLICK_SLOP || abs(dy) > CLICK_SLOP) {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                        updateHotItem(e.rawX, e.rawY)
                        return@setOnTouchListener true
                    }
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!canDrag && (abs(dx) > CLICK_SLOP || abs(dy) > CLICK_SLOP)) {
                        // 没等到长按就滑走：取消计时，这一下既不是拖动也不是点击
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (canDrag) {
                        dragging = true
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                        p.x = (downX + dx).toInt()
                        p.y = (downY + dy).toInt()
                            .coerceIn(0, (screenH() - size - bottomMargin()).coerceAtLeast(0))
                        runCatching { wm?.updateViewLayout(ball, p) }
                    }
                    true
                }
                // ★ 1.0.101 交互：单击 = 开/关菜单（拖动过不算）；长按生效但没动 = 什么都不做；
                //   滑动选择松手 = 指中了就触发那一项。★ 1.0.102：滑动模式下"原地松手"若是
                //   菜单刚由本次按下弹出，则保持展开（不立刻收回去）。
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    mainHandler.removeCallbacks(longPressRunnable)
                    when {
                        slideSelect -> {
                            slideSelect = false
                            val hot = hotIndex
                            hotIndex = -1
                            when {
                                hot >= 0 -> {
                                    // ★ fix126：必须**先**取 target 再 hideMenu ——
                                    //   hideMenu 会清空 menuTargets，旧代码先收菜单再查表，
                                    //   getOrNull 恒 null，滑动选择从来没真正触发过。
                                    val t = menuTargets.getOrNull(hot)
                                    hideMenu()
                                    t?.let {
                                        SHLog.i(TAG, "menu: 滑动选择 -> 触发")
                                        it.action()
                                    }
                                }
                                menuOpenAtDown -> hideMenu()
                                else -> Unit // 菜单刚弹出，保持展开
                            }
                        }
                        dragging -> snapToEdge(p, size)
                        canDrag -> Unit
                        else -> showMenu(p)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    mainHandler.removeCallbacks(longPressRunnable)
                    slideSelect = false
                    hotIndex = -1
                    if (dragging) snapToEdge(p, size)
                    canDrag = false
                    true
                }
                else -> false
            }
        }

        ballView = ball
        params = p
        runCatching { wm?.addView(ball, p) }
            .onFailure { SHLog.e(TAG, "addView failed", it) }
        applyBallState(StateManager.isWindowAlive())
    }

    private fun screenH(): Int = resources.displayMetrics.heightPixels

    /**
     * ★ fix126：球离屏幕底部的最小距离（横竖屏一致）—— 球贴死底边时，扇形菜单的
     * 两侧项与球同高、会被手势条/屏幕边缘顶住不好按，拖动钳制/吸附/旋转适配统一
     * 用这个地板把球抬起来一点，给扇形展开留出余量。
     */
    private fun bottomMargin(): Int = (56 * resources.displayMetrics.density).toInt()

    /** 小窗开着时高亮、关着时半透明 —— 一眼看出当前状态。 */
    private fun ballAlpha(): Float = if (StateManager.current.isEnabled) 1f else 0.72f

    /**
     * ★ fix59：让球的**外观**跟着会话状态走。
     *
     * 之前是"动作之后延迟 400ms 补一次 alpha"：菜单里点「打开小窗」猜一个 400ms 就当
     * 服务起来了。可小窗真正就绪的时刻只有 [StateManager] 知道（`markViewsAttached`
     * 那一下），猜错就是"球该亮的时候还是灰的"。改成订阅 `windowAliveFlow`：它由
     * `markViewsAttached` / `markViewsDetached` 在窗口生命周期的那一**刻**推一次，
     * 值没变不发射，稳定状态下零开销。
     *
     * 用一条后台线程 + `runBlocking` 收流（而不是 `CoroutineScope(Dispatchers.Main)`）：
     * 这里只需要"把布尔搬回主线程"，为此引入 Main 调度器不值得 —— 本类别的逻辑全是
     * Handler，一条线程更直白。线程是 daemon，随进程一起走。
     */
    private fun watchBallState() {
        if (stateWatchStarted) return
        stateWatchStarted = true
        Thread({
            runCatching {
                kotlinx.coroutines.runBlocking {
                    StateManager.windowAliveFlow.collect { alive ->
                        mainHandler.post { applyBallState(alive) }
                    }
                }
            }.onFailure { SHLog.w(TAG, "ball state watch ended", it) }
        }, "ball-state").apply { isDaemon = true }.start()
    }

    /** 主线程：把"小窗活着没有"画到球上。 */
    private fun applyBallState(alive: Boolean) {
        val v = ballView ?: return
        (v as? BallView)?.alive = alive
        v.background = null
        v.alpha = ballAlpha()
    }

    /** 拖拽松手后吸附到左/右边缘。 */
    private fun snapToEdge(p: WindowManager.LayoutParams, size: Int) {
        val w = resources.displayMetrics.widthPixels
        p.x = if (p.x + size / 2 < w / 2) 0 else w - size
        // ★ fix126：底部留距（见 [bottomMargin]）
        p.y = p.y.coerceIn(0, (screenH() - size - bottomMargin()).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(ballView, p) }
    }

    /**
     * ★ fix56：菜单（**单击悬浮球**展开，不再是长按）。
     *
     * ★ fix58：菜单的**形状恒定** ——「最近任务 / 应用」永远在，不再"忽隐忽现"。
     *
     * ★ fix60：菜单精简成「最近任务 / 应用 / （没有会话时）打开小窗 或 接管当前小窗 /
     *   隐藏悬浮球」。切的三个按钮都不再出现 —— 理由见类头注释。
     *
     * 判据用 [StateManager.isWindowAlive]（视图真的在窗口栈里），不是 `isEnabled` ——
     * 后者只是"用户想开"，服务被系统杀掉后会一直停在 true。
     *
     * ★ fix57 菜单位置：**贴着球**。先 `measure` 出菜单真实宽高，再按球在屏幕哪一半决定
     *   菜单放球的内侧（左半屏→球右侧，右半屏→球左侧），间隙 8dp；垂直居中于球，最后
     *   整体夹进屏幕内。旧公式用的 `±160dp` 是对菜单宽度的硬编码猜测，球吸在右侧时
     *   会明显离开球一大截。
     */
    @SuppressLint("ClickableViewAccessibility")
    /** 扇形菜单项。★ 1.0.95：菜单从"文字列表"改成"扇形展开的图标钮"（用户要求）。 */
    private class FanItem(val kind: Int, val label: String, val action: () -> Unit)

    private fun showMenu(p: WindowManager.LayoutParams) {
        if (menuView != null) { hideMenu(); return }
        // ★ fix58：刚收起就再弹一次没有任何意义，而且正是"点了没关掉"的元凶（见 menuClosedAt）
        val since = android.os.SystemClock.uptimeMillis() - menuClosedAt
        if (since < MENU_REOPEN_GUARD_MS) {
            SHLog.i(TAG, "menu: 刚收起 ${since}ms，本次不再弹出")
            return
        }
        val alive = StateManager.isWindowAlive()
        // ★ fix58：屏幕上**存在但不是我们开的** freeform 小窗（澎湃侧边栏 / 最近任务 /
        //   系统自己开的）。用它决定那一项是「打开小窗」还是「接管当前小窗」——
        //   直接说"打开小窗"会再开一个同包窗口，两个 freeform 窗口抢一套装饰。
        val foreign = if (alive) null else WindowWatcher.lastTasks()
            .firstOrNull { it.visible && it.pkg != packageName }

        val items = mutableListOf<FanItem>()
        items += FanItem(IC_RECENTS, "最近任务") { openCornerPanel(CornerWindowService.PANEL_RECENTS) }
        items += FanItem(IC_APPS, "应用") { openCornerPanel(CornerWindowService.PANEL_APPS) }
        if (!alive) {
            if (foreign != null) {
                val lb = appLabel(foreign.pkg)
                items += FanItem(IC_ADOPT, "接管$lb") { adopt(foreign.pkg) }
            } else {
                items += FanItem(IC_OPEN, "打开小窗") { manager.enableLast() }
            }
        } else {
            // ★ fix131：「切换角落」项按用户要求删除（切角功能整体退出菜单；
            //   CornerWindowService 的 EXTRA_SWITCH_CORNER 命令通道保留不动）。
            items += FanItem(IC_CLOSE, "关闭小窗") { sendSessionCmd(CornerWindowService.EXTRA_CLOSE_SESSION) }
        }
        // ★ fix129：「隐藏悬浮球」项按用户要求删除（隐藏走主界面开关，别占菜单一个坑）
        // items += FanItem(IC_HIDE, "隐藏悬浮球") { stopSelf() }

        // ---- 几何：以球心为圆心的扇形（圆弧永远朝屏幕内侧）----
        val density = resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()
        val itemSize = dp(52f)          // 圆钮直径
        // ★ fix127：展开半径定在 70dp（94 太散 → 47 太挤，用户反馈各打五十大板后取中）
        val radius = dp(70f)            // 展开半径
        val contSize = 2 * radius + itemSize
        val ballSize = params?.width?.takeIf { it > 0 } ?: dp(BALL_SIZE_DP.toFloat())
        // ★ fix130：**全链路绝对屏幕坐标**。实测本机 overlay 布局 y 原点在状态栏下方
        // （球与菜单容器一致偏 +108px，frame=attrs+108），而触摸 rawX/rawY 是绝对坐标 ——
        // 旧代码全用布局坐标，命中圆比 raw 触摸整体低了 108px（关不掉/滑不中的根源）。
        // 现在球心、容器钳制、命中圆全按绝对坐标算，只有摆容器窗口那一下减回偏移。
        val loc = ballOnScreen()
        val offX = (loc?.get(0) ?: p.x) - p.x
        val offY = (loc?.get(1) ?: p.y) - p.y
        val ballCx = p.x + offX + ballSize / 2
        val ballCy = p.y + offY + ballSize / 2
        val swAbs = resources.displayMetrics.widthPixels
        val shAbs = screenH() + offY
        // 扇形朝向：球在上边缘 → 朝下；下边缘 → 朝上；左右边缘 → 朝屏幕内侧
        val baseDeg = when {
            ballCy < shAbs * 0.26f -> 90f
            ballCy > shAbs * 0.74f -> 270f
            ballCx < swAbs / 2f -> 0f
            else -> 180f
        }
        val spread = 180f                // 半圆展开（1.0.99：去文字标签，张角改 180°）
        val n = items.size
        // 容器位置（绝对坐标）：尽量让球心落在容器中心，越界就夹回屏幕内
        val winX = (ballCx - contSize / 2).coerceIn(0, (swAbs - contSize).coerceAtLeast(0))
        val winY = (ballCy - contSize / 2).coerceIn(0, (shAbs - contSize).coerceAtLeast(0))
        val cx = (ballCx - winX).toFloat()
        val cy = (ballCy - winY).toFloat()

        val root = MenuRoot(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val anims = mutableListOf<ValueAnimator>()
        // ★ 1.0.101：登记每项的屏幕坐标命中圆，供"滑动选择"命中（触摸流在球窗口手上）
        val targets = mutableListOf<MenuTarget>()
        val hitR = itemSize / 2f + dp(6f)
        items.forEachIndexed { i, item ->
            val angle = if (n == 1) baseDeg else baseDeg - spread / 2 + spread * i / (n - 1)
            val rad = Math.toRadians(angle.toDouble())
            val tx = cx + radius * Math.cos(rad).toFloat() - itemSize / 2f
            val ty = cy + radius * Math.sin(rad).toFloat() - itemSize / 2f
            val cell = FrameLayout(this).apply {
                clipChildren = false
                addView(MenuIconView(this@FloatingBallService, item.kind).apply {
                    layoutParams = FrameLayout.LayoutParams(itemSize, itemSize)
                })
                translationX = cx - itemSize / 2f
                translationY = cy - itemSize / 2f
                alpha = 0f
                setOnClickListener {
                    hideMenu()
                    SHLog.i(TAG, "menu: ${item.label}")
                    item.action()
                }
            }
            root.addView(cell, FrameLayout.LayoutParams(itemSize, itemSize))
            targets += MenuTarget(
                winX + tx + itemSize / 2f, winY + ty + itemSize / 2f, hitR, cell
            ) { item.action() }
            val a = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240L
                interpolator = OvershootInterpolator(1.15f)
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    cell.translationX = (cx - itemSize / 2f) + (tx - (cx - itemSize / 2f)) * t
                    cell.translationY = (cy - itemSize / 2f) + (ty - (cy - itemSize / 2f)) * t
                    cell.alpha = t.coerceIn(0f, 1f)
                }
                start()
            }
            anims += a
        }

        val mp = WindowManager.LayoutParams(
            contSize, contSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // ★ fix58 语义保留：不模态 —— 菜单外的触摸照常给下面的窗口，
            //   WATCH_OUTSIDE_TOUCH 只用来"点别处收起"。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // ★ fix130：winX/winY 是绝对坐标，摆窗口要转回布局空间（减去实测偏移）
            x = winX - offX
            y = winY - offY
        }
        SHLog.i(TAG, "fan menu: 球心($ballCx,$ballCy) 朝向=$baseDeg ${n}项 容器${contSize}px @($winX,$winY)")
        menuShownAt = android.os.SystemClock.uptimeMillis()
        menuTargets = targets
        menuView = root
        runCatching { wm?.addView(root, mp) }
            .onFailure {
                SHLog.e(TAG, "fan menu addView failed", it)
                anims.forEach { a -> a.cancel() }
                menuView = null
            }
    }

    /** 把一条命令投给 [CornerWindowService]（关小窗 / 切角）。 */
    private fun sendSessionCmd(extra: String) {
        runCatching {
            startForegroundService(
                Intent(this, CornerWindowService::class.java).putExtra(extra, true)
            )
        }.onFailure { SHLog.e(TAG, "sendSessionCmd($extra) failed", it) }
    }

    /**
     * 扇形菜单的图标钮：**纯自绘**（不引图标库）—— 深色圆底 + 白色笔画图形，
     * 与 [BallView] 同一套视觉语言。种类见 companion 的 IC_*。
     */
    private class MenuIconView(context: Context, val kind: Int) : View(context) {
        private val d = resources.displayMetrics.density
        private fun dp(v: Float) = v * d
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF0202124.toInt(); style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = dp(1f)
        }
        private val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dp(2.1f); strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = w / 2f - dp(1f)
            c.drawCircle(w / 2f, h / 2f, r, bg)
            c.drawCircle(w / 2f, h / 2f, r - dp(0.5f), ring)
            val l = w * 0.30f; val rt = w * 0.70f; val tp = h * 0.30f; val b = h * 0.70f
            when (kind) {
                IC_RECENTS -> {
                    val x0 = w * 0.27f; val x1 = w * 0.73f
                    for (fy in floatArrayOf(0.35f, 0.50f, 0.65f)) {
                        c.drawLine(x0, h * fy, x1, h * fy, st)
                    }
                }
                IC_APPS -> {
                    val s = w * 0.17f; val g = w * 0.05f
                    val x0 = w / 2f - s - g / 2f; val x1 = w / 2f + g / 2f
                    val y0 = h / 2f - s - g / 2f; val y1 = h / 2f + g / 2f
                    val rr = dp(2.5f)
                    c.drawRoundRect(RectF(x0, y0, x0 + s, y0 + s), rr, rr, fill)
                    c.drawRoundRect(RectF(x1, y0, x1 + s, y0 + s), rr, rr, fill)
                    c.drawRoundRect(RectF(x0, y1, x0 + s, y1 + s), rr, rr, fill)
                    c.drawRoundRect(RectF(x1, y1, x1 + s, y1 + s), rr, rr, fill)
                }
                IC_OPEN, IC_ADOPT -> {
                    c.drawRoundRect(RectF(l, tp, rt, b), dp(3f), dp(3f), st)
                    c.drawRoundRect(
                        RectF(w * 0.38f, h * 0.40f, w * 0.60f, h * 0.56f),
                        dp(2f), dp(2f), fill
                    )
                }
                // ★ fix131：「切换角落」菜单项已删，⇄ 图标绘制随之退役
                IC_CLOSE -> {
                    c.drawLine(l, tp, rt, b, st)
                    c.drawLine(rt, tp, l, b, st)
                }
                IC_HIDE -> {
                    // 电源：缺口朝上的圆弧 + 顶部竖线
                    c.drawArc(
                        RectF(w * 0.28f, h * 0.32f, w * 0.72f, h * 0.76f),
                        -52f, 284f, false, st
                    )
                    c.drawLine(w / 2f, h * 0.22f, w / 2f, h * 0.46f, st)
                }
            }
        }
    }

    /**
     * ★ 1.0.101：滑动选择 —— 拿 raw 坐标在 [menuTargets] 里找指中的那一项，
     * 指中变化时做放大/还原动效 + 轻震一下。松手时 [hotIndex] 指向谁就触发谁。
     */
    private fun updateHotItem(rawX: Float, rawY: Float) {
        val targets = menuTargets
        var idx = -1
        for ((i, t) in targets.withIndex()) {
            val dx = rawX - t.cx
            val dy = rawY - t.cy
            if (dx * dx + dy * dy <= t.hitR * t.hitR) { idx = i; break }
        }
        if (idx == hotIndex) return
        targets.getOrNull(hotIndex)?.cell?.animate()
            ?.scaleX(1f)?.scaleY(1f)?.setDuration(90)?.start()
        hotIndex = idx
        targets.getOrNull(idx)?.cell?.animate()
            ?.scaleX(1.14f)?.scaleY(1.14f)?.setDuration(90)?.start()
        if (idx >= 0) {
            ballView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * ★ fix130：球的**绝对屏幕坐标**（真机实测：球窗口带 FLAG_LAYOUT_NO_LIMITS，
     * 布局 y 原点在状态栏下方 —— p.y=1031 而实际帧 y=1139，差一个状态栏 108px）。
     * 触摸 rawX/rawY 是绝对坐标，所以 hit 判定和菜单几何必须用这里，不能用布局坐标。
     * 调用方都在主线程（触摸回调 / showMenu），直接现查 view 位置，零状态不同步问题。
     */
    private val ballLoc = IntArray(2)
    private fun ballOnScreen(): IntArray? {
        val b = ballView ?: return null
        b.getLocationOnScreen(ballLoc)
        return ballLoc
    }

    /** 触摸是否落在球上（含 8dp 容差）。菜单窗口用它豁免"按在球上"的 OUTSIDE 收起。 */
    private fun touchOnBall(rawX: Float, rawY: Float): Boolean {
        val loc = ballOnScreen() ?: return false
        val size = params?.width?.takeIf { it > 0 } ?: return false
        val slop = (8 * resources.displayMetrics.density).toInt()
        return rawX >= loc[0] - slop && rawX <= loc[0] + size + slop &&
            rawY >= loc[1] - slop && rawY <= loc[1] + size + slop
    }

    /**
     * 扇形菜单根布局（原 fix56 的"点外面收起"职责保留）：
     * `FLAG_WATCH_OUTSIDE_TOUCH` 后菜单外的任何一下都会以 ACTION_OUTSIDE 送到根视图，
     * 在这儿收掉。菜单窗口不模态（NOT_TOUCH_MODAL），外面那一下照常给下面的窗口。
     *
     * ★ fix126：**球区域的手势由这里接管**。菜单容器窗口后 add、z 序在球之上，
     * 且容器恒盖住球 —— 旧注释"触摸流归球窗口"是错的：菜单开着时点球，事件落在
     * 本视图上，球的触摸监听根本收不到 DOWN/UP，"点球关菜单"从未通电。
     * 现在在本视图内复刻球的手势语义：
     *  - 落在球上、没挪窝的 UP = 收起菜单（点击模式的开/关配对）；
     *  - 按住球滑 = 滑动选择（[updateHotItem] 命中高亮，UP 时触发指中的那项）。
     */
    private inner class MenuRoot(context: android.content.Context) :
        FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var startedOnBall = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    // ★ fix130：弹出后 300ms 内的 OUTSIDE 一律忽略 —— 事件坐标不可信
                    if (android.os.SystemClock.uptimeMillis() - menuShownAt < 300) return true
                    if (!touchOnBall(event.rawX, event.rawY)) hideMenu()
                    return true
                }
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; moved = false
                    startedOnBall = touchOnBall(event.rawX, event.rawY)
                    SHLog.i(TAG, "menu-root: down onBall=$startedOnBall " +
                        "raw=(${event.rawX},${event.rawY}) ballScreen=" +
                        (ballOnScreen()?.let { "${it[0]},${it[1]}" } ?: "null") +
                        " layout=" + (params?.let { "${it.x},${it.y}" } ?: "null"))
                    // ★ fix129：不落球上 = 交回子视图正常分发（菜单图标自己的 click 才能通）
                    if (!startedOnBall) return super.dispatchTouchEvent(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!startedOnBall) return super.dispatchTouchEvent(event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > CLICK_SLOP || abs(dy) > CLICK_SLOP) {
                        moved = true
                        updateHotItem(event.rawX, event.rawY)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!startedOnBall) return super.dispatchTouchEvent(event)
                    SHLog.i(TAG, "menu-root: up moved=$moved hotIndex=$hotIndex")
                    if (!moved) {
                        // 原地点一下球 = 关闭菜单
                        hideMenu()
                    } else {
                        val hot = hotIndex
                        hotIndex = -1
                        // 与球侧 UP 同序：先取 target 再 hideMenu（见 fix126 注释）
                        val t = if (hot >= 0) menuTargets.getOrNull(hot) else null
                        hideMenu()
                        t?.action()
                    }
                    startedOnBall = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hotIndex = -1
                    moved = false
                    startedOnBall = false
                    return true
                }
            }
            return super.dispatchTouchEvent(event)
        }
    }

    /** ★ fix130：菜单弹出时刻 —— OUTSIDE 免疫闸用（刚 addView 时系统可能补发一条
     *  坐标不可信的 OUTSIDE，会把刚弹出的菜单瞬间收掉，真机实测 58ms 即收）。 */
    private var menuShownAt = 0L

    private fun hideMenu() {
        SHLog.i(TAG, "menu: hide from " + Throwable().stackTrace.take(5).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        })
        menuView?.let { runCatching { wm?.removeView(it) } }
        menuView = null
        menuTargets = emptyList()
        hotIndex = -1
        // ★ fix58：记下收起时刻 —— 给 showMenu 那道"刚收起就别再弹"的闸用
        menuClosedAt = android.os.SystemClock.uptimeMillis()
    }

    /** 应用的显示名（拿不到就退回包名）。只在菜单里给一项用，PM 查询一次可接受。 */
    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * ★ fix58：让小窗会话**接管**一个不是我们开的 freeform 小窗。
     *
     * 只投一条带包名的命令过去，服务侧 [CornerWindowService.EXTRA_ADOPT] 那条路
     * 只压几何、不发 `am start-activity` —— 所以不会像以前那样"再开一个同包小窗"。
     */
    private fun adopt(pkg: String) {
        runCatching {
            val i = Intent(this, CornerWindowService::class.java)
            i.putExtra(CornerWindowService.EXTRA_ADOPT, pkg)
            startForegroundService(i)
        }.onFailure { SHLog.w(TAG, "adopt($pkg) failed", it) }
    }

    /**
     * ★ fix55：让小窗会话打开「最近 / 应用」列表面板。
     *
     * 面板归 [CornerWindowService] 所有（它才是小窗会话），这里只投一条命令过去。
     * 服务侧收到 [CornerWindowService.EXTRA_PANEL] 后**只做开面板这一件事**并提前
     * return，不会顺手重新拉起小窗。
     */
    private fun openCornerPanel(mode: String) {
        runCatching {
            val i = Intent(this, CornerWindowService::class.java)
            i.putExtra(CornerWindowService.EXTRA_PANEL, mode)
            // ★ 1.0.101：面板跟随悬浮球 —— 球吸在左半屏面板就贴左，右半屏贴右。
            params?.let { p ->
                val size = p.width.takeIf { it > 0 } ?: (BALL_SIZE_DP * resources.displayMetrics.density).toInt()
                val left = p.x + size / 2 < resources.displayMetrics.widthPixels / 2f
                i.putExtra(CornerWindowService.EXTRA_PANEL_LEFT, left)
            }
            startForegroundService(i)
        }.onFailure { SHLog.w(TAG, "open panel($mode) failed", it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID, "记忆小窗悬浮球",
                    android.app.NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }

    override fun onDestroy() {
        hideMenu()
        ballView?.let { runCatching { wm?.removeView(it) } }
        ballView = null
        super.onDestroy()
    }
}
