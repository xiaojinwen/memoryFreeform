package xiaojw.memoryFreeform.service
import xiaojw.memoryFreeform.core.SHLog

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import xiaojw.memoryFreeform.core.Corner
import xiaojw.memoryFreeform.core.HookBridge
import xiaojw.memoryFreeform.core.StateManager
import xiaojw.memoryFreeform.core.WindowMemory
import xiaojw.memoryFreeform.core.WindowSizing
import xiaojw.memoryFreeform.core.WindowWatcher
import xiaojw.memoryFreeform.hook.HookContract
import xiaojw.memoryFreeform.root.RootManager
import xiaojw.memoryFreeform.ui.BallGlyph
import xiaojw.memoryFreeform.ui.BallView
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 单手模式核心：**把目标应用交给澎湃自己的 Freeform 小窗打开**（fix52 单路线）。
 *
 * ## 这个服务现在做什么
 *
 * 只有三件事，都不碰窗口几何本身：
 *
 *  1. **下发启动命令**：`am start-activity --windowingMode 5`（FREEFORM）+ 设备侧内联脚本
 *     里的 `am task resize`，让 RootTask 出生即目标矩形；
 *  2. **贴浮层**：退出球 + 切换左右角球挂在小窗外的屏幕边上（避开导航栏），
 *     外加「最近 / 应用」列表面板（[openPanel]，悬浮球菜单打开）。
 *     它们都是 `TYPE_APPLICATION_OVERLAY`(z=2038)，**层级高于系统 Freeform task**，
 *     所以球必须贴在小窗**外面**（`placeControls`）；
 *  3. **收尾**：退出时收掉我们开的 Freeform task、注销会话身份、抓一份记忆。
 *
 * 窗口的 Caption Bar / 触摸分发 / 焦点 / 二级页归属全部由澎湃接管 —— 这正是这条路线
 * 存在的理由（Display 重定向 28 轮、Window-Transform 4 轮都在跟系统抢这些）。
 *
 * ## fix52 删掉了什么（以及为什么必须删）
 *
 * 自造窗口那一套（占位窗口 / 触摸注入层）全部删除：它们都是 z=2038 的 overlay，
 * 层级高于系统 Freeform task —— 只要建出来就会把澎湃小窗整块盖住
 * （用户看到的就是"小窗被遮挡/点不到"）。所以这里从"两条路线按开关分支"改成"只留系统小窗"。
 *
 * 顺带也没了"键盘避让"：那是自造窗口才需要的 —— 应用在小窗里时，
 * 输入法由系统按 Freeform 窗口自己的 insets 处理。
 *
 * ## ★ fix54：主线程铁律（修"点关闭球/切角球会卡一下、画面卡住"）
 *
 * 症状是用户点**关闭球**或**切角球**时界面卡住，有时小窗画面整块不动。根因有三个，
 * 全是**在主线程上 fork su / 解图标**造成的，现在都定成了规矩：
 *
 *  1. **[onDestroy] 里不许有 root 命令。** 旧实现在第一行同步调用
 *     `captureWindowMemoryOnExit()`（两次 `am stack list` + `Thread.sleep(250)`，
 *     最坏 6.25s），而 `removeView` 排在它后面 —— 于是用户点完关闭球，
 *     球还挂在屏幕上、整个界面停住。现在主线程只翻状态 + 摘视图，抓记忆挪进后台线程。
 *  2. **watcher 线程里不许在主线程做的事。** 曾经的 [syncWindowRectAsync] 每 2 秒算一次
 *     小窗实测几何给面板定位，而 `HookBridge.miuiLayerScale()` 缓存未命中时会 fork 一个
 *     su（30~200ms）。★ fix60 面板改成按设置现算，这条管线整个删掉了 ——
 *     现在 watcher 回调只做会话状态维护，一个 root 命令都不发。
 *  3. **面板列表不许在主线程取图标。** 「应用」面板有 100+ 行，
 *     旧 [panelRow] 逐行调 `packageManager.getApplicationIcon` —— 按下那一刻卡几秒。
 *     现在图标在后台线程就画成 Bitmap（[appIcon]），主线程只 `addImageBitmap`。
 *
 * 另外**切角球不再是"重启服务"**：旧路径 `stopService` → `onDestroy` → `delay(200)`
 * → `startForegroundService` → 重新 `am start-activity`，用户看到整个小窗关掉重开；
 * 现在走 [reapplyHot]（重摆 overlay + 把当前 task 平移到新角落），目标应用全程不动。
 *
 * ## ★ fix55：面板接管返回
 *
 * **面板开着时，返回先关面板。** 系统的手势返回与实体返回键都只发给**聚焦窗口**，
 * 而面板以前是 NOT_FOCUSABLE 的 —— 返回会直接落到小窗里的应用上，用户想关列表
 * 结果把应用页面返回掉一层。现在面板窗口可聚焦（见 [openPanel] 的 flags），
 * 由 [PanelRoot] 在 `dispatchKeyEvent` 里接住 BACK：面板开着只收面板，
 * 面板不在才落到小窗。**这个"可聚焦"后来又变成"应用搜索框能用输入法"的前提**（fix56）。
 *
 * ## ★ fix56：删掉三键条，全部搬到悬浮球菜单 + 「应用」支持搜索
 *
 * 三条来自使用反馈的改动：
 *
 *  1. **三键条整条删除**（`setupNavBar` / `navBarButton` / `syncNavBar` / `sendKey` 与
 *     设置里的开关一并删掉）。"返回"交给系统（手势导航的侧边返回、三键导航的实体键），
 *     "最近 / 应用"由悬浮球菜单打开 —— 少一层压在小窗上的浮层，也就少一处抢触摸的地方。
 *     顺带删掉了 `AppState.navBarEnabled`（留着只会变成"改了没反应"的开关）。
 *  2. **悬浮球改成"点击展开菜单"**（原来单击是开关小窗、长按才是菜单）。菜单按小窗
 *     是否活着给出不同项（fix60 起只剩三/四项：最近任务 / 应用 / 隐藏悬浮球，
 *     没活着时多一项「打开小窗」或「接管当前小窗」）。
 *  3. **「应用」面板支持搜索**：面板顶部加一个输入框，输入即过滤。为此列表从
 *     `LinearLayout + ScrollView`（打字就得整棵重建）换成 **ListView + BaseAdapter**
 *     （只有可见行会被建/复用，过滤只是换个 list 再 `notifyDataSetChanged`）。
 *     输入法能弹出来，靠的正是 fix55 那个"面板窗口可聚焦"。
 */
class CornerWindowService : Service() {

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_CORNER = "corner"

        /**
         * ★ fix54：**热重应用**请求。值随意 —— 带着这个 extra 就表示
         * 「服务已经在跑，请按当前设置把控制球重新摆一遍，并把当前小窗平移到新角落」。
         *
         * 用它取代旧的"停服务 → delay(200) → 起服务 → 重新 `am start-activity` 目标应用"
         * 三件套：那条路走下来，用户按下切角球看到的是**整个小窗关掉重开**，
         * 而且期间会重新解析 Activity、重新走一遍 device-side 启动脚本。
         */
        const val EXTRA_HOT_REAPPLY = "hot_reapply"

        /** ★ 1.0.95：悬浮球扇形菜单命令 —— 关闭当前小窗会话（原窗口上 ✕ 球的行为）。 */
        const val EXTRA_CLOSE_SESSION = "close_session"

        /** ★ 1.0.95：悬浮球扇形菜单命令 —— 切换左/右下角（原窗口上 ⇄ 球的行为）。 */
        const val EXTRA_SWITCH_CORNER = "switch_corner"

        /**
         * ★ fix55：**列表面板**命令（由悬浮球菜单投递），值取 [PANEL_RECENTS] / [PANEL_APPS]。
         *
         * 为什么要这条通道：面板归本服务所有（它才是小窗会话），悬浮球是另一个 Service ——
         * 投一条命令过来复用同一个 [openPanel]，面板行为与从哪进无关。
         * ★ fix56 起这是面板的**唯一入口**（三键条已删）。
         * ★ fix58：**没有会话时也受理**（走 [startPanelOnlySession]）—— 面板本身不需要
         *   有小窗才成立（「应用」列表就是用来挑一个 app 开进小窗的），以前这里直接
         *   `return` 忽略，菜单上那两项就成了"点了没反应"。
         */
        const val EXTRA_PANEL = "panel_mode"

        /** [EXTRA_PANEL] 取值之一：「最近任务」 */
        const val PANEL_RECENTS = "recents"

        /** [EXTRA_PANEL] 取值之一：「应用」 */
        const val PANEL_APPS = "apps"

        /**
         * ★ 1.0.101：面板横向锚点（Boolean：true=贴左 / false=贴右）。
         * 由悬浮球随菜单命令带上 —— 球吸在左半屏，面板就展示在左侧。
         * 不带时退回设置里的角落（[StateManager.current.corner]）。
         */
        const val EXTRA_PANEL_LEFT = "panel_left"

        /**
         * ★ fix58：**接管**一个不是我们开的 freeform 小窗（澎湃侧边栏 / 最近任务 / 系统
         * 自己开出来的那些）。值是那个小窗的包名。
         *
         * 为什么需要它：用户先用系统的方式开了一个小窗，再点悬浮球菜单的「打开小窗」，
         * 我们会 `am start-activity --windowingMode 5` **再开一个同包的小窗** ——
         * 屏幕上就此出现两个 freeform task，澎湃的窗口装饰（顶部拖动条）只挂在其中
         * 一个上，另一个看上去就是"拖动条和手势条都点不动"的坏窗口。
         * 接管只做一件事：把这个**已存在**的 task 压到我们的设置几何，并登记进 [miuiTasks]
         * 由我们负责（关窗时一并收掉），全程**不发 `am start-activity`**。
         */
        const val EXTRA_ADOPT = "adopt_pkg"

        private const val TAG = "CornerWindow"
    }

    private var wm: WindowManager? = null
    /** ★ 1.0.95：控制层是否已建立（原 `controlsReady` 判据——窗口上的 ✕ / ⇄ 两颗球已删，
     *  关小窗 / 切角改走悬浮球扇形菜单的 [EXTRA_CLOSE_SESSION] / [EXTRA_SWITCH_CORNER] 命令）。 */
    private var controlsReady = false

    /**
     * 「最近 / 应用」列表面板（overlay，同一时刻最多一个）。
     *
     * 唯一入口：**悬浮球菜单**（单击悬浮球展开，[EXTRA_PANEL]，fix56 —— 三键条已删除）。
     * 面板窗口**可聚焦**（fix55），一来替小窗接住系统返回，二来「应用」里的搜索框
     * 要靠它才能拿到输入法 —— 见 [PanelRoot]。
     */
    private var panelView: View? = null
    private var panelLp: WindowManager.LayoutParams? = null

    /** ★ 1.0.101：本次面板命令带来的横向锚点（球在哪侧面板贴哪侧）；null = 按设置角落。 */
    private var panelAnchorLeft: Boolean? = null

    /** 面板打开时的模式，「最近 / 应用」互相切换用。 */
    private enum class PanelMode { RECENTS, APPS }

    private var panelMode: PanelMode? = null

    /**
     * 「应用」面板顶部那个搜索框（fix56）。「最近」面板没有它 → null。
     *
     * 面板关掉时一起置空，免得输入法还挂在一个已经摘掉的 View 上。
     */
    private var panelSearch: android.widget.EditText? = null

    // ★ fix60：这里原来有 lastVisualRect / windowRectKey / sessionScale 三件套 ——
    //   watcher 每轮读 `am stack list` 算出小窗的**实测视觉矩形**，供列表面板定位。
    //   整套删掉：面板改成**打开时按设置现算**（[settingsRect]）。理由见那个方法 ——
    //   实测矩形会被用户拖动、也会被澎湃在失焦时重摆到 `[35,127]`，面板跟着它跑就是
    //   "位置完全没按设置来"。顺带省掉 watcher 每 2 秒一次的计算与一次 HookBridge 读取。

    // ★ fix57：这里原来缓存着系统 dimen `navigation_bar_height`（三键导航时避让用）。
    //   现在这个间距由用户在设置里**直接填**（StateManager.current.bottomGapPx），
    //   服务侧不再需要缓存 —— 设置页会用 `WindowInsets.navigationBars` 把本机导航栏
    //   高度显示出来当参考（"本机导航栏 126px"），想避让就填这个数。

    /** 面板/窗口操作统一切回主线程用（Service 没有 runOnUiThread） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 待启动目标：窗口控制球挂好后再执行
    private var pendingPkg: String? = null
    private var pendingAct: String? = null
    private var launched = false

    /** 启动入口缓存：避免每次冷启动都 fork su 解析 resolve-activity（数百毫秒）。 */
    private val launcherCache = ConcurrentHashMap<String, String>()
    @Volatile private var cacheInvalidated = false

    /** 桌面包名：用于排除（桌面永远不进小窗）。 */
    @Volatile private var homePkg: String? = null

    /**
     * 由我们启动/接管过的 Freeform taskId → 包名。
     * 退出小窗时逐个 `am stack remove`（见 onDestroy），避免关掉单手模式后澎湃小窗残留屏幕；
     * 包名留给 force-stop 兜底用。
     */
    private val miuiTasks =
        java.util.Collections.synchronizedMap(mutableMapOf<Int, String>())

    /** LSP 模块是否已激活：只在后台线程探测一次并缓存，绝不能在主线程跑 root 命令。 */
    @Volatile private var hookActive = false

    /** 本次会话实际下发给 `am task resize` 的宽高 —— 关闭前采集用它校验读回值。 */
    @Volatile private var lastDeliveredWH = intArrayOf(0, 0)

    /** 观察维护的最后一次**稳定**可见矩形 `[l,t,r,b]`（仅内存，不落盘）。 */
    @Volatile private var lastStableRect: IntArray? = null

    /**
     * 会话在**全局**小窗监听（[WindowWatcher]）上的挂钩点。
     *
     * 位置记忆本身已由全局监听接管（对我们开的、系统开的都一样），会话侧只保留
     * "什么时候该收掉自己"这件事 —— 用户用澎湃自己的方式关掉小窗时不会走 `stopSelf()`，
     * `onDestroy` 根本不触发，只能在这里收尾。
     */
    private val watcherAttached = java.util.concurrent.atomic.AtomicBoolean(false)
    private var watcherListener: ((List<WindowWatcher.FfTask>) -> Unit)? = null
    @Volatile private var hadFreeformTask = false
    @Volatile private var ffGoneRounds = 0

    /** enabled 心跳只启一次（onStartCommand 可能被多次调用） */
    private var enabledHeartbeatStarted = false

    // ------------------------------------------------------------------ 环境

    private fun resolveHomePkg() {
        runCatching {
            val out = RootManager.get().execute(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
            ).output
            homePkg = out.lines().lastOrNull { it.contains("/") }?.trim()?.substringBefore('/')
            SHLog.i(TAG, "home pkg=$homePkg")
        }
    }

    /** 不进小窗、也不作为"当前前台应用"目标的包：本应用 / 系统 UI / **桌面**。 */
    private fun excludedPkgs(): Set<String> = buildSet {
        homePkg?.let { add(it) }
        add(packageName)
        add("com.android.systemui")
    }

    /**
     * 打开系统的「强制将活动设为可调整大小」与 Freeform 支持。
     *
     * `setLaunchWindowingMode(FREEFORM)` 只有在 `enable_freeform_support=1` 时才真正生效；
     * 不开的话 ActivityStarter 看到 mode=FULLSCREEN 会忽略 windowingMode。
     * `force_resizable_activities=1` 则让 `resizeableActivity=false` 的应用也接受几何要求。
     *
     * 每场会话只做一次（系统设置是全局持久的），失败只记日志 —— 拿不到 root 时
     * 退化为"部分应用开成系统默认几何"，不影响其它部分。
     */
    private fun forceResizableActivities() {
        runCatching {
            val rm = RootManager.get()
            val ffCur = rm.executeFast("settings get global enable_freeform_support", 3_000)
            if (ffCur.output.trim() != "1") {
                val r1 = rm.executeFast("settings put global enable_freeform_support 1", 3_000)
                SHLog.i(TAG, "enable_freeform_support=1 ok=${r1.success}")
            } else {
                SHLog.i(TAG, "enable_freeform_support already 1")
            }
            val cur = rm.executeFast("settings get global force_resizable_activities", 3_000)
            if (cur.output.trim() == "1") {
                SHLog.i(TAG, "force_resizable_activities already 1")
                return
            }
            val r = rm.executeFast("settings put global force_resizable_activities 1", 3_000)
            SHLog.i(TAG, "force_resizable_activities=1 ok=${r.success} ${r.output.take(80)}")
        }.onFailure { SHLog.w(TAG, "forceResizableActivities failed", it) }
    }

    /**
     * hook 自检：LSP 模块装上没有、缩放中和生效没有、出生几何有没有落地。
     *
     * 这三件事**都发生在 system_server 里**，App 侧只能靠 /data/system 下的自检文件看
     * （XposedBridge 日志在 LSPosed 管理器里，用户导出的 memoryfreeform.log 看不到）。
     * 装完 APK **必须重启手机** —— 覆盖安装不会替换 system_server 已加载的 hook 代码。
     */
    private fun probeHookActive() {
        Thread {
            hookActive = HookBridge.isActive()
            SHLog.i(
                TAG,
                "lsp hook active=$hookActive" +
                    " -> 本次按图层缩放=${"%.3f".format(HookBridge.miuiLayerScale())}下发" +
                    " (fix72: 缩放中和 hook 已移除，恒按配置比例÷补偿)" +
                    " birth=[${HookBridge.birthState()}]" +
                    if (!hookActive)
                        "（hook 未激活：小窗仍可用，但出生几何要等 resize；" +
                            "在 LSPosed 中勾选本模块并把作用域设为「系统框架」后重启手机）"
                    else ""
            )
            runCatching { forceResizableActivities() }
        }.apply { name = "hook-probe" }.start()
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 窗口还没建：先把"视图已挂"标记清掉。`isWindowAlive()` 据此判本页要不要退后台，
        // 而本服务会被反复创建（切角/改尺寸都重启），不清的话上一轮留下的 true
        // 会让 MainActivity 在当前这轮窗口**还没建起来**时就让位。
        runCatching { StateManager.markViewsDetached() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chId = "corner_window"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(
                chId, "记忆小窗", android.app.NotificationManager.IMPORTANCE_MIN))
        }
        // ★ fix58：只有面板那一下（没有小窗会话）不该顶着"运行中，点悬浮球退出"这句话 ——
        //   那条通知说的是一件当时并不存在的事。文案按本次 intent 分叉。
        val panelOnly = intent != null && intent.hasExtra(EXTRA_PANEL) && !controlsReady
        val notif = android.app.Notification.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("记忆小窗")
            .setContentText(
                if (panelOnly) "正在打开列表" else "记忆小窗运行中，点悬浮球退出"
            )
        try {
            startForeground(1001, notif.build())
        } catch (e: Exception) {
            SHLog.e(TAG, "startForeground failed", e)
        }

        // ★ fix111：侧边小窗默认几何（writeSideboxRect）已删 —— 设置尺寸退出所有
        //   开窗几何，侧边栏小窗交还系统自己管理。

        // ★ fix58：**必须放在所有提前 return 的分支之前**。
        //   下面「只开面板」「接管小窗」两条路都可能在 !controlsReady 时进来，
        //   而它们都要用 wm 去 addView —— 以前 wm 只在最后那段正常启动流程里赋值，
        //   提前 return 的分支只能靠"服务实例早就建过窗口"侥幸拿到非空值。
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // ★ fix54：**热重应用**（切角球）。服务已经在跑时不必重启 ——
        //   只重摆本进程的 overlay，再把当前 freeform task 平移过去，目标应用完全不动。
        //   放在这里（而不是流程末尾）是为了**不碰**下面那套"复用旧 pendingPkg + maybeLaunch"
        //   的逻辑：走到这里说明这是一次设置变更，不是一次新的启动。
        if (intent?.hasExtra(EXTRA_HOT_REAPPLY) == true && controlsReady) {
            runCatching { reapplyHot() }.onFailure { SHLog.w(TAG, "hot reapply failed", it) }
            return START_NOT_STICKY
        }

        // ★ 1.0.95：悬浮球扇形菜单的两条命令 —— 控制球删了之后，「关闭小窗」与
        //   「切换左右角落」由这里承接（原来分别是 ✕ 球的 stopSelf 与 ⇄ 球的
        //   [switchCornerFromWindow]）。同样提前 return：命令，不是新启动。
        if (intent?.hasExtra(EXTRA_CLOSE_SESSION) == true) {
            SHLog.i(TAG, "menu -> 关闭小窗（stopSelf）")
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        if (intent?.hasExtra(EXTRA_SWITCH_CORNER) == true) {
            runCatching { switchCornerFromWindow() }
                .onFailure { SHLog.w(TAG, "menu switch corner failed", it) }
            return START_NOT_STICKY
        }

        // ★ fix58：**接管**系统开的小窗（不新开窗，只压几何）。同样提前 return。
        if (intent != null && intent.hasExtra(EXTRA_ADOPT)) {
            runCatching { adoptExisting(intent.getStringExtra(EXTRA_ADOPT)) }
                .onFailure { SHLog.w(TAG, "adopt failed", it) }
            return START_NOT_STICKY
        }

        // ★ fix55/58：悬浮球菜单 -> 打开「最近 / 应用」面板。
        //   和热重应用同一个位置、同样**提前 return**：这是一条命令，不是一次新的启动，
        //   绝不能让下面那套「复用旧 pendingPkg + maybeLaunch」被顺带跑一遍。
        //   ★ fix58：**没有会话时也开**（[startPanelOnlySession]）—— 以前这里直接忽略，
        //     菜单上那两项就成了"点了没反应"。面板本来就不依赖小窗存在：
        //     「应用」列表的唯一用途就是挑一个 app 开进小窗。
        if (intent != null && intent.hasExtra(EXTRA_PANEL)) {
            val mode = if (intent.getStringExtra(EXTRA_PANEL) == PANEL_APPS)
                PanelMode.APPS else PanelMode.RECENTS
            val label = if (mode == PanelMode.APPS) "应用" else "最近"
            // ★ 1.0.101：面板跟随悬浮球 —— 球在哪侧，面板横向就贴哪侧
            panelAnchorLeft = if (intent.hasExtra(EXTRA_PANEL_LEFT))
                intent.getBooleanExtra(EXTRA_PANEL_LEFT, false) else null
            if (controlsReady) {
                SHLog.i(TAG, "悬浮球菜单 -> 打开面板「$label」")
            } else {
                SHLog.i(TAG, "悬浮球菜单 -> 打开面板「$label」（本服务没有会话：只开面板，不拉小窗）")
                runCatching { startPanelOnlySession() }
                    .onFailure { SHLog.w(TAG, "panel-only session failed", it) }
            }
            runCatching { openPanel(mode) }
                .onFailure { SHLog.w(TAG, "panel from float ball failed", it) }
            return START_NOT_STICKY
        }

        // 显式覆盖语义：没带 extra 时保留旧值（切角/改尺寸重启要复用同一目标），
        // 带了空串表示"清空"—— 点「当前前台应用」必须清掉上次的包名。
        if (intent != null && intent.hasExtra(EXTRA_PACKAGE)) {
            pendingPkg = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotEmpty() }
        }
        if (intent != null && intent.hasExtra(EXTRA_ACTIVITY)) {
            pendingAct = intent.getStringExtra(EXTRA_ACTIVITY)?.takeIf { it.isNotEmpty() }
        }
        // 允许调用方显式指定角落（切角重启时 StateManager 可能尚未刷新）
        intent?.getStringExtra(EXTRA_CORNER)?.let { name ->
            runCatching { StateManager.updateCorner(Corner.valueOf(name)) }
        }

        probeHookActive()
        startEnabledHeartbeat()
        if (!controlsReady) setupControls() else maybeLaunch()
        return START_NOT_STICKY
    }

    /**
     * ★ fix58：**只开面板**的会话 —— 没有小窗，也就没有控制球、没有 `am start-activity`。
     *
     * 用途：「应用 / 最近任务」列表本身不依赖小窗存在（「应用」列表就是用来挑一个 app
     * 开进小窗的），菜单既然恒定给这两个入口，就得让它们在没有会话时也能打开。
     *
     * 只做两件**纯本地**的事：探一次 hook 状态、把心跳拉起来（心跳是为"用户接下来从
     * 面板里选了一个 app，会话就地转正"准备的）。★ fix60 起不再预算面板落点 —— 面板是
     * 打开那一刻**按设置现算**的（见 [settingsRect]）。
     * **绝不** [setupControls]（那会拉起小窗），也**不** `markViewsAttached()`
     * —— 于是 `StateManager.isWindowAlive()` 保持 false，主界面不会退到后台。
     */
    private fun startPanelOnlySession() {
        probeHookActive()
        startEnabledHeartbeat()
    }

    /**
     * ★ fix58：**接管**一个已存在的、不是我们开的 freeform 小窗。
     *
     * ## 为什么
     *
     * 用户先用系统的方式（澎湃侧边栏 / 最近任务 / 系统自己）开了一个小窗，再点悬浮球
     * 菜单里的「打开小窗」—— 旧行为是 `am start-activity --windowingMode 5` **再开一个
     * 同包的小窗**。屏幕上于是有两个 freeform task，而澎湃的窗口装饰（顶部拖动条）
     * 只挂在其中一个上；用户看到的是"小窗顶部的拖动条和底部的手势条都点不动"。
     *
     * ## 做什么
     *
     * 只针对**那个已经在屏幕上**的 task：摆好控制球、把它的几何压成我们的设置、
     * 记进 [miuiTasks] 由我们负责（关窗时一并收掉）。**全程没有一条 `am start-activity`**
     * —— 这是它与 [launchApp] 的本质区别。
     *
     * ⚠ [launched] 必须在 [setupControls] **之前**置 true：[setupControls] 末尾调
     *   [maybeLaunch]，而那会用 [pendingPkg] 去 `am start-activity` 拉起一个**新**窗口，
     *   正好把我们想避免的那个 bug 造出来。
     */
    private fun adoptExisting(pkg: String?) {
        if (controlsReady) {
            SHLog.w(TAG, "adopt: 本服务已有会话，忽略这条接管命令")
            return
        }
        if (pkg.isNullOrEmpty()) {
            SHLog.w(TAG, "adopt: 没带包名，忽略")
            return
        }
        SHLog.i(TAG, "adopt: 接管 $pkg 已存在的小窗（不新开窗，只压几何）")
        pendingPkg = pkg
        pendingAct = null
        launched = true                      // ⚠ 见方法头注释
        StateManager.updateLastTarget(pkg, null)
        setupControls()                      // 摆球 + markViewsAttached
        startWindowWatcher()                 // 会话生命周期的收尾（关窗 / 被系统关掉）
        Thread { runCatching { resizeTaskToWindow(pkg) } }
            .apply { name = "adopt-resize" }.start()
    }

    /**
     * 每 2 秒上报一次"小窗还活着"（[StateManager.windowHeartbeat]）。
     *
     * MainActivity 只认这个心跳：服务被系统杀掉时不会走 onDestroy，
     * 单靠 enabled 标记会让本页永远退在后台。心跳超时 6s 即视为已关闭，本页自动恢复。
     */
    private fun startEnabledHeartbeat() {
        if (enabledHeartbeatStarted) return
        enabledHeartbeatStarted = true
        // 服务起来了 = 小窗真的开着：清掉"已关闭"标记，让心跳自愈重新有资格。
        runCatching { StateManager.setWindowOpen() }
        val tick = object : Runnable {
            override fun run() {
                runCatching { StateManager.windowHeartbeat() }
                mainHandler.postDelayed(this, 2_000L)
            }
        }
        mainHandler.post(tick)
    }

    // ------------------------------------------------------------------ 控制球

    /**
     * 只加两个边缘控制球 —— **不建任何窗口层**。
     *
     * 这里**不建任何占位窗口**：自造的 overlay（`TYPE_APPLICATION_OVERLAY`，z=2038）
     * 层级高于系统 Freeform task，加上去会把澎湃小窗整块盖住（用户看到的就是
     * "小窗被遮挡"）；占位层的尺寸又正好等于小窗矩形，压在同一个位置。
     */
    private fun setupControls() {
        // ★ fix58：幂等保护。面板-only 会话（[startPanelOnlySession]）里用户选了一个 app 时
        //   会走到这里把会话"转正"，而那条路也要求本方法可以被安全地当成"开始一个会话"来调。
        if (controlsReady) return
        // ★ 1.0.95：窗口上的 ✕ / ⇄ 两颗控制球已删（用户要求）—— 关小窗 / 切角改由
        //   悬浮球扇形菜单承担（EXTRA_CLOSE_SESSION / EXTRA_SWITCH_CORNER 命令通道）。
        controlsReady = true
        SHLog.i(TAG, "session started（无控制球；关窗/切角走悬浮球菜单）")
        // ★ 视图真的进栈了，这才是"小窗开着"的权威时刻：
        //   悬浮球从此刻起才该亮起来（fix59 起它订阅 windowAliveFlow）。
        runCatching { StateManager.markViewsAttached() }
        maybeLaunch()
    }

    /**
     * 按**设置**算出来的那块区域（视觉矩形 `[l,t,r,b]`）：角落 + 尺寸 + 离底边间距。
     *
     * 也就是「小窗应该待的地方」。列表面板（[openPanel]）用的就是它 ——
     * 面板摆位**只认设置**，不再跟着 task 的实测几何跑。
     *
     * ★ fix60 为什么不再用实测几何：那份几何会被两件事改掉 ——
     *  ① 用户自己把小窗拖到别处；
     *  ② 澎湃在小窗**失去顶层地位**时把它重摆到左上角 `[35,127]`（fix40 实测）。
     * 于是"面板位置"跟着小窗乱跑（最典型的是跑到屏幕左上角），看起来就是
     * "完全没按我设置里的角落 / 离底边间距摆"。
     *
     * ⚠ 纯本地计算，无 root、无 IO（主线程可跑）。
     */
    private fun settingsRect(): IntArray {
        val (ww, wh) = windowSize()
        val (sw, sh) = realScreenSize()
        // ★ fix86：顶部让出状态栏（横屏），否则窗顶贴到 y=0、移动手势条压在状态栏上。
        val topAvoid = topAvoidPx()
        val usableH = (sh - navAvoidPx() - topAvoid).coerceAtLeast(dp(160))
        val isLeft = StateManager.current.corner == Corner.LEFT
        val wx = if (isLeft) 0 else (sw - ww).coerceAtLeast(0)
        val wy = topAvoid + (usableH - wh).coerceAtLeast(0)
        return intArrayOf(wx, wy, wx + ww, wy + wh)
    }

    /**
     * 切换左右小窗（关闭球正上方那个按钮）。
     *
     * ★ fix54：改成**服务内热切换**，不再重启服务。
     *
     * 旧实现（fix53 及以前）走 `SingleHandManager.switchCorner()`：更新设置后
     * `stopService` → 本服务 `onDestroy`（抓记忆、清会话文件、`am stack remove`）→
     * `delay(200)` → `startForegroundService` → 重新 `am start-activity` 目标应用 →
     * 设备侧轮询 taskId → `am task resize`。用户按下这个键看到的是**整个小窗关掉重开**，
     * 而且当时 `onDestroy` 还在主线程 fork su，于是"卡一下"格外明显。
     */
    private fun switchCornerFromWindow() {
        runCatching {
            val old = StateManager.current.corner
            val new = if (old == Corner.LEFT) Corner.RIGHT else Corner.LEFT
            SHLog.i(TAG, "swap corner -> ${if (new == Corner.LEFT) "左下" else "右下"}（服务内热切换）")
            // ★ fix93：切角**不再清记忆**（fix63 旧行为）—— 记忆已是"用户摆放的位置 +
            //   拉伸的大小"，清掉等于全丢（真机日志实证：点切角球 →「已清掉 system 侧记忆」
            //   → 之后全部退回设置几何）。改成水平镜像到新角落，镜像落盘后再热重摆。
            val (sw, sh) = realScreenSize()
            StateManager.updateCorner(new)
            WindowMemory.mirrorAllHorizontally(sw, sh, HookBridge.miuiLayerScale()) {
                runCatching { reapplyHot() }.onFailure { SHLog.w(TAG, "hot reapply after mirror failed", it) }
            }
        }.onFailure { SHLog.w(TAG, "switchCornerFromWindow failed", it) }
    }

    /**
     * ★ fix63：把 **system_server 那份**位置记忆（[HookContract.WINDOW_MEMORY_PATH]）也清掉。
     *
     * 位置记忆有两份：App 侧 SP（我们**启动**小窗时读）与 `/data/system/memoryfreeform_window_memory`
     * （出生钩子在**恢复 / 复用**链路里读）。改角落 / 尺寸 / 缩放时只清 SP 的话，钩子还会拿
     * 那份旧几何把窗口顶回去 —— 用户看到的是"小窗大小 / 位置会突然变化"（真机实测：把
     * mark.via 记成 400x400，`am start-activity --windowingMode 5` 出来的窗口就是 400x400）。
     *
     * **后台线程**调用 —— fork su，主线程碰不得（fix54 铁律）。
     */
    private fun wipeSystemWindowMemoryAsync() {
        Thread {
            runCatching {
                // ★ fix79：记忆统一一份，只有主文件（SB 文件已废弃，由记录钩子启动时迁移删除）。
                val f = HookContract.WINDOW_MEMORY_PATH
                RootManager.get().executeFast("rm -f $f $f.tmp", 3000)
                SHLog.i(TAG, "windowMemory: 已清掉 system 侧记忆 $f")
            }.onFailure { SHLog.w(TAG, "清 system 位置记忆失败: ${it.message}") }
        }.apply { name = "wipe-wmem"; isDaemon = true }.start()
    }

    /**
     * ★ fix54：按当前设置**热重摆** overlay —— 不重启服务、不重启目标应用。
     *
     * 只做一件事：后台对当前 freeform task 重下一次 `am task resize` —— 窗口原地平移到新角落，
     *   澎湃自己播位移动画。overlay 布局跟着 resize 自行更新，这里不再摆 overlay。
     *
     * ⚠ 主线程调用：`addView` / `updateViewLayout` 都要求视图创建线程。
     * ⚠ 本方法自身不跑 root 命令（② 已交给后台线程）。
     */
    private fun reapplyHot() {
        val left = StateManager.current.corner == Corner.LEFT
        SHLog.i(TAG, "hot reapply: 角落=${if (left) "左下" else "右下"}")
        val pkg = pendingPkg
        if (pkg == null) {
            SHLog.i(TAG, "hot reapply: 尚无目标包，几何将在下次启动时按新设置计算")
            return
        }
        Thread {
            runCatching { resizeTaskToWindow(pkg) }
                .onFailure { SHLog.w(TAG, "hot reapply resize failed", it) }
        }.apply { name = "hot-resize" }.start()
    }


    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()


    // ------------------------------------------------------------------ 列表面板

    /**
     * 打开「最近 / 应用」列表面板；再打开同一个收起，点行 / ✕ / **系统返回** / 点面板外都收起。
     *
     * 入口（fix56 起唯一）：悬浮球菜单 —— 那是个**另一个 Service**，靠 [EXTRA_PANEL] 投命令过来。
     *
     * ★ fix55：面板窗口**不再 NOT_FOCUSABLE**。系统的手势返回与实体返回键都只发给
     *   **当前聚焦窗口**，NOT_FOCUSABLE 的浮层永远收不到 —— 用户想关列表，返回却直接落到
     *   小窗里的应用上（把应用页面返回掉一层）。代价是面板开着这段时间小窗里的应用会短暂
     *   失去**窗口焦点**，所以面板自带 ✕ / 选行 / 点面板外三个立刻收起的出口。
     * ★ fix56：**这个"可聚焦"正是「应用」搜索能用输入法的前提** —— 输入法只跟着聚焦窗口走。
     * ★ fix60：位置**只按设置算**（[settingsRect]：角落 + 尺寸 + **离底边间距**），
     *   不再跟随 watcher 读回的小窗实测几何（那份几何会被拖动 / 被澎湃重摆到左上角）。
     *   宽高就是"小窗该占的那块"，底边 = 屏高 - 离底边间距。
     *
     * 列表是 `ListView + [PanelAdapter]` 而不是 `LinearLayout + ScrollView`：
     * 搜索每敲一个字都要换一批数据，前者只有可见行会被建/复用，过滤 = 换个 List 再
     * `notifyDataSetChanged`；后者得把上百行 View 整棵拆了重建，打字必卡。
     */
    // `SOFT_INPUT_ADJUST_RESIZE` 在 API 30+ 被标废弃（官方要求自己处理 insets），
    // 但浮层窗口没有 Activity 那一套 insets 管线，这里仍用它让系统在键盘弹出时收短窗口 ——
    // 不收短也只是列表下半截被键盘盖住，不影响搜到的那几行。
    @Suppress("DEPRECATION")
    private fun openPanel(mode: PanelMode) {
        // 同一个面板再点一次 = 收起它（用户主动关，所以走 dismissPanel）
        if (panelView != null && panelMode == mode) { dismissPanel(); return }
        hidePanel()
        // ★ fix60：面板位置**只按设置算** —— 角落决定贴左还是贴右，尺寸决定宽高，
        //   「离底边间距」决定底边离屏幕底多远。和小窗共用同一份计算（[settingsRect]），
        //   所以面板永远盖在"小窗那块地方"。
        //
        //   以前是按 watcher 读回的**实测** task 几何摆的，于是位置会被两件事带走：
        //   ① 用户把小窗拖到别处；② 澎湃在小窗失去顶层地位时把它重摆到左上角
        //   `[35,127]`（fix40 实测）—— 面板跑到屏幕左上角，用户看到的就是
        //   "完全没按设置里的角落 / 离底边间距摆"。
        val rect = settingsRect()
        val (sw, sh) = realScreenSize()
        // 底边：屏幕底往上让出「离底边间距」——这正是设置项该起的作用
        val bottom = (sh - navAvoidPx()).coerceAtLeast(dp(200))
        val w = (rect[2] - rect[0]).coerceIn(dp(240).coerceAtMost(sw), sw)
        // ★ fix132：单手操作 —— 列表顶太高拇指够不着（1080×2400 上旧逻辑列表顶到屏幕上
        //   半部）。两种面板统一按屏幕可用高度的 50% 封顶，列表本身可滚动，矮一点反而更
        //   好点。下限 200dp 兜底，避免设置窗口尺寸过小把面板压没。
        val maxH = (sh * 0.5f).toInt()
        val hRaw = (rect[3] - rect[1]).coerceAtMost(bottom)
        val h = hRaw.coerceAtMost(maxH).coerceAtLeast(dp(200).coerceAtMost(bottom))
        val top = (bottom - h).coerceAtLeast(0)

        val title = if (mode == PanelMode.RECENTS) "最近任务" else "应用"
        val card = PanelRoot(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF212141A.toInt())
                setStroke(dp(1).coerceAtLeast(1), 0x33FFFFFF)
            }
        }
        // ① 标题行 + ✕（自带关闭入口：面板能收系统返回了，但点 ✕ 永远最快）
        card.addView(
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(8), dp(8))
                addView(android.widget.TextView(this@CornerWindowService).apply {
                    text = title
                    setTextColor(0xFFEDEDED.toInt())
                    textSize = 16f
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(android.widget.TextView(this@CornerWindowService).apply {
                    text = "✕"
                    setTextColor(0xFF9AA0A6.toInt())
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setPadding(dp(10), 0, dp(10), 0)
                    isClickable = true
                    setOnClickListener { dismissPanel() }
                })
            }
        )

        val adapter = PanelAdapter { item -> pickPanelItem(item, title) }

        // ② 「应用」多一行搜索框（fix56）。
        //    ⚠ 输入法只在**可聚焦窗口**里才会弹（见方法头注释），所以这里的 EditText
        //      不需要任何特殊 flag；点一下会主动 renew 一次焦点 + 抬起输入法，见 [requestIme]。
        // ★ fix60：输入法右下角做成「搜索」键，**回车 = 直接打开当前匹配到的第一条**
        //   （软键盘搜索键与物理回车都走 [android.widget.TextView.OnEditorActionListener]）。
        //   搜到一半还要伸手去点那一行、而那一行往往就在指尖底下 —— 回车直达更顺手。
        var searchField: android.widget.EditText? = null
        if (mode == PanelMode.APPS) {
            val counter = android.widget.TextView(this).apply {
                setTextColor(0xFF9AA0A6.toInt())
                textSize = 12f
            }
            val search = android.widget.EditText(this).apply {
                hint = "搜索应用"
                setHintTextColor(0xFF7C828A.toInt())
                setTextColor(0xFFEDEDED.toInt())
                textSize = 15f
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                // 让输入法把右下角那颗键画成「搜索」（fix60）
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x1FFFFFFF)
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { requestIme(this) }
                setOnEditorActionListener { _, actionId, event ->
                    // 两条路都算「回车」：软键盘的搜索/完成键（actionId），
                    // 以及物理键盘/第三方输入法直接发来的 KEYCODE_ENTER。
                    val enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                        (event != null &&
                            event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                            event.action == android.view.KeyEvent.ACTION_DOWN)
                    if (!enter) return@setOnEditorActionListener false
                    val first = adapter.itemAt(0)
                    if (first == null) {
                        SHLog.i(TAG, "panel[$title]: 回车，但当前没有匹配项（不动作）")
                        return@setOnEditorActionListener true
                    }
                    SHLog.i(TAG, "panel[$title]: 搜索回车 -> 直接打开第一条 ${first.pkg}")
                    // ⚠ 不在输入法回调里同步摘自己的窗口，post 到下一拍再做（同 PanelRoot 的规矩）
                    mainHandler.post { runCatching { pickPanelItem(first, title) } }
                    true
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        adapter.filter(s?.toString().orEmpty())
                    }
                })
            }
            searchField = search
            adapter.onChanged = { shown, total ->
                counter.text = when {
                    total == 0 -> ""
                    shown == total -> "共 $total"
                    else -> "$shown / $total"
                }
            }
            card.addView(
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), dp(6))
                    addView(search, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                    addView(counter, android.widget.LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                }
            )
        }

        // ③ 列表：ListView 自己管滚动与行的复用；空态交给 ListView 的 emptyView 机制
        val listView = android.widget.ListView(this).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(2), 0, dp(8))
            clipToPadding = false
        }
        val emptyView = android.widget.TextView(this).apply {
            text = if (mode == PanelMode.APPS) "没有可显示的条目" else "没有最近任务"
            setTextColor(0xFF9AA0A6.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val listWrap = android.widget.FrameLayout(this).apply {
            addView(listView, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(emptyView, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        // 行点击：走 AdapterView 自己的 OnItemClickListener（行内不放任何 clickable 子 View，
        // 否则触摸会被子 View 吃掉、item click 不触发）
        listView.adapter = adapter
        listView.emptyView = emptyView
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.itemAt(position)?.let { pickPanelItem(it, title) }
        }
        // ★ fix131：「最近任务」行**横向滑动关闭** —— 左右滑过阈值松手 = 关掉该应用的
        //   小窗（有 freeform task 才关，见 [closeRecentTask]）并从列表移除；不够阈值弹回。
        //   只在 RECENTS 模式启用；「应用」面板保持纯点选。
        //   实现：监听 ListView 的整条触摸流（DOWN 返回 false 不抢滚动），横向位移
        //   超过 touchSlop 且明显大于纵向时才判定为滑动并接管该手势。
        if (mode == PanelMode.RECENTS) {
            val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
            val dismissDist = dp(72)
            var dX = 0f; var dY = 0f
            var downPos = -1
            var swiping = false
            var activeRow: View? = null
            listView.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dX = ev.x; dY = ev.y
                        downPos = listView.pointToPosition(ev.x.toInt(), ev.y.toInt())
                        swiping = false
                        activeRow = null
                        false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - dX
                        val dy = ev.y - dY
                        if (!swiping && downPos >= 0 &&
                            kotlin.math.abs(dx) > touchSlop &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2
                        ) {
                            swiping = true
                            activeRow = listView.getChildAt(downPos - listView.firstVisiblePosition)
                        }
                        if (swiping) {
                            activeRow?.translationX = dx
                            true
                        } else false
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val row = activeRow
                        if (swiping && row != null) {
                            val dx = row.translationX
                            row.translationX = 0f
                            val item = adapter.itemAt(downPos)
                            if (kotlin.math.abs(dx) >= dismissDist && item != null) {
                                SHLog.i(TAG, "panel[$title]: 滑动关闭 ${item.pkg}")
                                adapter.removePkg(item.pkg)
                                Thread { runCatching { closeRecentTask(item.pkg) } }
                                    .apply { name = "panel-close" }.start()
                            }
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
        card.addView(listWrap, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // ★ fix55：不带 FLAG_NOT_FOCUSABLE = **可聚焦**，系统返回与输入法都要求它；
            //   NOT_TOUCH_MODAL：面板以外的触摸照常给小窗（不然整屏都被面板吞掉）；
            //   WATCH_OUTSIDE_TOUCH：点面板外会收到 ACTION_OUTSIDE，用来收起面板。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // ★ 1.0.101：横向锚点跟随悬浮球（球在左半屏→面板贴左，右半屏→贴右）；
            //   命令没带侧别时退回设置角落。纵向仍按「离底边间距」那套算。
            val anchorLeft = panelAnchorLeft ?: (StateManager.current.corner == Corner.LEFT)
            x = (if (anchorLeft) dp(8) else sw - w - dp(8)).coerceIn(0, (sw - w).coerceAtLeast(0))
            y = top
            // 键盘弹起时把窗口收短，列表才不会被键盘整块盖住（fix56）
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panelView = card
        panelLp = lp
        panelMode = mode
        panelSearch = searchField
        runCatching { wm?.addView(card, lp) }
            .onFailure { SHLog.e(TAG, "panel addView failed", it) }
        // ★ fix131：「应用」面板一打开就聚焦搜索框、抬起输入法 —— 省掉"点一下搜索框"
        //   那一步（[requestIme] 内部 postDelayed，等窗口完成第一轮布局再要焦点）。
        if (mode == PanelMode.APPS) searchField?.let { requestIme(it) }

        // 列表在后台装载（PackageManager / dumpsys / 图标解码都可能慢），装好再塞给 adapter
        Thread {
            val items = runCatching {
                if (mode == PanelMode.RECENTS) loadRecents() else loadApps()
            }.getOrDefault(emptyList())
            mainHandler.post {
                if (panelView !== card) return@post
                adapter.setItems(items)
                SHLog.i(TAG, "panel[$title]: 装载 ${items.size} 项")
            }
        }.apply { name = "panel-load" }.start()
        SHLog.i(
            TAG,
            "panel[$title]: 打开 ${w}x$h @ [${lp.x},${lp.y}]" +
                "（锚点=${if (panelAnchorLeft ?: (StateManager.current.corner == Corner.LEFT)) "左" else "右"}侧" +
                " 离底边间距=${navAvoidPx()}px -> 底边 $bottom）" +
                if (mode == PanelMode.APPS) "（带搜索框）" else ""
        )
    }

    /**
     * 面板里选中一项：收起面板，把它在小窗里打开。
     *
     * ★ fix58：两种会话状态，处理方式不同 ——
     *  - **已有小窗**：直接把选中项开进小窗（[launchApp]，与旧行为一致）；
     *  - **面板-only 会话**（[startPanelOnlySession]，屏幕上一个小窗都没有）：选中即
     *    "开始一个真正的会话" —— [setupControls] 摆好控制球（其末尾会 [maybeLaunch]，
     *    用刚设好的 [pendingPkg] 启动目标）。否则会开出一个**没有控制球**的小窗，
     *    用户既关不掉它、菜单也不会认它（`isWindowAlive()` 仍是 false）。
     */
    private fun pickPanelItem(item: PanelItem, title: String) {
        hidePanel()
        pendingPkg = item.pkg
        pendingAct = null
        if (!controlsReady) {
            SHLog.i(TAG, "panel[$title]: 选中 ${item.pkg} -> 没有会话，就地开始一个会话")
            StateManager.updateLastTarget(item.pkg, null)
            runCatching { setupControls() }
                .onFailure { SHLog.w(TAG, "panel pick -> start session failed", it) }
            return
        }
        SHLog.i(TAG, "panel[$title]: 选中 ${item.pkg} -> 在小窗里打开")
        launchApp(item.pkg, null)
    }

    /**
     * 主动给浮层里的输入框要焦点并抬起输入法（fix56）。
     *
     * 面板窗口是可聚焦的，但它**不是 Activity 的窗口** —— 系统不会像对待 Activity 那样
     * 自动把 EditText 接上输入法，所以点击后 post 一拍，再显式
     * `requestFocus()` + `showSoftInput`。用 `SHOW_IMPLICIT`（不要 SHOW_FORCED，
     * 那个会让键盘在窗口关掉后还赖着不走）。
     */
    private fun requestIme(view: View) {
        mainHandler.postDelayed({
            runCatching {
                view.requestFocus()
                val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }.onFailure { SHLog.w(TAG, "panel: 抬起输入法失败", it) }
        }, 60)
    }

    /**
     * 收起列表面板。**幂等**（不在时什么都不做），所以「返回」「✕」「选行」「点面板外」
     * 可以各叫各的，不会打架。
     *
     * ⚠ 面板窗口是**可聚焦**的（fix55），摘掉它 = 系统把焦点还给小窗里的应用 ——
     *   这也是"点面板外 / 按返回"要尽早走这里的原因。
     *
     * ★ fix58：这里**只负责摘窗口**，不判断"服务是不是该结束了" —— 那个判断在
     *   [dismissPanel] 里（见它的注释：openPanel 内部换面板时也会调本方法，
     *   那时绝不能让服务停掉）。
     */
    private fun hidePanel() {
        panelMode = null
        val v = panelView ?: return
        panelView = null
        panelLp = null
        // 输入法要跟搜索框一起收掉，否则键盘会赖在一个已经被摘掉的 View 上（fix56）
        panelSearch = null
        runCatching { wm?.removeView(v) }
            .onFailure { SHLog.w(TAG, "panel removeView failed", it) }
        SHLog.i(TAG, "panel: 已收起（窗口焦点交还小窗）")
    }

    /**
     * ★ fix58：**用户主动收起面板**的统一出口（✕ / 系统返回 / 点面板外）。
     *
     * 与 [hidePanel] 的区别只有一件事：面板-only 会话（[startPanelOnlySession]，
     * 屏幕上压根没有小窗）此时已经没有任何事可做 —— 停掉服务，别让它顶着一条
     * 前台通知赖在那儿。有会话时什么都不做（小窗还开着，服务本来就要活着）。\
     *
     * ⚠ 选行（[pickPanelItem]）**不**走这里：那一下多半会开出一个新会话。
     */
    private fun dismissPanel() {
        hidePanel()
        if (controlsReady) return
        SHLog.i(TAG, "panel: 面板-only 会话（没有小窗）无事可做 -> 停服务")
        mainHandler.post { runCatching { stopSelf() } }
    }

    /**
     * ★ fix55：面板根布局 —— 除了当容器，唯一职责就是**替小窗接住系统返回**。
     *
     * 面板窗口现在可聚焦（见 [openPanel] 的 flags），系统的手势返回与实体返回键都会
     * 落到这个窗口上；`ViewRootImpl` 是无条件把按键事件交给**根视图**的，所以在这里
     * 覆写 [dispatchKeyEvent] 就能抢在一切之前处理掉，不会漏到小窗里的应用。
     *
     * 行为就是用户要的顺序：**面板开着 -> 只收面板，不碰小窗**；
     * 面板关着时这个视图根本不存在，返回照常落到小窗里的应用。
     * （键盘弹着时那一下返回由**输入法自己**吃掉，第二下才走到这里 —— 系统行为，不用管。）
     *
     * 收起动作一律 `post` 出去，不在事件分发途中摘自己的窗口（避免在 dispatch 里
     * 把 ViewRootImpl 拆掉）。
     */
    private inner class PanelRoot(context: android.content.Context) :
        android.widget.LinearLayout(context) {

        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    SHLog.i(TAG, "panel: 收到系统返回（侧边手势 / 返回键）-> 先收起面板，不碰小窗")
                    mainHandler.post { runCatching { dismissPanel() } }
                }
                // down / up 都吞掉：把 up 漏出去的话，它会和下一次返回凑成一对
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        /** 点面板外（地板 = 小窗里的应用，或屏幕别处）→ 收起，把焦点立刻还回去。 */
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                // ⚠ 输入法是一个**独立窗口**：点键盘同样会给我们发 ACTION_OUTSIDE。
                //   不判这一下就是"打一个字面板就没了"（fix56 加搜索框时才踩到）。
                if (isImeVisible()) return true
                SHLog.i(TAG, "panel: 点击面板外部 -> 收起面板")
                mainHandler.post { runCatching { dismissPanel() } }
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        /** 输入法是否正弹着。API 30 才有 `WindowInsets.Type`，低版本一律当"没弹"。 */
        private fun isImeVisible(): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 30) return false
            return runCatching {
                rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) ?: false
            }.getOrDefault(false)
        }
    }

    /**
     * 列表面板的一项。**图标在这里就已经解码成 Bitmap**，见 [loadApps]。
     */
    private class PanelItem(
        val pkg: String,
        val label: String,
        val icon: android.graphics.Bitmap?
    )

    /**
     * 面板行的**骨架**：图标 + 名称（内容由 [bindPanelRow] 填）。
     *
     * 拆成"骨架 / 绑定"两步是因为 [PanelAdapter] 要复用 convertView：
     * 搜索时每敲一个字都换一批数据，ListView 只重建新露出来的那些行。
     *
     * ⚠ **主线程只做 addView / setImageBitmap，绝不调用 PackageManager**（fix54）：
     *   图标以前是在这里现取的（`packageManager.getApplicationIcon(pkg)`），
     *   而「应用」面板一次要建 100+ 行 —— 等于在主线程做 100+ 次图标解码，
     *   用户按下「应用」会卡好几秒。现在图标全在 [loadApps] 的后台线程里解好。
     * ⚠ 行内**不放 clickable 子 View** —— 否则触摸被子 View 吃掉，
     *   ListView 的 OnItemClickListener 不会触发。
     */
    private fun panelRowShell(): android.widget.LinearLayout =
        android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            addView(
                android.widget.ImageView(this@CornerWindowService),
                android.widget.LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) }
            )
            addView(
                android.widget.TextView(this@CornerWindowService).apply {
                    setTextColor(0xFFEDEDED.toInt())
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

    /** 把一项的内容填进 [panelRowShell] 造出来的行里。 */
    private fun bindPanelRow(row: android.widget.LinearLayout, item: PanelItem) {
        val icon = row.getChildAt(0) as android.widget.ImageView
        val label = row.getChildAt(1) as android.widget.TextView
        if (item.icon != null) icon.setImageBitmap(item.icon) else icon.setImageDrawable(null)
        label.text = item.label
        // ★ fix131：滑动关闭会把行横移，行是复用的 —— 绑定前必须复位，否则别的行带着偏移出场
        row.translationX = 0f
    }

    /**
     * ★ fix131：最近任务面板横滑某行 -> 关掉该应用的 freeform 小窗。
     * 现查 `am stack list` 拿该包的 taskId（不依赖会话的 miuiTasks —— 滑动关闭
     * 在面板里随时可用，包括没有会话时）；没有 task 就什么都不关（只移行）。
     * 后台线程调用。
     */
    private fun closeRecentTask(pkg: String) {
        val rm = RootManager.get()
        val ids = runCatching {
            Regex("taskId=(\\d+): ${Regex.escape(pkg)}/").findAll(
                rm.executeFast("am stack list", 3000).output
            ).mapNotNull { m -> m.groupValues[1].toIntOrNull() }.toList()
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) {
            SHLog.i(TAG, "panel: 滑动关闭 $pkg —— 没有 freeform task，仅从列表移除")
            return
        }
        for (tid in ids) {
            val r = runCatching { rm.execute("am stack remove $tid") }.getOrNull()
            val ok = r != null && r.success && !r.output.contains("Error", ignoreCase = true)
            SHLog.i(TAG, "panel: 滑动关闭 $pkg task=$tid -> ${if (ok) "已关" else "失败 out=${r?.output?.trim()}"}")
        }
    }

    /**
     * ★ fix56：「最近 / 应用」列表的数据源，带**本地过滤**（「应用」面板的搜索框用）。
     *
     * 为什么值得单独一个 adapter：搜索每敲一个字都要换一批数据。
     *  ① 过滤只动 [shown] 这个 List（150 个应用线性扫一遍 = 微秒级），视图一个不碰；
     *  ② 交给 ListView 去 `notifyDataSetChanged`，它只会重建**当前可见的那十来行**；
     *  ③ 顺带解决了旧实现的两个毛病：开「应用」面板时一次性 100+ 行全建（卡），
     *     以及打字时把上百行拆了重建（更卡）。
     */
    private inner class PanelAdapter(
        private val onPick: (PanelItem) -> Unit
    ) : android.widget.BaseAdapter() {

        /** 全量（搜索结果从这里筛）。 */
        private var all: List<PanelItem> = emptyList()
        /** 当前显示。 */
        private var shown: List<PanelItem> = emptyList()
        /** 当前搜索词（装载是异步的，装完得按它再筛一次）。 */
        private var query: String = ""

        /** 列表变化时回调 `(显示数, 总数)`，给右上角那个计数用。 */
        var onChanged: ((Int, Int) -> Unit)? = null

        fun setItems(items: List<PanelItem>) {
            all = items
            applyQuery()
        }

        fun filter(q: String) {
            query = q
            applyQuery()
        }

        private fun applyQuery() {
            val q = query.trim()
            shown = if (q.isEmpty()) {
                all
            } else {
                // 名称与包名都参与匹配：中文名搜"微信"，英文名/包名搜"weixin"都行
                all.filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
            }
            notifyDataSetChanged()
            onChanged?.invoke(shown.size, all.size)
        }

        fun itemAt(position: Int): PanelItem? = shown.getOrNull(position)

        /** ★ fix131：滑动关闭 -> 把该应用从列表里移除（最近任务面板用）。 */
        fun removePkg(pkg: String) {
            all = all.filterNot { it.pkg == pkg }
            shown = shown.filterNot { it.pkg == pkg }
            notifyDataSetChanged()
            onChanged?.invoke(shown.size, all.size)
        }

        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any? = shown.getOrNull(position)
        override fun getItemId(position: Int): Long = shown.getOrNull(position)?.pkg?.hashCode()?.toLong() ?: -1L

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): View {
            val row = (convertView as? android.widget.LinearLayout) ?: panelRowShell()
            shown.getOrNull(position)?.let { bindPanelRow(row, it) }
            return row
        }
    }

    /**
     * 「应用」：所有可启动应用（排除自己与桌面）。
     *
     * ⚠ **只在后台线程调用**：图标解码（[iconBitmap]）与 `getApplicationLabel` 都要碰
     *   PackageManager，100+ 个应用在主线程做就是几秒级的卡顿。
     */
    private fun loadApps(): List<PanelItem> = runCatching {
        val self = packageName
        val home = homePkg
        val size = dp(30)
        packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            .asSequence()
            .map { it.activityInfo.packageName }
            .filter { it != self && it != home }
            .distinct()
            .map { PanelItem(it, appLabel(it), appIcon(it, size)) }
            .sortedBy { it.label }
            .toList()
    }.getOrDefault(emptyList())

    /** 「最近」：`dumpsys activity recents` 里的任务顺序（最近的在前面）。 */
    private fun loadRecents(): List<PanelItem> {
        val out = runCatching { RootManager.get().execute("dumpsys activity recents").output }
            .getOrDefault("")
        val self = packageName
        val home = homePkg
        val size = dp(30)
        return Regex("cmp=([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/")
            .findAll(out)
            .map { it.groupValues[1] }
            .filter { it != self && it != home }
            .distinct()
            .take(30)
            .map { PanelItem(it, appLabel(it), appIcon(it, size)) }
            .toList()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * 把应用图标**在后台线程**画成 Bitmap。
     *
     * 为什么要转 Bitmap 而不是直接把 Drawable 传过去：`getApplicationIcon` 返回的
     * 可能是 `AdaptiveIconDrawable` 这类**懒加载**的 Drawable，实际 inflate 发生在
     * 第一次 draw（也就是主线程）；转成 Bitmap 等于把解码成本全留在后台，
     * 主线程拿到的是纯粹的像素缓冲。
     *
     * 纯 framework API（Canvas + Bitmap），不依赖 androidx.core 的 `toBitmap`。
     */
    private fun appIcon(pkg: String, size: Int): android.graphics.Bitmap? = runCatching {
        val ic = packageManager.getApplicationIcon(pkg)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        ic.setBounds(0, 0, size, size)
        ic.draw(canvas)
        bmp
    }.getOrNull()


    // ------------------------------------------------------------------ 尺寸

    /**
     * 最终小窗**视觉**尺寸。
     *
     * 计算全部收敛到 [WindowSizing]，设置页预览用**同一个函数**，
     * 避免 UI 与实际下发各算一套（fix40 之前滑块显示 100% 但实际按 /1.2 缩放，
     * 用户看到的"尺寸不对"就是这么来的）。
     */
    private fun windowSize(): Pair<Int, Int> {
        val (screenW, screenH) = realScreenSize()
        val st = StateManager.current
        val r = WindowSizing.resolve(
            displayW = st.displayWidth,
            displayH = st.displayHeight,
            scaleRatio = st.scaleRatio,
            screenW = screenW,
            screenH = screenH,
            navInset = navAvoidPx(),
            // ★ fix86：横屏扣掉顶部状态栏，避免高度收边成满屏后窗顶贴到 y=0。
            topInset = WindowSizing.topAvoidPx(this)
        )
        return r.width to r.height
    }

    /**
     * ★ fix86：当前方向下**顶部要让出的高度**（横屏 = 状态栏高，竖屏 = 0）。
     * 走 [WindowSizing.topAvoidPx]，与服务里其它几何计算同源。
     */
    private fun topAvoidPx(): Int = WindowSizing.topAvoidPx(this)

    /**
     * 底部**需要避让**的高度（px）。
     *
     * ★ fix57：改成直接读用户填的「离底边间距」（[StateManager.current.bottomGapPx]）。
     *
     * 以前这里是 `if (手势导航) 0 else 系统导航栏高度` —— 两档硬编码。问题在于
     * "让多少"本来是个连续值：各家 ROM 的导航栏高度不同，三键导航用户也可能
     * 只想留一点缝，而枚举把选择压成了 0 / 一个固定数，想微调都做不到。
     *
     * 语义没变：0 = 贴到屏幕最底（手势导航要的就是这个，底部那条只是**透明**手势条，
     * 让出空隙反而显得"没贴住"）；填成实体导航栏高度 = 小窗最下面一排按钮不被压住。
     */
    private fun navAvoidPx(): Int = StateManager.current.bottomGapPx.coerceAtLeast(0)

    /**
     * 屏幕**真实**分辨率（含状态栏/导航栏区域）。
     *
     * 不能用 `resources.displayMetrics` —— 本机实测它给 1080x**2292**，比真实
     * 1080x2400 少一截，拿它算小窗位置会贴不到底。
     */
    private fun realScreenSize(): Pair<Int, Int> = WindowSizing.realScreenSize(this)

    // ------------------------------------------------------------------ 启动

    /** 控制球挂好之后启动目标。 */
    private fun maybeLaunch() {
        if (launched) return
        launched = true
        val pkg = pendingPkg
        if (pkg != null) launchApp(pkg, pendingAct) else launchForegroundApp()
    }

    /**
     * 没有明确目标（用户点的是「当前前台应用」格子）：把**当前前台应用**开进系统小窗。
     *
     * 为什么要有两条探测：用户点那个格子时，本应用（MainActivity）通常就在前台，
     * 只按 `topResumedActivity` 探会探到自己 → 被排除 → 什么都开不出来。
     * 所以第一层探不到可用的目标时，退回最近任务列表里第一个非排除包
     * —— 语义上就是"你刚才在用的那个应用"。
     */
    private fun launchForegroundApp() {
        Thread {
            try {
                val fg = detectForegroundPkg()
                if (fg != null) {
                    SHLog.i(TAG, "foreground app: $fg -> launch")
                    launchApp(fg, null)
                } else {
                    SHLog.w(TAG, "没有可用的前台应用目标，本次会话不启动任何小窗")
                }
            } catch (t: Throwable) {
                SHLog.e(TAG, "launchForegroundApp failed", t)
            }
        }.apply { name = "fg-probe" }.start()
    }

    /** 见 [launchForegroundApp]：先看 resumed 栈，再看最近任务。 */
    private fun detectForegroundPkg(): String? {
        val rm = RootManager.get()
        val excluded = excludedPkgs()
        runCatching {
            val out = rm.execute("dumpsys activity activities").output
            val re = Regex(
                "(?:topResumedActivity|mResumedActivity|ResumedActivity)[^\\n]*?\\bu0\\s+([A-Za-z0-9_.]+)/"
            )
            for (m in re.findAll(out)) {
                val p = m.groupValues[1]
                if (p !in excluded) return p
            }
        }.onFailure { SHLog.w(TAG, "dumpsys activities failed", it) }
        runCatching {
            val out = rm.execute("dumpsys activity recents").output
            val re = Regex("cmp=([A-Za-z0-9_.]+)/")
            for (m in re.findAll(out)) {
                val p = m.groupValues[1]
                if (p !in excluded) return p
            }
        }.onFailure { SHLog.w(TAG, "dumpsys recents failed", it) }
        return null
    }

    /**
     * 用小窗打开 [pkg]。
     *
     * ★ fix112：**纯系统调用** —— 只有一条 `am start-activity --windowingMode 5`，
     * 不写 birth_target、不写 SESSION、不 resize、不复核：app 不再设置任何窗口大小。
     * 几何完全交给系统（有记忆时出生钩子自己会摆；没记忆时澎湃按默认摆，
     * 出生/关闭时钩子把几何登记成记忆，下次就命中）。
     */
    private fun launchApp(pkg: String, activity: String?) {
        Thread {
            try {
                val rm = RootManager.get()

                val act = activity ?: ""
                val component = if (act.isNotEmpty()) {
                    "$pkg/$act"
                } else if (!cacheInvalidated && launcherCache.containsKey(pkg)) {
                    launcherCache[pkg]!!
                } else {
                    val resolved = rm.execute(
                        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg"
                    ).output.lines().lastOrNull { it.contains("/") }?.trim()
                        ?: throw IllegalStateException("无法解析 $pkg 的启动 Activity")
                    launcherCache[pkg] = resolved
                    resolved
                }

                // ★ fix124：先写「自启动标记」（与 startCmd 同一条 root shell 串行执行，顺序有保证）。
                //   RecordHook 据此把自启动的出生帧排除在「系统默认几何」学习之外；
                //   出生钩子据此决定是否用学得的默认几何兜底（无记忆的自启动才用）。
                //   ⚠ 必须走 tee：execAsync 会给整行追加 `>/dev/null`，命令内再用 `> 文件`
                //   重定向会被顶掉（最后一个重定向生效，文件被截空）——真机实证。
                rm.execAsync(
                    "echo pkg=$pkg ts=${System.currentTimeMillis() / 1000} | tee ${HookContract.SELF_LAUNCH_PATH}"
                )
                // ★ fix112：直接调系统能力开小窗，几何零参与 —— 旧的「记忆命中 →
                //   内联脚本设出生几何 + resize 复核」分支整体删除。
                val startCmd = buildPlainFreeformStart(component, pkg)
                SHLog.i(TAG, "start: $startCmd")
                rm.execAsync(startCmd)
                startWindowWatcher()
            } catch (e: Exception) {
                SHLog.e(TAG, "launchApp failed", e)
            }
        }.apply { name = "launch" }.start()
    }

    /**
     * 本次要下发给 `am task resize` 的 task 矩形 `[left,top][right,bottom]`。
     *
     * ## 两个来源，语义不同（★ fix52 的关键）
     *
     * **① 记忆命中 → 原样下发**。记忆里存的就是**下发值**
     * （[WindowMemory] 与 `/data/system/memoryfreeform_window_memory` 现在同域，见
     * [WindowMemory] 的类注释），所以这里**不做任何换算**，只按屏幕收边。
     * fix48~fix51 期间这里存的是"视觉意图"、要用**猜出来的**倍率反算，
     * 猜错一次就缩一圈 —— 用户清空记忆后看到的"小窗一下子大了很多"就是那个乘法螺旋。
     *
     * **② 没有记忆 → 按设置算意图，再反推下发值**。设置里填的是**要看到的**尺寸，
     * 而澎湃给 freeform 强加 0.70 图层缩放（锚点左上），所以要把宽高各除以
     * [HookBridge.miuiLayerScale]；缩放已被 hook 中和时该值为 1.0，等于不补偿。
     */
    /**
     * ★ fix111：`writeSideboxRect` 整体删除 —— 它按设置尺寸算侧边栏小窗默认几何，
     * 而设置尺寸已退出所有开窗几何；且它直接 File 写 /data/system 恒 EACCES
     * （app 无 root 写系统目录），从未真正生效过。侧边栏小窗几何交还系统。
     */

    /**
     * ★ fix69：从 system_server 钩子**实时**写的 [HookContract.WINDOW_MEMORY_PATH] 读回本方向记忆。
     * 与出生钩子同源、200ms 内更新，避免用本进程 SP（WindowWatcher 每 2 秒轮询）的陈旧值去重开小窗。
     * 读不到（su 忙 / 无记忆）返回 null，调用方退回 SP / 设置值。
     */
    private fun readSystemMemoryRect(pkg: String, landscape: Boolean): IntArray? {
        return runCatching {
            val res = RootManager.get().executeFast(
                "cat ${HookContract.WINDOW_MEMORY_PATH} 2>/dev/null", 2000
            )
            if (!res.success || res.output.isBlank()) return@runCatching null
            val key = HookContract.memoryKey(pkg, landscape)
            for (line in res.output.lineSequence()) {
                val i = line.indexOf('=')
                if (i <= 0 || line.substring(0, i).trim() != key) continue
                val v = line.substring(i + 1).split(",").mapNotNull { it.trim().toIntOrNull() }
                if (v.size == 4 && v[2] > v[0] && v[3] > v[1]) {
                    return@runCatching intArrayOf(v[0], v[1], v[2], v[3])
                }
            }
            null
        }.getOrNull()
    }

    /**
     * ★ fix111：开窗几何**只认记忆** —— 有记忆返回下发矩形，没记忆返回 null
     * （调用方走"纯 start"，让澎湃自己给默认小窗几何，出生/关闭时钩子会自动登记）。
     *
     * 旧「② 按设置算」分支整体删除：设置里的尺寸/角落不再参与开窗 —— 系统默认
     * （下发 1080x1728）就是用户习惯的比例，app 再按设置摆只会摆出另一套样子。
     */
    private fun miuiDeliveryRect(pkg: String): IntArray? {
        val (screenW, screenH) = realScreenSize()
        val topAvoid = topAvoidPx()
        val usableTop = topAvoid
        val usableH = (screenH - navAvoidPx() - topAvoid).coerceAtLeast(WindowSizing.MIN_SIDE)
        // ★ fix64：屏幕宽 > 高 = 横屏（realScreenSize 跟随旋转）。记忆按方向分开存，
        //   读的时候**必须**带上方向，否则会拿竖屏的矩形去横屏用。
        val landscape = screenW > screenH
        // 图层缩放（下发坐标收边上限要 ÷ 它：0.70 语义下合法下发值可以超物理屏）
        val scale = HookBridge.miuiLayerScale()
        val limW = (screenW / scale).toInt().coerceAtLeast(WindowSizing.MIN_SIDE)
        val limH = (usableH / scale).toInt().coerceAtLeast(WindowSizing.MIN_SIDE)

        // ★ fix128 语义修正：「记住小窗大小」只管**大小** —— 位置照记忆恢复；
        //   开关关 = 宽高换学得的系统默认（DEFAULT_RECT，缺了退记忆宽高）。
        //   （旧行为"关 = 整份记忆不用"只在这条 adopt 复核路上成立，主开窗链路
        //   的出生钩子从来就没看过这个开关 —— 这正是用户报的"描述有问题"。）
        // 优先读 system_server 钩子实时写的系统文件（200ms 内更新，与出生钩子同源）；
        // 读不到（su 忙 / 无记忆）再退回本进程 SP。
        val mem = readSystemMemoryRect(pkg, landscape) ?: WindowMemory.get(pkg, landscape)
        if (mem == null) {
            SHLog.i(TAG, "windowMemory: $pkg 无记忆 -> 交系统默认")
            return null
        }
        var w = mem[2] - mem[0]
        var h = mem[3] - mem[1]
        var sizeFromDefault = false
        if (!StateManager.current.rememberWindowSize) {
            val d = readDefaultRect(landscape)
            if (d != null) {
                w = d[2] - d[0]
                h = d[3] - d[1]
                sizeFromDefault = true
            }
        }
        // ★ fix86：minTop = 顶部让出的高度（横屏=状态栏）。下发坐标与视觉坐标在
        //   top 上是同一个值（0.70 只缩右/下、锚点左上），所以这里直接用逻辑像素。
        val fitted = WindowSizing.clampRect(
            intArrayOf(mem[0], mem[1], mem[0] + w, mem[1] + h),
            limW, limH + usableTop, usableTop
        )
        if (fitted == null) {
            SHLog.w(TAG, "windowMemory: $pkg 的记忆没法收进当前屏幕，交系统默认")
            return null
        }
        SHLog.i(
            TAG,
            "windowMemory: 命中 $pkg（${if (landscape) "横屏" else "竖屏"}，" +
                (if (sizeFromDefault) "位置=记忆 大小=系统默认" else "位置和大小都用记忆") +
                ") 记忆=${mem[0]},${mem[1]},${mem[2]},${mem[3]}" +
                " -> 下发 ${fitted[2] - fitted[0]}x${fitted[3] - fitted[1]} @ [${fitted[0]},${fitted[1]}]" +
                "（已按 ${limW}x$limH 收边，" +
                "屏幕 ${screenW}x$usableH ÷ 图层缩放 ${"%.3f".format(scale)}，" +
                "★fix86 顶部让出=$usableTop）"
        )
        return fitted
    }

    /** ★ fix128：读学得的系统默认矩形（按方向），adopt 复核路径在开关关时取宽高用。 */
    private fun readDefaultRect(landscape: Boolean): IntArray? {
        return runCatching {
            val res = RootManager.get().executeFast(
                "cat ${HookContract.DEFAULT_RECT_PATH} 2>/dev/null", 2000
            )
            if (!res.success || res.output.isBlank()) return@runCatching null
            val key = HookContract.defaultKey(landscape)
            for (line in res.output.lineSequence()) {
                val i = line.indexOf('=')
                if (i <= 0 || line.substring(0, i).trim() != key) continue
                val v = line.substring(i + 1).split(",").mapNotNull { it.trim().toIntOrNull() }
                if (v.size == 4 && v[2] > v[0] && v[3] > v[1]) {
                    return@runCatching intArrayOf(v[0], v[1], v[2], v[3])
                }
            }
            null
        }.getOrNull()
    }

    /**
     * ★ fix111：**纯 start** —— 该包没有记忆时用。
     *
     * 不写 birth_target（里面没有目标几何，写了出生钩子反而会按旧矩形压窗口）、
     * 不 resize、不复核：让澎湃按自己的默认摆 freeform 窗，出生/关闭时
     * [xiaojw.memoryFreeform.hook.MiuiFreeFormBirthHook] / WindowWatcher 会把系统给的
     * 几何登记成记忆，下次开窗就走记忆了。
     */
    private fun buildPlainFreeformStart(component: String, pkg: String): String =
        "am start-activity --windowingMode 5 -f 0x10000000 -n $component >/dev/null 2>&1" +
            "; sleep 0.3; am stack list 2>/dev/null | grep -E 'taskId=[0-9]+: $pkg/'"

    /**
     * 复核（幂等兜底）：把刚启动的 Freeform task 精确 resize 到小窗矩形。
     *
     * `am task resize <taskId> <left> <top> <right> <bottom>`
     *  - 四个 int **用空格分隔**；写 `0,0,804,1206` 会 NumberFormatException
     *    （`ActivityManagerShellCommand.getBounds` 逐个 parseInt）。
     *  - 真机实测设完后 `am stack list` 里 bounds 立刻变成目标值，且小窗内二级页跳转
     *    不会跳出小窗（topActivity 变了但 bounds 保持）。
     *
     * taskId 要等启动后才存在，所以轮询 `am stack list` 找 topActivity 命中 pkg 的任务行。
     * 找不到就放弃 —— 最坏保留 MIUI 默认几何，不报错、不影响使用。
     */
    /**
     * ★ fix58：从 `am stack list` 里找 [pkg] 的 **freeform** taskId。
     *
     * ## 为什么不能只按包名找（旧代码就是这么干的）
     *
     * 同一个包可以**同时**有一个全屏 task 和一个小窗 task（用户先全屏用着，再开小窗）。
     * 旧代码对每一行只判 `pkg` 相等、取**第一个**命中，而 `am stack list` 是按 RootTask
     * 顺序输出的，全屏栈通常排在前面 —— 于是它可能抓到**全屏那个 task**，把用户的
     * 全屏窗口 resize 成小窗几何；更糟的是 [miuiTasks] 记下的是这个 id，退出小窗时
     * `am stack remove` 收掉的就是**用户的全屏任务**。
     *
     * 这条路径对"接管"尤其危险：接管的目标本来就是用户可能刚在全屏用着的应用。
     *
     * ## 做法：两趟
     *
     *  1. 先按 `mWindowingMode=freeform` 分段，只在 freeform 段里找 —— 正确的那一个；
     *  2. 一趟没找到就**退回旧行为**（任意段里按包名找第一个），只为兼容"本机
     *     `am stack list` 的输出结构哪天变了"这种情况 —— 那时宁可拿到旧行为，
     *     也不能整个 resize 流程失效。退回时打一条日志，便于发现。
     */
    private fun findTaskIdFor(out: String, pkg: String): Int? {
        val taskRe = Regex("taskId=(\\d+):\\s*([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/")
        var freeform = false
        for (line in out.lineSequence()) {
            if (line.startsWith("RootTask")) { freeform = false; continue }
            if (line.contains("mWindowingMode=freeform")) { freeform = true; continue }
            if (!freeform) continue
            val m = taskRe.find(line) ?: continue
            if (m.groupValues[2] == pkg) return m.groupValues[1].toIntOrNull()
        }
        for (line in out.lineSequence()) {
            val m = taskRe.find(line) ?: continue
            if (m.groupValues[2] == pkg) {
                SHLog.w(TAG, "resizeTask: 没在 freeform 段里找到 $pkg，退回按包名取第一个 task")
                return m.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    private fun resizeTaskToWindow(pkg: String) {
        val rm = RootManager.get()
        // 几何与启动时内联下发那条脚本**同源**（miuiDeliveryRect），保证不会
        // "内联设成 A、兜底又压成 B"。
        // ★ fix111：miuiDeliveryRect 可空（无记忆=交系统默认），此时复核无事可做。
        val rect = miuiDeliveryRect(pkg) ?: run {
            SHLog.w(TAG, "resizeTask: $pkg 无记忆（fix111 几何只认记忆），保留系统默认几何")
            return
        }
        val left = rect[0]
        val top = rect[1]
        val right = rect[2]
        val bottom = rect[3]
        // ★ fix54：这里也算一次「本次下发尺寸」—— 切角球热切换（[switchCornerFromWindow]）
        //   走的就是本方法，宽高不变但位置变了；把它对齐后，[isValidWindowRect] 的
        //   ±5% 尺寸校验与 [captureWindowMemoryOnExit] 才有一致的基准。
        lastDeliveredWH = intArrayOf(right - left, bottom - top)
        var taskId = -1
        var attempts = 0
        while (taskId <= 0 && attempts < 12) {   // 最多等 ~3s
            attempts++
            val out = runCatching { rm.executeFast("am stack list", 3000).output }
                .getOrDefault("")
            taskId = findTaskIdFor(out, pkg) ?: -1
            if (taskId <= 0) Thread.sleep(250)
        }
        if (taskId <= 0) {
            SHLog.w(TAG, "resizeTask: 未找到 $pkg 的 taskId，保留系统默认小窗几何")
            return
        }
        // 记下来：退出小窗时要把这个系统 Freeform task 一并收掉，
        // 否则【关掉单手模式】后澎湃小窗还留在屏幕上。
        miuiTasks[taskId] = pkg
        val isLeft = StateManager.current.corner == Corner.LEFT
        val requested = "[$left,$top][$right,$bottom]"
        // 幂等：启动那一刻的内联脚本通常已经把几何压到位了，这里**先读再决定** ——
        // 已是目标几何就什么都不做，多发一次 `am task resize` 会让澎湃**再播一遍**
        // 缩放动画，用户看到的就是【窗口先小后大 / 尺寸越大越迟迟不贴底】。
        val current = readTaskBounds(taskId)
        if (current == requested) {
            SHLog.i(TAG, "resizeTask: 几何已是目标 $requested（内联脚本已生效，跳过二次下发）")
            return
        }
        if (current != null) {
            SHLog.w(TAG, "resizeTask: 内联未生效，当前=$current 目标=$requested，补下发一次")
        }
        val r = runCatching {
            rm.executeFast("am task resize $taskId $left $top $right $bottom", 3000)
        }.getOrNull()
        SHLog.i(
            TAG,
            "resizeTask: taskId=$taskId -> $requested" +
                " (${if (isLeft) "左下" else "右下"}) ok=${r?.success}"
        )

        // ★ fix40：**复核**。`am task resize` 经真机实测是精确且持久的（0.3s / 15s 后
        //   读数都一致，连 200x200 和右/下贴边都不打折扣），所以这里读回系统真实几何
        //   只为两件事：
        //     1) 把"系统到底有没有动我的几何"写进日志 —— 用户问"是不是被系统限制了"
        //        时，这一行就是答案；
        //     2) 万一澎湃的手势层在窗口被触摸/贴边后自己挪了一次，就地压回去一遍。
        Thread.sleep(600)
        val actual = readTaskBounds(taskId)
        when {
            actual == null ->
                SHLog.w(TAG, "resizeTask: 复核读不到 taskId=$taskId 的几何（任务已消失？）")
            actual != requested -> {
                SHLog.w(TAG, "resizeTask: 系统把几何改成 $actual（请求 $requested），再压一次")
                runCatching { rm.executeFast("am task resize $taskId $left $top $right $bottom", 3000) }
                Thread.sleep(400)
                SHLog.i(TAG, "resizeTask: 重设后实际=${readTaskBounds(taskId) ?: "读取失败"}")
            }
            else ->
                SHLog.i(TAG, "resizeTask: 几何已确认 $actual（系统未做任何钳制）")
        }
    }

    // ------------------------------------------------------------------ 会话监听

    /**
     * 会话接入**全局**小窗监听（[WindowWatcher]）。
     *
     * 位置记忆已经不由这里负责了 —— 全局监听盯的是 `am stack list` 里**所有** freeform
     * 小窗，所以"澎湃自己开的窗"和"我们开的窗"在关闭 / 挂起时都会各自落盘。
     * 本方法只剩两件**会话内**的事：
     *  ① 摘掉已消失的 taskId、刷新 [lastStableRect]；
     *  ② 识别「用户用澎湃自己的方式关掉了小窗」—— 那条路不走 `stopSelf()`，
     *     `onDestroy` 根本不会触发，得在这里收尾。
     *
     * 用全局监听的轮询结果而不是再起一条线程：`am stack list` 是常驻 shell 上的命令，
     * 两条线程各轮各的既浪费又容易互相把查询挤成 busy（RootManager 的防雪崩规则）。
     *
     * 目标包不在这里传进来：回调里读 [pendingPkg]（`panel` 选新应用时会更新它）。
     */
    private fun startWindowWatcher() {
        WindowWatcher.ensureStarted(this)
        if (!watcherAttached.compareAndSet(false, true)) return
        val l: (List<WindowWatcher.FfTask>) -> Unit = { tasks ->
            runCatching { onFreeformRound(tasks) }
        }
        watcherListener = l
        WindowWatcher.addListener(l)
    }

    /** 全局监听每轮回调：维护会话状态（位置落盘已在监听器里完成）。 */
    private fun onFreeformRound(tasks: List<WindowWatcher.FfTask>) {
        // ★ fix60：这里原来先 syncWindowRectAsync(tasks) —— watcher 线程算小窗的实测视觉
        //   矩形（顺带在后台线程读一次 HookBridge.miuiLayerScale），给列表面板定位用。
        //   面板已改成按设置现算，那一整段随之删掉。
        val alive = tasks.mapTo(HashSet()) { it.taskId }
        // 摘掉已经消失的 taskId：否则 onDestroy 会对不存在的 task 发 `am stack remove`，
        // 失败后回退 force-stop —— 把用户刚用系统方式关掉的那个 App 后台进程也一起杀了。
        synchronized(miuiTasks) { miuiTasks.keys.retainAll(alive) }
        if (miuiTasks.isNotEmpty()) hadFreeformTask = true

        val pkg = pendingPkg ?: return
        val mine = tasks.firstOrNull { it.pkg == pkg && it.visible }

        // ★ fix112：开窗已改纯系统调用，不再经 resizeTaskToWindow 登记 task ——
        //   这里看到就登记，否则用户用系统手势关掉小窗后，下面的会话收尾
        //   （hadFreeformTask && miuiTasks.isEmpty() -> stopSelf）永远不触发。
        if (mine != null) {
            synchronized(miuiTasks) { if (mine.taskId !in miuiTasks) miuiTasks[mine.taskId] = pkg }
            hadFreeformTask = true
        }

        if (mine != null) {
            // lastStableRect 只作关闭前采集的兜底缓存；纯启动后没有"本次下发尺寸"
            // 可比对，改用矩形自身宽高过一遍几何合法性检查（滤全屏中间帧等垃圾值）。
            val r = mine.rect
            if (isValidWindowRect(r, r[2] - r[0], r[3] - r[1])) lastStableRect = r
            ffGoneRounds = 0
            return
        }

        // 小窗不在原位了（挂起 / 正在退出 / 已被关闭）。连续两轮确认 —— 切换应用时
        // 旧 task 销毁、新 task 建立之间有 1~2 秒空窗，只凭一轮会把正常切窗误判成关闭。
        if (hadFreeformTask && miuiTasks.isEmpty()) {
            if (++ffGoneRounds >= 2) {
                SHLog.i(TAG, "windowWatcher: 小窗已被系统关闭（freeform task 已消失），结束会话")
                runCatching { mainHandler.post { stopSelf() } }
            }
        } else {
            ffGoneRounds = 0
        }
    }

    /**
     * **关闭前记忆**（★ fix54：**只在后台线程调用**，见 [onDestroy]）。
     *
     * 必须在任何清理命令之前跑 —— 那一刻小窗还停在用户拖到的位置；等 `am stack remove`
     * 一发，窗口就开始退出动画，读回来的只会是放大中的全屏中间帧。所以才由 [onDestroy]
     * 在**同一条后台线程**里把它排在收窗口之前，而不是像 fix53 那样放在主线程第一行
     * （那会让"点关闭球"卡住 0.6~6 秒）。
     *
     * 取值顺序**与 fix53 完全一致**（只是整段搬到了后台线程，判据一个字没改）：
     *  ① 现场采两次（间隔 250ms）取**一致值** —— 两次相同才说明窗口已静止，
     *     不会读到退出/重摆动画的中间帧；
     *  ② 采不到才退回 [lastStableRect]（观察线程每轮维护的内存读数）。
     *
     * 之所以不图省事把 ② 提成首选：澎湃在小窗失去顶层地位时会把它重摆到左上角
     * `[35,127]`（fix40 实测），那一帧**尺寸是对的**、能过 [isValidWindowRect]
     * 的一切检查 —— 只有"两次读数必须相同"这条时间维度上的判据能挡掉它。
     *
     * 这条路径只负责**我们自己开的那一个窗**（判据最严：读回尺寸必须与本次下发一致）。
     * 系统开的窗、以及挂起场景，都由全局监听 [WindowWatcher] 负责，两边写同一份记忆、幂等。
     *
     * ★ fix59：它现在多了一道前置闸 —— [WindowWatcher.recordedRecently]。全局监听在
     *   窗口**静止两拍**时就会落盘实测值，那比这里的兜底（[lastStableRect] 缓存，
     *   还要过"尺寸=本次下发"的校验）新；用户拖过又改过大小的情况下，缓存甚至连校验
     *   都过不了，硬写就是把更新的值覆盖成旧的。
     *
     * @param pkg 会话目标包（调用方已从 [pendingPkg] 快照，那时字段还没被清）
     * @param ew 本次下发的宽（[lastDeliveredWH]）
     */
    private fun captureWindowMemoryOnExit(pkg: String, ew: Int, eh: Int) {
        if (ew <= 0 || eh <= 0) return
        // ★ fix59：全局监听刚记过就别再采 —— 窗口"静止两拍"时它已经落盘了实测值，
        //   而这里的兜底用的是 [lastStableRect] 缓存（要过"尺寸=本次下发"的校验）。
        //   用户拖过之后再改过大小的话，那份缓存**更旧**且过不了校验，会白覆盖一次。
        if (WindowWatcher.recordedRecently(pkg)) {
            SHLog.i(TAG, "windowMemory: 全局监听刚记过 $pkg，跳过关闭前采集（不覆盖更新的值）")
            return
        }
        runCatching {
            val b1 = WindowWatcher.visibleBoundsOf(pkg)
            Thread.sleep(250)
            val b2 = WindowWatcher.visibleBoundsOf(pkg)
            val b = if (
                b1 != null && b2 != null && b1.contentEquals(b2) &&
                isValidWindowRect(b1, ew, eh)
            ) b1 else lastStableRect?.takeIf { isValidWindowRect(it, ew, eh) }

            if (b == null) {
                SHLog.i(TAG, "windowMemory: 关闭前未采到有效位置，$pkg 记忆保持不变")
                return
            }
            WindowWatcher.record(pkg, b, "关闭前")
        }.onFailure { SHLog.w(TAG, "windowMemory: 关闭前采集失败 ${it.message}") }
    }

    /**
     * 读回值是不是「用户真正停留的位置」。
     *
     * 核心判据是**尺寸必须与本次下发一致（±5%）**：小窗拖动只改位置、不改尺寸，
     * 而退出动画会把它放大到全屏、重摆帧的尺寸也是飘的 —— 光这一条就滤掉了日志里
     * 出现过的 `1080x2400` / `1079x1728` 等全部垃圾值。
     */
    private fun isValidWindowRect(b: IntArray, ew: Int, eh: Int): Boolean {
        if (ew <= 0 || eh <= 0) return false
        if (b[0] < 0 || b[1] < 0) return false
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        if (w < 200 || h < 200) return false
        val (sw, sh) = realScreenSize()
        if (w > sw || h > sh) return false
        if (b[0] >= sw - 100 || b[1] >= sh - 100) return false
        if (kotlin.math.abs(w - ew) > ew * 0.05f) return false
        if (kotlin.math.abs(h - eh) > eh * 0.05f) return false
        return true
    }

    /** 读回某个 task 当前在 `am stack list` 里的 RootTask bounds，形如 `[245,1189][1080,2358]`。 */
    private fun readTaskBounds(taskId: Int): String? {
        val out = runCatching {
            RootManager.get().executeFast("am stack list", 3000).output
        }.getOrNull() ?: return null
        val re = Regex("RootTask id=$taskId bounds=(\\[[0-9,-]+]\\[[0-9,-]+])")
        return re.find(out)?.groupValues?.get(1)
    }

    // ------------------------------------------------------------------ 收尾

    override fun onDestroy() {
        // ★ fix54 铁律：**主线程这一段只做视图摘除与状态翻转，一条 root 命令都不许有。**
        //
        // 旧实现的第一行是同步的 `captureWindowMemoryOnExit()`，它内部要 fork 两次 su
        // （每次最坏 3s）再 `Thread.sleep(250)`，而且 `removeView` 排在它后面 ——
        // 用户点「关闭球」看到的就是：球仍在屏幕上、整个界面卡住不动。
        // 现在抓记忆挪到下面的后台线程，主线程立刻把球摘掉。

        // ① 声明"小窗已关"。`isWindowAlive()` 以这个标记为第一道闸 —— 而 `onDestroy`
        //   是**三条退出路径（App 关闭按钮 / 悬浮球 stopSelf / 系统停服务）的公共出口**，
        //   在这里同步置位，MainActivity 才会在同一次重组里恢复本页。
        runCatching { StateManager.markViewsDetached() }
        // ② 必须在这里清 enabled —— 「关闭球」走的是 stopSelf()，绕过了
        //   SingleHandManager.disable()。enabled 留在 true 的后果：本页永远退在后台。
        StateManager.updateEnabled(false)

        // ③ 视图摘除紧跟其后（都是本进程的 overlay，removeView 是同步快操作）。
        //   放在最前面：用户一点就该看到球消失，而不是等一串 root 命令跑完。
        // ★ fix53：列表面板同样是 overlay，必须摘掉 —— 服务没了它还留着的话，
        //   下次开小窗会叠出一张新的（用户看到"面板越关越多"）。
        runCatching { panelView?.let { wm?.removeView(it) } }
        // ④ 会话结束，摘掉全局监听上的挂钩（全局监听本身继续跑 —— 它要负责
        //   系统自己开的那些小窗，不能随会话停）。
        watcherListener?.let { WindowWatcher.removeListener(it) }
        watcherListener = null
        watcherAttached.set(false)

        // ⑤ 给后台线程拍快照（都是内存读，主线程零成本）
        val exitPkg = pendingPkg?.takeIf { it.isNotBlank() }
        val exitW = lastDeliveredWH[0]
        val exitH = lastDeliveredWH[1]
        val hadTasks = miuiTasks.isNotEmpty()
        val pendingTasks = miuiTasks.entries.map { it.key to it.value }
        miuiTasks.clear()
        cacheInvalidated = true

        // ⑥ 剩下的全是慢活（fork su），**串行**跑在这条后台线程上。
        //   顺序仍然保证「先抓记忆、再收窗口」：小窗必须还停在原位时抓 ——
        //   `am stack remove` 一发，窗口就开始退出动画，读到的只会是放大中的全屏中间帧。
        Thread {
            // 6.1 关闭前记忆（只有我们确实开过窗时才需要）
            if (hadTasks && exitPkg != null) {
                runCatching { captureWindowMemoryOnExit(exitPkg, exitW, exitH) }
            }

            // 6.2 注销**会话身份**。记录钩子（MiuiFreeformRecordHook）按这个文件判断
            //     "哪些小窗是我们开的" —— 留着它，用户之后自己开的小窗会被当成我们的、
            //     位置记错进主文件。删掉即可，不影响位置记忆（那是另一个文件）。
            runCatching {
                RootManager.get().executeFast(
                    "rm -f ${xiaojw.memoryFreeform.hook.HookContract.SESSION_PATH}", 2000
                )
            }

            // 6.3 收掉我们开的 Freeform task，否则"关掉单手模式"后澎湃小窗仍留在屏幕上。
            //   ★ fix131：miuiTasks 靠 watcher 轮询登记（约 2s 一轮）—— 开窗后几秒内就点
            //   「关闭小窗」的话 task 还没登记上，pendingTasks 是空的，小窗就成了孤儿
            //   （用户实测：面板选应用 -> 立刻点关闭 -> 小窗留在屏幕上关不掉）。
            //   这里补一道：按 exitPkg 现查一遍 am stack list，把没登记上的 task 一并收掉。
            runCatching {
                val rm = RootManager.get()
                val known = pendingTasks.map { it.first }.toHashSet()
                val extraTasks = if (exitPkg.isNullOrBlank()) emptyList() else runCatching {
                    Regex("taskId=(\\d+): ${Regex.escape(exitPkg)}/").findAll(
                        rm.executeFast("am stack list", 3000).output
                    ).mapNotNull { m -> m.groupValues[1].toIntOrNull() }
                        .filter { it !in known }.toList()
                }.getOrDefault(emptyList())
                if (extraTasks.isNotEmpty()) {
                    SHLog.i(TAG, "close: 补收未登记的 freeform task $extraTasks ($exitPkg)")
                }
                for ((tid, pkg) in pendingTasks + extraTasks.map { it to exitPkg.orEmpty() }) {
                    // ⚠ 这里**不能**写 `am task remove` —— Android 16 的 `am` 没有
                    //   task remove 子命令（`am task` 只有 lock / lockTask / resize /
                    //   focus / drag-test），写了就是静默失败、还照打成功日志。
                    //   真机实测能收掉 Freeform task 的是 `am stack remove <taskId>`
                    //   （命令名是 stack，参数收的却是 taskId）。
                    //   万一还是失败，退回 force-stop 该包 —— 进程没了 task 自然消失。
                    val r = runCatching { rm.execute("am stack remove $tid") }.getOrNull()
                    val out = r?.output?.trim().orEmpty()
                    val ok = r != null && r.success && !out.contains("Error", ignoreCase = true)
                    if (ok) {
                        SHLog.i(TAG, "miui freeform task removed: $tid ($pkg)")
                    } else {
                        // 兜底之前先**确认 task 是不是还在**：`executeFast` 在查询通道忙时
                        // 会**跳过本轮**（那不是"关窗失败"）。旧代码直接 force-stop 兜底，
                        // 把用户刚关掉小窗的那个 App 后台进程也一起杀了。
                        val stillThere = runCatching {
                            rm.executeFast("am stack list", 3000).output.contains("taskId=$tid:")
                        }.getOrDefault(true)
                        if (stillThere) {
                            SHLog.w(TAG, "am stack remove $tid 失败 out=【$out】-> force-stop $pkg 兜底")
                            runCatching { rm.execute("am force-stop $pkg") }
                        } else {
                            SHLog.i(TAG, "am stack remove $tid 无输出但 task 已消失（视为成功）")
                        }
                    }
                }
            }.onFailure { SHLog.w(TAG, "remove miui tasks failed", it) }

            // 6.4 抓 crash 缓冲：用户报「首次进二级页软件重启一次」在 App 侧日志零痕迹 ——
            //     应用进程的死活只能看系统 crash buffer。关窗时用户大概率刚经历过问题，
            //     抓最近 200 条 crash（root 可读），栈里写明谁死的、为什么死。
            runCatching {
                val out = RootManager.get().executeFast("logcat -d -b crash -t 200", 6000)
                val lines = out.output.lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    SHLog.i(TAG, "crash buffer: empty（会话期间无任何应用崩溃 —— 重启另有机制）")
                } else {
                    SHLog.i(TAG, "crash buffer: ${lines.size} lines（最近 200 条内）")
                    lines.takeLast(120).forEach { SHLog.i(TAG, "crash| $it") }
                }
            }

            // 6.5 **最后**才销毁常驻 shell —— 上面 6.2~6.4 都还要用它们。
            //     （旧顺序是先 destroyShell 再 logcat，logcat 会把刚销毁的查询通道重建起来，
            //      等于白销毁一次。）
            runCatching { RootManager.get().destroyShell() }
        }.apply { name = "session-exit" }.start()

        controlsReady = false
        panelView = null; panelLp = null; panelMode = null
        pendingPkg = null; pendingAct = null; launched = false
        super.onDestroy()
    }
}
