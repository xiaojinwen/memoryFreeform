package xiaojw.memoryFreeform.hook

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ★ fix66：**小窗位置记录搬进 system_server**（之前在 App 进程的 `WindowWatcher` 里轮询）。
 *
 * ## 为什么搬过来
 *
 * `WindowWatcher` 是 App 进程里的线程，两个硬伤它解决不了：
 *  ① **App 进程死了（浮球隐藏 / 被杀）就没人记** —— 那段期间关掉的小窗位置全丢；
 *  ② **只能每秒轮询 `am stack list` 采样**，既不是"拖完/缩放完那一刻"也白烧电唤醒。
 *
 * 系统侧常驻、对所有窗口生效，且能拿到**真实的拖拽/销毁回调**，于是：
 *  - 不管小窗是澎湃侧边栏/通知/最近任务开的，还是我们开的 —— **都记**；
 *  - 不管我们的 App 在不在 —— **都记**；
 *  - 窗口**几何落定**（`Task.resize`，拖动/缩放的结束那一帧）或**销毁**（`Task.remove*`）时
 *    直接写记忆，**不用等 1 秒**。
 *
 * ## 挂点（按名字 + 参数个数匹配，抗 ROM 改签名）
 *
 * 与 [MiuiFreeFormBirthHook] 同款策略：不硬赌精确签名，只认「类名 + 方法名 + 参数个数」。
 * 这里挂的是 AOSP 里极稳的两个漏斗：
 *  - `Task.resize(int,int,int,int[,boolean…])` —— freeform 几何变更的最终落定点
 *    （拖动每一帧、缩放把手、我们的 `am task resize` 都走它）；`after` 里读**改完之后**的
 *    bounds，带 **debounce** 写（最后一次变化后 350ms 才落盘 = "用户停手了"）。
 *  - `Task.removeIfPossible(String[,…])` / `Task.remove([boolean])` —— 窗口销毁前
 *    （`before` 里读，窗口还在）立刻把当前 bounds 写下去，覆盖"手势条关窗 / 直接杀"等
 *    resize 没机会触发的路径。
 *
 * ⚠ 这两个名字在 `WindowContainer`/`ActivityStack` 上也有（例如 `removeChild`），所以用
 *   `locate` 找到的方法**只认 `com.android.server.wm.Task` 这一个 thisObject**，回调里先判
 *   类名再处理，避免钩到别的容器上。
 *
 * ## fix78：手势条拖拽关窗的「触摸起点锁定」
 *
 * 拖小窗**底部手势条**关闭时，窗口几何全程跟手指下滑（`onMovedByResize` 帧一路攒），
 * 关窗 flush 把"跟指中间帧"当用户位置记下 → 重开位置错。方案（用户指定）：
 * system_server 里注册全局 `PointerEventListener`，**DOWN 落在小窗底部手势条带上**那一刻：
 *  ① `flushNow` 立即记录停稳位置（拖动前的正确位置）；
 *  ② 置 `barFlags[pkg]` 标记，标记期内 resize 帧只进内存缓冲**不落盘**；
 *  ③ **ACTION_UP/CANCEL** 后按 [BAR_UP_JUDGE_DELAY_MS] / [BAR_UP_RECHECK_DELAY_MS]
 *     **两次采样 `getWindowingMode()`** 判定：仍在 freeform = 「移动」→ flush 终帧；
 *     已退出 freeform（**澎湃关小窗 = mode→fullscreen + 隐藏，并不 remove Task**）
 *     = 「关闭」→ 丢弃缓冲、写入 ① 的按下快照（停稳位置）；15s 超时保险防 UP 丢失。
 * ★ fix78b：注册改用 lpparam.classLoader（模块 classloader 看不见 services.jar，真机
 *   plisten=0 实证）；DOWN 命中去掉 5s TTL、改读 task 实时 bounds；自检行加 err=。
 * 自检：`memoryfreeform_record.state` 的 `bar(down/hits/ups/flags/plisten)` 行 +
 * `memoryfreeform_record.state.moved` 里的 `BAR-DOWN/BAR-UP` 标记。
 *
 * ## 写什么
 *
 * 与 App 侧 `WindowMemory` / 出生钩子**完全同域**（见 [HookContract.WINDOW_MEMORY_PATH]、
 * [HookContract.memoryKey]）：竖屏 `pkg=l,t,r,b`、横屏 `pkg@L=l,t,r,b`。落盘前按当前屏幕
 * **收边 + 拒绝屏外几何**（方向自洽，绝不跨方向回退）。所有写都在同一条 `writeHandler`
 * 线程上串行，避免两个进程（系统 + App）同时改同一个文件。
 */
object MiuiFreeformRecordHook {

    private const val TAG = "SingleHand/Record"

    private const val FREEFORM_MODE = 5

    /**
     * debounce：最后一次几何变化后这么多毫秒才落盘（≈ 用户停手）。
     * fix67b：350→200；**fix80b：200→80** —— fix80 已把「resize 改坐标」判成无效数据，
     * 可信来源只剩用户拖动（`onMovedByResize`），不必再靠长 debounce 躲系统重摆，80ms 就能
     * 合并连帧又不拖慢手感（松手即生效）。
     */
    private const val DEBOUNCE_MS = 80L

    /** 记下来的窗口最小边（比这小的不是"用户在用的小窗"）。 */
    private const val MIN_SIDE = 200

    // ★★ fix78：手势条（小窗底部那条）拖拽关窗的「触摸起点锁定」参数。
    //  ★ fix78f：条带几何判定已整体移除（pointer 坐标空间 ≠ bounds 空间，实测不可靠），
    //    改为"任何按下 + 存在活小窗"即快照置标记 —— 拖动必以 DOWN 开头，快照必先于拖动帧。

    /** 标记位超时保险：万一 UP 事件丢了（input 线程竞争 / 系统吞事件），最多拦这么久。 */
    private const val BAR_FLAG_TIMEOUT_MS = 15_000L

    /**
     * ★ fix78i：UP 后**第一次采样**的延时 —— 看窗口还活不活在 freeform（关小窗的
     *   方式是 mode→fullscreen，比这早）。活 = 先按「移动」flush 终帧。
     *   ★ fix80b：900→700（配合 fix80b 的「退出 freeform 当场自愈」，实际多数关闭
     *   根本等不到这次采样就已经纠正完了，采样退居兜底）。
     */
    private const val BAR_UP_JUDGE_DELAY_MS = 700L

    /** ★ fix78i：第二次采样（在第一次之后再等这么久，即 UP+2.5s）下最终结论，防"只是失焦"误判。 */
    private const val BAR_UP_RECHECK_DELAY_MS = 1_600L

    /** ★ fix78g：`barRecent`（UP 后待判期）的超时保险——**必须大于两次采样之和**（900+1600）。 */
    private const val BAR_PENDING_TIMEOUT_MS = 6_000L

    // ---------------------------------------------------------------- 挂点

    private enum class Kind { RESIZE, REMOVE }

    private data class Site(
        val label: String,
        val clazz: String,
        val method: String,
        val kind: Kind,
        /** true = 只钩本类**自己声明**的方法（不追父类）。用于 `setBounds`：避免钩到
         *  `WindowContainer.setBounds` 而波及全局每个窗口、拖垮 system_server。 */
        val declaredOnly: Boolean = false
    )

    // ★ 只认「类名 + 方法名」，挂该类下**所有参数量**的同名方法 ——
    //   不赌具体签名（Android 跨版本 `resize` / `remove` 参数量会变，上一版写死 4/5/6 在本机全漏了）。
    //   回调里再用 `thisObject` 必须是 `Task` 这一道闸，避免钩到 `WindowContainer` 之类父类方法。
    private val SITES = listOf(
        // ★ fix87 精简：`Task.resize` 与 `Task.remove` 两个站点已删除 —— 真机自检
        //   `Task.resize inst=1 calls=0`、`Task.remove inst=1 calls=0`：装得上但从没被
        //   调用过（本机 AM 的 resize/关窗走的是 `onResize`/`onMovedByResize`/`setBounds`/
        //   `removeIfPossible` 这条链），留着只是白占两个 inline hook 位。
        //   真正写记忆的只有 `onMovedByResize`（applied=calls），其余各点职责见各自注释。
        // ★ fix90：**恢复 `Task.resize` 站点**（fix87 因 calls=0 误删）。真机复现：从消息横幅
        //   展开的小窗，出生/展开帧走的是 `resize` 而不是 `onResize`/`setBounds` —— 删了它
        //   出生帧就登记不进 `freeformSeen`，手势条按下时找不到活窗、建不了快照，
        //   于是关闭动效的重摆帧畅通无阻写进记忆（用户报"横幅开窗手势关闭位置错"）。
        //   这里只用它登记活窗 + 刷路由（skip-write，坐标由 `onMovedByResize` 负责），
        //   多一个挂点的开销可以忽略（resize 在本机调用极稀疏）。
        Site("Task.resize", "com.android.server.wm.Task", "resize", Kind.RESIZE, declaredOnly = true),
        // ★ fix71：MIUI 拖动小窗几何落定后走的是这两个 0 参回调，不是 `resize`/`setBounds`。
        //   真机探针证实 `Task` 自身 declared 了 `onResize(0)` / `onMovedByResize(0)`（AOSP 的
        //   `WindowContainer.onResize` 是内容尺寸变化回调；`onMovedByResize` 是 MIUI 拖动落定回调）。
        //   fix66~fix70 只钩 resize/setBounds/remove，导致拖动时系统钩子几乎不命中（resize inst=1
        //   calls=1、setBounds 大量 skip=not-freeform），全靠 App 侧 2 秒轮询兜底 → 用户报
        //   "拖动后位置有时记不上、要等几秒才记上"。这两个才是真正兜住拖动的那一下。
        //   ★ fix73：`declaredOnly=true` —— 父类 `WindowContainer` 也 declared 了同名 `onResize(0)`/
        //   `onMovedByResize(0)`，`locateByName` 追父类会连它一起钩（inst=2），回调里 thisObject
        //   不是 Task 的全被 `not-task` 过滤（实测 onResize calls=65 里 61 次是这种噪音）。既然
        //   Task 自己 declared 了这俩方法，就只钩 Task 的，不再白钩 WindowContainer 的。
        Site("Task.onResize", "com.android.server.wm.Task", "onResize", Kind.RESIZE, declaredOnly = true),
        Site("Task.onMovedByResize", "com.android.server.wm.Task", "onMovedByResize", Kind.RESIZE, declaredOnly = true),
        // ★ MIUI 拖拽小窗可能走 `Task.setBounds` 而非 `resize`；只钩 Task **自己声明**的，不追父类。
        Site("Task.setBounds", "com.android.server.wm.Task", "setBounds", Kind.RESIZE, declaredOnly = true),
        Site("Task.removeIfPossible", "com.android.server.wm.Task", "removeIfPossible", Kind.REMOVE)
    )

    // ---------------------------------------------------------------- 统计

    private class Stat {
        @Volatile var inst = 0
        val calls = AtomicInteger()
        val applied = AtomicInteger()
        @Volatile var skip = "-"
        override fun toString() =
            "inst=$inst calls=${calls.get()} applied=${applied.get()} skip=$skip"
    }

    private val stats = ConcurrentHashMap<String, Stat>()
    private val seen = AtomicInteger()
    private val written = AtomicInteger()
    @Volatile private var lastPkg = "-"
    @Volatile private var lastRect = "-"
    @Volatile private var lastSkip = "-"

    // ---------------------------------------------------------------- 写盘线程

    /** 所有记忆写都在这条线程上串行，避免与 App 进程抢写同一个文件。 */
    private val writeThread = HandlerThread("memoryfreeform-rec").also { it.start() }
    private val writeHandler = Handler(writeThread.looper)

    /**
     * ★ fix90：每个包**最后一次被真实触摸**的时刻（ms）。
     *
     * 手势条标记 [barFlags] 只在「按下时恰好能从 [freeformSeen] 找到活窗」时才置得上去；
     * 横幅展开的窗出生帧可能不走我们钩的那几个回调，活窗登记不上 → 按下建不了快照 →
     * 关闭动效帧无人拦截（真机实证：横幅开窗、未移动、手势关闭，记忆被写成重摆位置）。
     * 这里退一步：只要**近期摸过屏幕**，就认为几何变化是用户弄的，可以快写；
     * 没摸过则按系统帧处理（长 debounce），由 [cancelPending] 在关窗时掐掉。
     */
    private val lastTouchTs = ConcurrentHashMap<String, Long>()

    private fun isTouched(pkg: String, windowMs: Long): Boolean =
        lastTouchTs[pkg]?.let { System.currentTimeMillis() - it < windowMs } ?: false

    /** ★ fix90：取消某包所有待落盘的 debounce 与缓冲帧（关窗 / 退出 freeform 时调用）。 */
    private fun cancelPending(pkg: String) {
        val ro = routeOf[pkg] ?: run {
            val sz = readDisplaySize()
            val land = sz != null && sz[0] > sz[1]
            computeRoute(pkg, land)
        }
        val key = stableKeyFor(ro)
        runnables.remove(key)?.let { writeHandler.removeCallbacks(it) }
        latest.remove(key)
    }

    /** pkg(+来源标记) → (pkg, 最新 bounds)，debounce 期间攒着。★ fix67b：用稳定键，不再用会变的 Task 对象哈希。 */
    private val latest = ConcurrentHashMap<String, Pair<String, Rect>>()
    /** 同键 → 待执行的落盘 runnable（用于取消上一次的延时）。 */
    private val runnables = ConcurrentHashMap<String, Runnable>()

    /**
     * ★ fix91：稳定键 → 上次**落盘**的尺寸（w × h）。
     *
     * 用途：给「尺寸类回调」（`onResize` / `setBounds` / `Task.resize`）一个比较基准 ——
     * 只有宽高真的和上次写过的不同，才认为"用户拉伸了"并允许更新大小。
     * 进程内缓存即可：它只用来判断"变没变"，重启后首次开窗不写（见 ② ），
     * 由 `onMovedByResize`（拖动）或我们 App 的下发值重新确立基准，不会出现误记。
     */
    private val lastSize = ConcurrentHashMap<String, Pair<Int, Int>>()

    /**
     * ★ fix69b：每个包**本次生命周期内**已确立的写盘路由（path + key）。
     *
     * 关窗 `removeIfPossible` 的 flush 必须复用它，而不是在那一刻重算路由
     * （fix79 起路由虽已统一，但这份缓存仍承担「drag 期间确立的正确方向键」职责）：
     * 我们 App 的关窗流程（见 `CornerWindowService` 关窗 6.2→6.3）**先 `rm -f` 会话文件、
     * 再 `am stack remove` 关窗**，系统侧 flush 跑起来时会话早已不在 → 被误判成「系统窗」
     * 写进 SB 文件；而我们 App 重开只认主文件（`readSystemMemoryRect` 只读
     * `WINDOW_MEMORY_PATH`）→ 这条记忆永远读不回来 = "记不上"。
     * 复用 drag 期间 `onResize` 算过的那份路由，就绕开了这个时序竞态。窗口关掉即清，
     * 下次再开（无论我们还是系统）的第一个 `onResize` 会按当时的会话重新算。
     */
    private val routeOf = ConcurrentHashMap<String, Pair<String, String>>()

    // ---------------------------------------------------------------- 手势条触摸锁定（fix78）

    /**
     * ★ fix80：一次写入的**来源**（决定要不要走「坐标保护」判定）。
     *  - [Src.MOVE]：用户/我们主动**移动**了窗口（`onMovedByResize` 触摸拖动、手势条快照与
     *    移动终帧、关窗时的停稳快照）→ 坐标本来就该变，照写。
     *  - [Src.RESIZE]：**尺寸类**回调（`Task.resize`：我们 App 的 `am task resize`、
     *    MIUI 缩放把手；`onResize`/`setBounds`）→ **resize 不改变坐标**，凡是这类帧把记忆
     *    坐标（left/top）改了的，一律是无效数据（系统重摆 / 缩放补偿 / 动效帧），
     *    见 [doWrite] 里的 fix80 闸。
     */
    private enum class Src { MOVE, RESIZE }

    /** ★ fix80：resize 帧的坐标与记忆坐标差这么多以下，视为「没改坐标」（缩放舍入）。 */
    private const val RESIZE_COORD_TOL = 8

    /**
     * ★ fix90：**用户没在摸屏幕**时的落盘延时。必须明显大于「按下手势条 → 窗口退出
     * freeform」这段间隔（真机几十毫秒），关窗信号才来得及把系统帧掐掉；又不能太大，
     * 否则监听万一失灵时用户拖动的位置要等太久才记上。
     */
    private const val IDLE_DEBOUNCE_MS = 500L

    /** ★ fix80 自检：被判「resize 改坐标 = 无效数据」而只沿用旧坐标的次数。 */
    private val resizeCoordInvalid = AtomicInteger()

    /** 近期见过的 freeform 任务：pkg → (task 引用, bounds 快照, **首次**见到时刻)。DOWN 判定用。 */
    private class Seen(val task: Any, val bounds: Rect, val at: Long)
    private val freeformSeen = ConcurrentHashMap<String, Seen>()

    /**
     * ★ fix92：出生「安静期」时长。MIUI 开小窗的**进入动画是逐帧 resize 实现的**，中间帧
     * 全走 `onMovedByResize`（真机实证：酷安重开，动画中间帧 97,355 与终点帧 97,804 先后
     * 各写一次记忆）。平时终点帧会把中间帧纠正回来，但只要用户在动画那 1~2 秒里关窗 /
     * 上滑挂起，**动画半路的位置就成了最后一次写入** —— 重开就跑偏（用户报「从 App 首页
     * 打开小窗后记忆失效」）。安静期内的帧只要没有触摸（用户没在拖），一律丢弃。
     */
    private const val BIRTH_QUIET_MS = 2_000L

    /**
     * ★★ fix78b：**手势条「拖动期」标记**（pkg → 按下时刻 ms）。
     * 标记期内 resize 帧不落盘，只攒进 `latest`（无 debounce runnable）——拖手势条既可能是
     * 「关」也可能是「移动」：UP 后延时确认窗还活着才 flush 终帧（移动），remove 则丢弃
     * （关闭，按下时已记停稳位置）。
     */
    private val barFlags = ConcurrentHashMap<String, Long>()

    /** ★ fix78b：UP 之后到「延时落盘判定」之间的缓冲期（pkg → 抬起时刻 ms），remove 时据此丢弃。 */
    private val barRecent = ConcurrentHashMap<String, Long>()

    /** ★ fix78f：按下时快照的停稳位置（pkg → bounds）。关闭时写入记忆，移动时作废。 */
    private val barSnapshot = ConcurrentHashMap<String, Rect>()

    /**
     * ★ fix89：本轮（该 task 自出生起）发生过「用户/我们主动几何变更」（`onMovedByResize`
     * 通过失焦角过滤的帧）的包。系统开的小窗（悬浮通知 banner 下拉 / 侧边栏 / am 命令）
     * 用户没拖过就不在这里 —— 手势关闭时它的按下快照只是**系统默认几何**，不许写记忆
     * （真机复现：am 开窗→不动→手势关闭，记忆被写成默认几何 286,714,794,1794）。
     */
    private val userGeomPkgs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val barDowns = AtomicInteger()
    private val barHits = AtomicInteger()
    private val barUps = AtomicInteger()

    /** 已注册的全局 PointerEventListener（非空 = 注册成功，不再重复注册）。 */
    @Volatile private var pointerListener: Any? = null
    private var pointerRegTried = 0
    private var appClassLoader: ClassLoader? = null

    /** ★ fix78b：最近一次注册失败的原因（进自检 bar(...) 行，真机直接看为什么没注册上）。 */
    @Volatile private var pointerErr: String = ""

    // ---------------------------------------------------------------- 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        appClassLoader = lpparam.classLoader
        for (site in SITES) {
            val stat = stats.getOrPut(site.label) { Stat() }
            val cls = runCatching { XposedHelpers.findClass(site.clazz, lpparam.classLoader) }
                .getOrNull()
            if (cls == null) {
                log("${site.label}: class not found")
                continue
            }
            // ★ 探针：把 Task 上所有含 resize/setBounds/remove/move 的方法签名 dump 出来，
            //   真机读 `memoryfreeform_record.probe` 就能看到这台 ROM 的真实签名（抗改签名）。
            if (site == SITES.first()) probeSignatures(cls)
            val methods = if (site.declaredOnly) locateDeclared(cls, site.method) else locateByName(cls, site.method)
            if (methods.isEmpty()) {
                log("${site.label}: no method ${site.method}")
                continue
            }
            for (m in methods) {
                runCatching { m.isAccessible = true }
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (site.kind == Kind.REMOVE) runCatching { onRemove(stat, param) }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (site.kind == Kind.RESIZE) runCatching { onResize(stat, param) }
                        }
                    })
                    stat.inst++
                }.onFailure { log("${site.label}: hook failed ${it.message}") }
            }
        }
        // ★ fix87：原「MIUI 手势类探针」(installGestureClassProbe) 已删除 —— 真机自检
        //   `gm(hooked=0 -)`：候选类一个都没钩上，而全局 pointer 监听（plisten=1）已经
        //   接管了手势条 DOWN/UP 的检测。留着只会在 system_server 启动时白扫一遍类名。
        // ★ fix79：记忆统一一份 —— 启动时把旧 SB 记忆并入主文件（写线程上跑，与后续写盘串行）。
        writeHandler.post { migrateSbMemory() }
        startFlusher()
        log("installed record hook: ${stats.values.count { it.inst > 0 }}/${SITES.size} sites")
    }

    // ------------------------------------------------ MIUI 手势类探针 + 触摸管道（fix78e）

    /** 按「名字」在类及其父类里找**所有参数量**的同名方法（不校验参数类型，抗 ROM 改签名）。 */
    private fun locateByName(cls: Class<*>, name: String): List<java.lang.reflect.Method> {
        val out = LinkedHashMap<String, java.lang.reflect.Method>()
        var c: Class<*>? = cls
        while (c != null && c.name != "java.lang.Object") {
            for (m in c.declaredMethods) {
                if (m.name == name) out[m.toString()] = m
            }
            c = c.superclass
        }
        return out.values.toList()
    }

    /** 只找本类**自己声明**的同名方法（不追父类）—— 用于 `setBounds` 避免钩到 `WindowContainer`。 */
    private fun locateDeclared(cls: Class<*>, name: String): List<java.lang.reflect.Method> {
        return cls.declaredMethods.filter { it.name == name }
    }

    /** 把 Task 上与几何/销毁相关的方法签名写进探针文件，便于"本机到底有没有这个名字"实查。 */
    private fun probeSignatures(cls: Class<*>) {
        runCatching {
            val sb = StringBuilder()
            var c: Class<*>? = cls
            while (c != null && c.name != "java.lang.Object") {
                for (m in c.declaredMethods) {
                    val n = m.name
                    if (n.contains("resize") || n.contains("setBounds") || n.contains("moveTo") ||
                        n.contains("onResize") || n.contains("onMoved") || n.contains("remove")
                    ) {
                        sb.append("${c.simpleName}.$n(${m.parameterTypes.size})\n")
                    }
                }
                c = c.superclass
            }
            val f = File(HookContract.WINDOW_MEMORY_PATH + ".probe")
            f.writeText(sb.toString())
            runCatching { f.setReadable(true, false) }
            log("probe signatures written (${f.absolutePath})")
        }
    }

    // ---------------------------------------------------------------- 拦截

    private fun onResize(stat: Stat, param: XC_MethodHook.MethodHookParam) {
        seen.incrementAndGet()
        stat.calls.incrementAndGet()
        val task = param.thisObject
        if (task?.javaClass?.name != "com.android.server.wm.Task") {
            stat.skip = "not-task"
            return
        }
        // ★ fix78：手势条触摸检测需要全局 PointerEventListener，趁任何 Task 回调懒注册一次
        //   （system_server 启动早期 WindowManagerInternal 可能还没就绪，所以持续重试）。
        ensurePointerListener(task)
        if (!isFreeformTask(task)) {
            // ★★ fix80b：**关窗即时自愈**。抬手后（barRecent 待判期）窗口一退出 freeform
            //   = 确定是「关闭」（澎湃关小窗 = mode→fullscreen + 隐藏），**当场**丢弃跟指帧、
            //   写入按下快照，不必等 700ms 采样 —— 记忆纠正从"抬手后 0.9s"提前到"动效一开始"。
            val p0 = pkgOfTask(task)
            // ★★ fix90：窗口退出 freeform（关闭 / 最小化）→ **无条件**取消这个包所有待写
            //   与缓冲帧。真机实证：关闭动效的重摆帧（把窗从 84,310 摆到 386,605 那一帧）
            //   走 `onMovedByResize` 进 `latest`，80ms 后 debounce 落盘 —— 比"窗口退出
            //   freeform"这个信号还快，于是系统重摆的几何被当成用户位置写进记忆。
            //   只要在关窗这一刻把待写全掐掉，那批帧就再也落不了盘。
            if (p0 != null) cancelPending(p0)
            if (p0 != null && barRecent.containsKey(p0)) {
                stat.skip = "not-freeform(bar-close)"
                writeSnapshotAsClose(p0)
            } else {
                stat.skip = "not-freeform"
            }
            return
        }
        val pkg = pkgOfTask(task) ?: run { stat.skip = "no-pkg"; return }
        val b = boundsOf(task) ?: run { stat.skip = "no-bounds"; return }
        // ★ fix78：登记"近期见过的 freeform 任务"（含 skip-write 的出生帧），DOWN 判定用。
        //   ★ fix92：at 记**首次**见到时刻（出生时刻），后续帧刷新 task/bounds 但保留 at，
        //   供「出生安静期」判定；窗口关掉（onRemove 清 freeformSeen）后重开则重新计时。
        val prevSeen = freeformSeen[pkg]
        freeformSeen[pkg] = Seen(task, Rect(b), prevSeen?.at ?: System.currentTimeMillis())
        // ★ fix124：这帧是不是「出生帧」（该包此前从没见过 freeform 任务）。
        val isFirstSeen = prevSeen == null
        stat.applied.incrementAndGet()
        // ★ fix71：每次 resize 都**实时重算路由**并刷新 routeOf，而不是「首次锁定永久复用」。
        //   原因：App 开窗脚本是「窗口出生（setBounds 到默认几何）→ 循环拿到 taskId → 写 SESSION
        //   → am task resize」。窗口出生的那次 setBounds 时 SESSION 还没写，若按首次锁定就会把
        //   路由锁死成「系统窗/SB」；之后 SESSION 写好了、用户拖动，也永远写不进主文件 →
        //   App 重开读不到 = "拖动位置记不上"。实时重算后，SESSION 一就绪，下一帧 resize 就自动
        //   纠正成「我们的窗/主文件」。routeOf 仍每帧刷新，供 onRemove 关窗 flush 复用（那时
        //   SESSION 已被 App 删掉，实时算会误判系统窗，只能靠 drag 期间存的这份）。
        val sz = readDisplaySize()
        val land = sz != null && sz[0] > sz[1]
        val r = computeRoute(pkg, land)
        routeOf[pkg] = r

        // ★ fix74：`Task.onResize` 是「内容尺寸变化」回调，**澎湃失焦重摆也走它**——用户关窗时
        //   澎湃把窗口从用户拖到的位置重摆到「重摆带」（实测 bili/aweme/calculator/gallery 四个
        //   不同 app 全被摆到 x≈302 的同一点，内容宽=屏幕宽 1080），这一帧 geometry 会把 `latest`
        //   里用户真实的停手位置顶掉，关窗 flush 后记进去 → 下次重开位置乱跳（用户报"侧边栏
        //   工具箱打开的小窗位置乱跳"）。
        //   而 `Task.onMovedByResize`（触摸拖动缩放落定）与 `Task.resize`（我们 App 的 am task
        //   resize）才是「用户/我们主动」的几何来源，不含系统重摆。所以这里：`onResize` 只刷新
        //   路由、不 scheduleWrite，把记录交给 resize/onMovedByResize 那几个可信点。
        //
        // ★ fix75：`Task.setBounds` 同样只刷路由、不写。fix71 真机已证实拖动落定走的是
        //   `onResize`/`onMovedByResize`（0 参回调），`setBounds` 的「拖动落定」角色已被取代；
        //   现在 setBounds 触发几乎全是「系统摆默认几何」（侧边栏工具箱/通知打开小窗时系统按
        //   默认几何摆窗，实测 `Task.setBounds calls=67 applied=7`，全是出生/重摆帧，如
        //   `0,672,1080,2400`）。用户「打开→没动→关」侧边栏窗时，`latest` 里唯一的值就是这个
        //   默认几何，关窗 flush 把它当「用户位置」记进 SB → 下次重开位置乱跳（用户报
        //   "侧边栏工具箱小窗记忆没用"）。切断 setBounds 写入后，侧边栏窗「没拖就关」时 latest
        //   为空 → 关窗 flush 什么都不记（正确：用户没动，本就不该记系统默认值）。
        val methodName = (param.method as? java.lang.reflect.Method)?.name
        if (methodName == "onResize" || methodName == "setBounds" || methodName == "resize") {
            lastPkg = pkg
            lastRect = "${b.left},${b.top},${b.right},${b.bottom}"
            // ★ fix124：出生帧上学「系统默认几何」（只在 onResize/setBounds 上学，
            //   Task.resize 是我们 am task resize 的通道，绝不能学）。
            if (isFirstSeen && methodName != "resize") {
                maybeLearnDefault(pkg, Rect(b), sz, land)
            }
            // ★ fix91：**尺寸类回调要能更新「大小」**，否则拉伸过的尺寸永远记不进去。
        //   真机实证：记忆文件里两个不同 App 的大小一模一样（1080x1728，位置却不同）——
        //   说明大小从来不是用户拉伸出来的，只是「上次开窗的下发值」在自我循环，
        //   于是"拉伸过 → 关掉 → 再打开，大小又变回去了"（用户报「大小没恢复」）。
        //   放行条件很窄，三条全满足才写：
        //   ① 不是失焦重摆角（[35,127] 那一族，与 onMovedByResize 同口径过滤）；
        //   ② **已确立过尺寸基准**（[lastSize] 有值）——首次开窗不写，避免把系统默认
        //      几何（侧边栏 `0,672,1080,2400` / 出生帧）当成"用户拉的尺寸"记进去；
        //   ③ 宽高与基准真的不同（> [RESIZE_COORD_TOL] px）——系统重摆通常只改坐标不改
        //      大小，这一条把它们全挡在外面，只有真拉伸才触发。
        //   坐标怎么写仍由 [doWrite] 的 fix80 闸决定（无触摸 → 沿用旧坐标，只更新大小）。
            val corner = b.left in 20..60 && b.top in 100..160
            val key0 = stableKeyFor(r)
            // 基准：优先进程内上次落盘的尺寸；没有（刚开机 / 刚装模块）就退回记忆文件里
            // 已有的尺寸，这样"开机后第一次拉伸"也不会因为没基准而漏记。
            val baseSize = lastSize[key0] ?: readMemoryRect(r.first, key0)
                ?.let { it.width() to it.height() }
            val w = b.width()
            val h = b.height()
            val sizeChanged = baseSize != null && (
                abs(baseSize.first - w) > RESIZE_COORD_TOL ||
                    abs(baseSize.second - h) > RESIZE_COORD_TOL
                )
            // ★ 必须有触摸：系统自己摆窗 / 侧边栏 / banner 展开时尺寸也可能和记忆不同，
            //   但那一刻用户没摸屏幕；只有"用户在摸"才是真拉伸（拖缩放把手）。
            val touched = isTouched(pkg, 2_000L)
            if (!corner && w > 0 && h > 0 && sizeChanged && touched) {
                scheduleWrite(key0, pkg, b, Src.RESIZE, DEBOUNCE_MS)
                stat.skip = "$methodName(size-write ${w}x$h)"
                probeMoved(b, "$methodName size-write ${w}x$h", task)
                return
            }
            stat.skip = "$methodName(skip-write)"
            // ★ fix77 探针：onResize 与 onMovedByResize 是同一帧重摆的两个回调，
            //   序列对照能看出「关闭动效」到底先走哪个、几何怎么漂。
            if (methodName == "onResize") probeMoved(b, "onResize(skip-write)", task)
            return
        }
        // ★ fix77：`onMovedByResize` 混了「系统关闭动效重摆」。fix76b 用 isOnTop 过滤
        //   **已被真机证伪**（按 Home 重摆后 isOnTop 仍返回 true，applied=47=calls=47 一次没拦住），
        //   窗口重摆到带位（如 [775,841][1855,2569] 超屏、[35,127][543,1207] 失焦角）照样被
        //   clamp 后写进 SB → 用户报"不拖动小窗 关闭 位置一直在变"。
        //   本版改两招：
        //   ① **失焦重摆角**过滤（x∈20..60 && y∈100..160，与 App 侧 WindowWatcher
        //      `isLostFocusCornerRect` 同口径，多年实证零误伤）——拦 [35,127] 这一族；
        //   ② **全状态探针**：每一帧 onMovedByResize（含被拦的）把 bounds + isVisible +
        //      isAnimating + isOnTop + Task.toString() dump 进
        //      `/data/system/memoryfreeform_record.state.moved`，并在 removeIfPossible 时打标记。
        //      一次复现就能看清「真拖动帧」与「关闭动效帧」在哪个状态位上分叉，下一版按
        //      真实信号补第二道过滤（超屏重摆带 [775,…] 那一族）。
        if (methodName == "onMovedByResize") {
            val visible = boolOf(task, "isVisible")
            val animating = boolOf(task, "isAnimating")
            val onTop = boolOf(task, "isOnTop")
            val corner = b.left in 20..60 && b.top in 100..160
            probeMoved(b, "corner=$corner vis=$visible anim=$animating top=$onTop", task)
            if (corner) {
                stat.skip = "onMovedByResize(lost-focus-corner ${b.left},${b.top})"
                lastPkg = pkg
                lastRect = "${b.left},${b.top},${b.right},${b.bottom}"
                return
            }
            // ★ fix92：出生安静期 —— 开窗进入动画的逐帧 resize 全走这里（无触摸），
            //   中间帧写记忆 = "没拖过窗、重开位置却跑偏"。动画终点就是记忆位置本身，
            //   这些帧**毫无信息量**，安静期内且用户没在拖 → 直接丢弃（latest 也不进，
            //   免得关窗 flush 时被当成"用户停稳位置"写出去）。用户在拖（有触摸）照常走。
            val seen0 = freeformSeen[pkg]
            if (seen0 != null && System.currentTimeMillis() - seen0.at < BIRTH_QUIET_MS &&
                !isTouched(pkg, 2_000L)
            ) {
                stat.skip = "onMovedByResize(birth-anim)"
                lastPkg = pkg
                lastRect = "${b.left},${b.top},${b.right},${b.bottom}"
                probeMoved(b, "birth-anim drop", task)
                return
            }
            // ★ fix89：通过失焦角过滤 = 用户/我们主动的几何变更 → 本轮关闭时快照可信
            userGeomPkgs.add(pkg)
        }
        // ★★ fix78 → **fix80b：拖动期间（按下~抬起）直接落盘，不再只缓冲**。
        //   原设计「跟指帧只攒缓冲、等 UP 后判定再写」是怕污染记忆，但 fix80 已把
        //   「resize 改坐标」判成无效数据、fix78i 又能在判成「关闭」时用按下快照覆盖自愈，
        //   于是拖动帧可以放心即时写（debounce 80ms 合并连帧）——松手即生效，手感跟手得多。
        //   同时仍更新 `latest`，供 UP 判定与关闭自愈使用。
        barFlags[pkg]?.let { ts ->
            if (System.currentTimeMillis() - ts > BAR_FLAG_TIMEOUT_MS) {
                barFlags.remove(pkg) // 保险丝：UP 丢了最多拦 15s，超时自愈
                barSnapshot.remove(pkg)
            } else {
                latest[stableKeyFor(r)] = pkg to Rect(b)
                stat.skip = "bar-drag"
                lastPkg = pkg
                lastRect = "${b.left},${b.top},${b.right},${b.bottom}"
                probeMoved(b, "bar-drag write", task)
                scheduleWrite(stableKeyFor(r), pkg, b, Src.MOVE)
                return
            }
        }
        // ★ fix78g/h：UP 后待判期（barRecent）—— 动效/惯性帧**继续只缓冲不落盘**
        //   （旧版 UP 即清标记，动效帧绕过拦截直接落盘 = "没移动小窗、重开位置却变了"）。
        //   fix78h：flush 终帧后 barRecent 也**不清**，留到 remove（慢关闭自愈）或 4s 超时。
        barRecent[pkg]?.let { ts ->
            if (System.currentTimeMillis() - ts > BAR_PENDING_TIMEOUT_MS) {
                barRecent.remove(pkg) // 保险丝：remove 永不来（如挂起成气泡）最多拦 4s
                barSnapshot.remove(pkg)
            } else {
                latest[stableKeyFor(r)] = pkg to Rect(b)
                stat.skip = "bar-pending"
                lastPkg = pkg
                lastRect = "${b.left},${b.top},${b.right},${b.bottom}"
                probeMoved(b, "bar-pending buffered", task)
                return
            }
        }
        // ★ fix80：来源判定 —— 只有「触摸拖动落定」是 MOVE；`Task.resize`（我们 App 的
        //   am task resize / MIUI 缩放把手）是尺寸类，走 fix80 的「不改坐标」保护。
        val src = if (methodName == "onMovedByResize") Src.MOVE else Src.RESIZE
        // ★★ fix90：用户**没在摸屏幕**时进来的帧（关闭动效重摆 / 系统重摆）用长 debounce。
        //   真机实证这类帧 80ms 就落盘、"窗口退出 freeform"来不及取消它；拉长到
        //   [IDLE_DEBOUNCE_MS] 后，关窗信号（几十毫秒内必到）能稳稳把它掐掉。
        //   用户在摸（[lastTouchTs] 2 秒内）则维持 80ms，拖动照样跟手。
        val touched = isTouched(pkg, 2_000L)
        scheduleWrite(stableKeyFor(r), pkg, b, src, if (touched) DEBOUNCE_MS else IDLE_DEBOUNCE_MS)
        lastPkg = pkg
        lastRect = "${b.left},${b.top},${b.right},${b.bottom}"
    }

    private fun onRemove(stat: Stat, param: XC_MethodHook.MethodHookParam) {
        seen.incrementAndGet()
        stat.calls.incrementAndGet()
        val task = param.thisObject
        if (task?.javaClass?.name != "com.android.server.wm.Task") {
            stat.skip = "not-task"
            return
        }
        // ★ fix71：先尽力取包名、抓走 drag 期间存的路由（onResize 每帧刷新的），再清掉它。
        //   顺序必须是「先取后清」：关窗时 App 已删 SESSION，实时 computeRoute 会把我们的窗
        //   误判成系统窗（写 SB）；只有 drag 期间存下的那份路由（那时 SESSION 还在）是对的。
        //   而「哪怕窗口已退 freeform 也要清」是为了不让路由残留到下一个同包窗口。
        val pkg = pkgOfTask(task)
        val cachedRoute = pkg?.let { routeOf[it] }
        // ★ fix78f：拖动期/缓冲期内关窗 → 丢弃跟指缓冲，**写入按下时快照的停稳位置**
        //   （fix78e 前是纯丢弃，依赖 DOWN 时已 flush；fix78f 快照只进内存，这里补落盘）。
        if (pkg != null && (barFlags.remove(pkg) != null || barRecent.remove(pkg) != null)) {
            // ★ fix89：可信信号先取走（本轮用户拖过 = remove 前有过 onMovedByResize）。
            val trusted = userGeomPkgs.remove(pkg)
            routeOf.remove(pkg)
            freeformSeen.remove(pkg)
            cachedRoute?.let {
                val rk = stableKeyFor(it)
                runnables.remove(rk)?.let { r -> writeHandler.removeCallbacks(r) }
                latest.remove(rk)
            }
            stat.skip = "bar-close-drag"
            val snap = barSnapshot.remove(pkg)
            if (snap != null) {
                val sz = readDisplaySize()
                val land = sz != null && sz[0] > sz[1]
                val r2 = cachedRoute ?: computeRoute(pkg, land)
                routeOf[pkg] = r2
                // ★ fix89：与 writeSnapshotAsClose 同一道闸 —— 系统开的窗用户没拖过，
                //   按下快照只是系统默认几何，不许顶掉原记忆。
                if (trusted || snapMatchesMemory(r2, Rect(snap)) || isOursSession(pkg)) {
                    flushNow(stableKeyFor(r2), pkg, Rect(snap), r2)
                    probeMark("REMOVE bar-close 写停稳位置 pkg=$pkg " +
                        "记=${snap.left},${snap.top},${snap.right},${snap.bottom}")
                    log("拖动关窗 $pkg：记停稳位置 ${snap.left},${snap.top},${snap.right},${snap.bottom}，跟指帧全部丢弃")
                } else {
                    probeMark("REMOVE bar-close(系统窗未动·不写) pkg=$pkg " +
                        "拒=${snap.left},${snap.top},${snap.right},${snap.bottom}")
                    log("拖动关窗 $pkg：系统开的窗且用户没拖过，丢弃系统默认快照，保留原记忆")
                }
            } else {
                probeMark("REMOVE bar-close 无快照 pkg=$pkg")
            }
            return
        }
        if (pkg != null) { userGeomPkgs.remove(pkg); routeOf.remove(pkg) }
        // ★ fix78：窗口没了，DOWN 判定缓存与手势条标记一并清掉。
        if (pkg != null) { freeformSeen.remove(pkg); barSnapshot.remove(pkg) }
        // ★ fix77 探针：remove 是「关闭动效」的终点标记，与 moved 序列对照看时序。
        runCatching { probeMark("REMOVE pkg=$pkg cached=${latest["${cachedRoute?.second}"]?.second}") }

        // ★ fix73：关窗是「必须记」的关键事件，**不能因为窗口已退出 freeform 就整个跳过**。
        //   真机 `record.state`：`removeIfPossible calls=6 applied=2 skip=not-freeform` —— 澎湃在
        //   remove 前先把窗口退出 freeform（`isFreeformTask` 已 false），于是旧逻辑在这就 return，
        //   「拖完立刻关窗」那一帧（debounce 200ms 还没到）彻底漏掉 = 用户报"拖完立刻关窗记不上"。
        //   修法：优先读「drag 期间 onMovedByResize 攒进 latest 的最后一帧几何」flush（那份是用户
        //   停手的位置，关窗那一刻的 bounds 反而是退出动画 / 重摆帧，不如 latest 准）。
        //
        // ★ fix75：**移除「latest 空时退回读当前 boundsOf」的兜底分支**。fix74 起 onResize/setBounds
        //   都 skip-write，latest 只由 onMovedByResize（用户触摸拖）/ Task.resize（我们 App 的
        //   am task resize）填充。latest 为空 = 用户**从没拖过**这个窗 → 当前 boundsOf 读到的必是
        //   系统摆的默认几何（`0,672,1080,2400` 或失焦重摆带 `302,960,1382,2688`，内容宽恒 == 屏幕宽
        //   1080），把它 flush 进 SB 文件就是「把系统默认几何当用户位置」→ 下次重开位置乱跳
        //   （用户报"侧边栏工具箱小窗记忆没用"）。用户没动，本就不该记 —— 直接跳过。
        if (pkg == null) { stat.skip = "no-pkg"; return }
        val sz = readDisplaySize()
        val land = sz != null && sz[0] > sz[1]
        val r = cachedRoute ?: computeRoute(pkg, land)
        val key = stableKeyFor(r)

        val cached = latest[key]?.second
        if (cached == null) {
            // 用户从没拖过（latest 空）：清掉可能还在排队的 debounce，什么都不记。
            runnables.remove(key)?.let { writeHandler.removeCallbacks(it) }
            latest.remove(key)
            stat.skip = "no-drag"
            lastTouchTs.remove(pkg)
            return
        }
        // ★★ fix90：关窗时若**近期没人摸过屏幕**，`latest` 里这份必是系统重摆 / 关闭动效帧
        //   （真机实证：横幅开窗未移动即关闭，flush 出来的就是重摆位置 386,605），
        //   原样落盘 = 用系统几何顶掉用户记忆。没摸过就取消待写、什么都不记。
        if (!isTouched(pkg, 5_000L)) {
            runnables.remove(key)?.let { writeHandler.removeCallbacks(it) }
            latest.remove(key)
            stat.skip = "no-touch(系统帧·不写)"
            lastTouchTs.remove(pkg)
            probeMark("REMOVE 无触摸·不写 pkg=$pkg 丢弃=${cached.left},${cached.top},${cached.right},${cached.bottom}")
            log("关窗 $pkg：最近没有触摸，丢弃系统帧 ${cached.left},${cached.top},${cached.right},${cached.bottom}")
            return
        }
        lastTouchTs.remove(pkg)
        stat.applied.incrementAndGet()
        // ★ fix69b/fix71：销毁前立刻落盘，路由复用 drag 期间存的（cachedRoute），
        //   没有才现算兜底（首事件就是 remove 的极端情况）。
        flushNow(key, pkg, cached, r)
        lastPkg = pkg
        lastRect = "${cached.left},${cached.top},${cached.right},${cached.bottom}"
    }

    // ---------------------------------------------------------------- 手势条触摸检测（fix78）

    /**
     * ★ fix78：懒注册全局 pointer 监听。fix78d 自适应版：不猜类名/方法名 ——
     *  ① 注册目标 = `LocalServices → WindowManagerInternal`（兜底 `Task.mAtmService.mWindowManager`）；
     *  ② 在目标类上搜「register × 参数类型名为 PointerEventListener」的方法，按**真实参数类型** Proxy；
     *  ③ 失败则把目标类 + MIUI freeform 手势类候选的相关签名 dump 进
     *     `memoryfreeform_record.state.wm`（fix78c 实证：本 ROM 没有 `android.view.PointerEventListener`）。
     *  回调里只做轻量几何判定，IO 全在 writeHandler 线程。
     */
    private fun ensurePointerListener(task: Any?) {
        if (pointerListener != null) return
        synchronized(this) {
            if (pointerListener != null) return
            pointerRegTried++
            if (pointerRegTried > 300) return // 别无限烧：注册一直失败就放弃（自检里能看到）
            // ★ fix78b：**必须用 lpparam.classLoader**（system_server 自己的加载器）解析
            //   com.android.server.*——hook 模块自身的 classloader 看不见 services.jar，
            //   Class.forName("com.android.server.LocalServices") 必挂（真机 plisten=0 实证）。
            // ★ fix78c：`android.view.PointerEventListener` 在本 ROM 上**不存在**（err=itf-CNFE）。
            // ★ fix78d：**不再猜任何名字** —— ① 先拿到注册目标对象（LocalServices→
            //   WindowManagerInternal，兜底 Task.mAtmService.mWindowManager=WMS）；
            //   ② 在目标类上搜「register×PointerEventListener」方法，**参数类型就是真实监听接口**，
            //   Proxy 按它实现；③ 找不到就把目标类 + MIUI freeform 手势类候选的全部相关方法
            //   签名 dump 进 `.wm` 探针文件，一次重启看清这台 ROM 的真实 API。
            val cl = appClassLoader
            // ★ fix78e：目标对象列表 —— LocalService 本身没有 registerPointerEventListener，
            //   它在 **WMS 本体**上（真机探针实证）；且 Android 16 接口已搬进
            //   `android.view.WindowManagerPolicyConstants$PointerEventListener`。
            //   所以把 LocalService、this$0（WMS）、Task.mAtmService.mWindowManager 全收集起来挨个搜。
            val targets = ArrayList<Any>(3)
            runCatching {
                val lsCls = Class.forName("com.android.server.LocalServices", true, cl)
                val svc = lsCls.getMethod("getService", Class::class.java).invoke(
                    null, Class.forName("com.android.server.wm.WindowManagerInternal", true, cl)
                )
                if (svc != null) targets.add(svc)
            }.getOrElse { pointerErr = "ls:${it.message?.take(60)}" }
            targets.firstOrNull()?.let {
                runCatching { XposedHelpers.getObjectField(it, "this\$0") }
                    .getOrNull()?.let { wms -> targets.add(wms) }
            }
            runCatching {
                val atms = XposedHelpers.getObjectField(task ?: return@runCatching, "mAtmService")
                XposedHelpers.getObjectField(atms, "mWindowManager")?.let { targets.add(it) }
            }
            if (targets.isEmpty()) { pointerErr = "no-target"; probePointerApis(null); return }
            // 在所有目标上搜「register × 首参类型名为 PointerEventListener」的方法
            var regM: java.lang.reflect.Method? = null
            var regTarget: Any? = null
            outer@ for (t in targets) {
                for (m in t.javaClass.methods) {
                    if (!m.name.lowercase().contains("register")) continue
                    if (m.parameterTypes.isEmpty()) continue
                    if (m.parameterTypes[0].simpleName != "PointerEventListener") continue
                    if (m.parameterTypes.size > 2) continue
                    regM = m; regTarget = t
                    break@outer
                }
            }
            if (regM == null || regTarget == null) {
                pointerErr = "no-reg-api"
                probePointerApis(targets.last())
                return
            }
            val itf = regM.parameterTypes[0]
            if (!itf.isInterface) {
                pointerErr = "not-iface:${itf.name.take(80)}"
                probePointerApis(regTarget)
                return
            }
            val listener = runCatching {
                // ★ fix78e：Proxy handler 必须正确应答 equals/hashCode/toString —— 注册时
                //   PointerEventDispatcher 会 mListeners.contains(listener) → equals 走到
                //   这里返回 null 会拆箱 NPE（真机 err=reg:null 实证，注册永远失败）。
                Proxy.newProxyInstance(itf.classLoader ?: cl, arrayOf(itf)) { proxy, method, args ->
                    if (method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == MotionEvent::class.java
                    ) {
                        val ev = args?.firstOrNull() as? MotionEvent
                        if (ev != null) runCatching { onPointerEvent(ev) }
                        null
                    } else when (method.name) {
                        "equals" -> args?.firstOrNull() === proxy
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "SingleHandPointerListener"
                        else -> null
                    }
                }
            }.getOrElse { pointerErr = "proxy:${it.message?.take(60)}"; return }
            val ok = runCatching {
                regM.isAccessible = true
                if (regM.parameterTypes.size == 1) regM.invoke(regTarget, listener)
                else regM.invoke(regTarget, listener, 0) // (listener, displayId=0)
                true
            }.getOrElse {
                val c = it.cause
                pointerErr = "reg:" + (c?.let { e -> "${e.javaClass.simpleName}:${e.message ?: ""}" }
                    ?: it.toString()).take(80)
                false
            }
            if (ok) {
                pointerListener = listener
                pointerErr = ""
                log("已注册 PointerEventListener（手势条触摸检测生效，" +
                    "api=${regTarget.javaClass.simpleName}.${regM.name}/${regM.parameterTypes.size}p）")
            } else if (pointerRegTried % 50 == 0) {
                log("PointerEventListener 注册失败（$pointerRegTried 次）：$pointerErr")
            }
        }
    }

    /**
     * ★ fix78d 探针：把「这台 ROM 上 pointer/触摸注册的真实 API」dump 进
     * `memoryfreeform_record.state.wm`。目标对象为空时改扫 MIUI freeform 手势类候选。
     * 内容不变不重写，避免烧 IO。
     */
    private var lastProbeDump = ""
    private fun probePointerApis(target: Any?) {
        runCatching {
            val sb = StringBuilder()
            if (target != null) {
                sb.append("== ${target.javaClass.name} ==\n")
                for (m in target.javaClass.methods.sortedBy { it.name }) {
                    val n = m.name
                    if (n.contains("Pointer") || n.contains("pointer") ||
                        (n.startsWith("register") && m.parameterTypes.any {
                            it.simpleName.contains("Listener")
                        })
                    ) {
                        sb.append("$n(${m.parameterTypes.joinToString(",") { it.name }})\n")
                    }
                }
                // WMS 本体（LocalService 的 this$0）与 InputManagerService 也扫一遍
                val wms = runCatching { XposedHelpers.getObjectField(target, "this\$0") }.getOrNull()
                if (wms != null) {
                    sb.append("== ${wms.javaClass.name} ==\n")
                    for (m in wms.javaClass.methods.sortedBy { it.name }) {
                        if (m.name.contains("Pointer")) {
                            sb.append("${m.name}(${m.parameterTypes.joinToString(",") { it.name }})\n")
                        }
                    }
                    val ims = runCatching { XposedHelpers.getObjectField(wms, "mInputManager") }.getOrNull()
                    if (ims != null) {
                        sb.append("== ${ims.javaClass.name} ==\n")
                        for (m in ims.javaClass.methods.sortedBy { it.name }) {
                            if (m.name.contains("Pointer")) {
                                sb.append("${m.name}(${m.parameterTypes.joinToString(",") { it.name }})\n")
                            }
                        }
                    }
                }
            }
            // ★ fix87：原「MIUI 手势类候选 dump」已随手势类探针一起删除（hooked=0，钩不上）。
            val dump = sb.toString()
            if (dump == lastProbeDump) return
            lastProbeDump = dump
            val f = File(HookContract.RECORD_STATE_PATH + ".wm")
            f.writeText(dump)
            runCatching { f.setReadable(true, false) }
        }
    }

    /**
     * ★ fix78 触摸处理。**fix78f：彻底不依赖坐标系** —— 真机实证 pointer 事件的坐标空间
     * 与 `Task.getBounds()`（未缩放逻辑坐标）差 0.70 图层缩放（且锚点不明），条带判定
     * 不可靠。而小窗几何变化**只能由拖动条发起**，拖动必然以自己的 DOWN 开头，所以：
     *  - **ACTION_DOWN**：只要有活的 freeform 小窗（首次，putIfAbsent 防双指覆盖快照）→
     *    ① 快照当前 bounds 进 `barSnapshot`（拖动前的停稳位置）② 置 `barFlags[pkg]`；
     *  - 标记期内 resize 帧只进 `latest` 缓冲**不落盘**；
     *  - **ACTION_UP/CANCEL**：清 `barFlags` 转 `barRecent`（**拦截不解除**，fix78g），
     *    两次采样判定（见 [barUpJudge]，fix78i：看 `getWindowingMode()` 是否仍 freeform）
     *    —— 仍 freeform = 移动 flush 终帧；已退出 freeform = 关闭，写 `barSnapshot`
     *    （按下停稳位置）、跟指/动效缓冲全部丢弃。真 remove 则由 onRemove 的 bar-close
     *    分支处理（同一份快照）。
     */
    private fun onPointerEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (barFlags.isNotEmpty()) {
                    barUps.incrementAndGet()
                    probeMark("BAR-UP 开始 ${barUps.get()}")
                    for ((pkg, ts) in barFlags) {
                        barFlags.remove(pkg, ts)
                        val upTs = System.currentTimeMillis()
                        barRecent[pkg] = upTs
                        barUpDecide(pkg, upTs)
                    }
                }
            }
            MotionEvent.ACTION_DOWN -> {
                barDowns.incrementAndGet()
                // ★ fix90 探针：DOWN **无条件**留痕（原先只在命中活窗时才记，一旦活窗登记
                //   不上就完全没线索）。带 freeformSeen 当前内容，下次复现直接看清
                //   "按下时到底有没有活窗可快照"。
                probeMark("BAR-DOWN seen=${freeformSeen.entries.joinToString("|") {
                    "${it.key}:${if (isFreeformTask(it.value.task)) "ff" else "no-ff"}" }}")
                for ((pkg, s) in freeformSeen) {
                    if (!isFreeformTask(s.task)) continue
                    val b = boundsOf(s.task) ?: continue
                    // ★ fix90：这个包刚被摸过 —— 之后几百毫秒内的几何变化按"用户弄的"处理。
                    lastTouchTs[pkg] = System.currentTimeMillis()
                    // ★ fix78i：新手势开始 → 上一轮还没出结论的判定作废（令牌失效），
                    //   避免"关窗后又立刻重开"时旧判定把旧快照盖到新窗口上。
                    barRecent.remove(pkg)
                    // ★ 同时清掉上一轮攒的陈旧缓冲帧：否则"关闭动效帧"会赖在 latest 里，
                    //   被下一次轻点（无位移、无新帧）的判定当成终帧写进记忆 = 位置又漂。
                    routeOf[pkg]?.let { r ->
                        val k = stableKeyFor(r)
                        runnables.remove(k)?.let { writeHandler.removeCallbacks(it) }
                        latest.remove(k)
                    }
                    // putIfAbsent：双指/同链路重复 DOWN 不覆盖第一份快照（第一份才是停稳位置）
                    if (barFlags.putIfAbsent(pkg, System.currentTimeMillis()) == null) {
                        barSnapshot[pkg] = Rect(b)
                        barHits.incrementAndGet()
                        probeMark("BAR-DOWN snapshot pkg=$pkg 记=${b.left},${b.top},${b.right},${b.bottom}")
                        log("按下快照 $pkg：${b.left},${b.top},${b.right},${b.bottom}，拖动期间只缓冲不落盘")
                    }
                }
            }
        }
    }

    /**
     * ★★ fix78i：UP 后的判定 —— **看「窗口还活不活在 freeform」，两次采样防误判**。
     *
     * 前两版为什么都错（真机实证）：
     *  - fix78g 用 `isVisible`/`isFreeformTask` 判「关闭动效」→ 小窗失焦后这两个值就翻
     *    false（窗明明活着）→ 所有手势被判关闭、拦到超时全丢 = "完全不记忆"；
     *  - fix78h 改成「只看有没有 remove」→ **澎湃关小窗根本不 remove Task**：实测关闭后
     *    `Task{…com.tencent.mm visible=false mode=fullscreen}` —— 窗口是**退出 freeform
     *    变回全屏**并隐藏（`removeIfPossible calls=2` 几乎不触发）⇒ bar-close 自愈链永不
     *    执行 ⇒ 600ms 那次 flush 把"拖下去关窗"的最后一帧写进记忆 = 用户报
     *    "坐标记录到了最小化后的位置"。
     *
     * 修法：判定信号换成 `getWindowingMode()`（5=freeform，关闭后=1/fullscreen），
     * 并且**采样两次**（+[BAR_UP_JUDGE_DELAY_MS]、再 +[BAR_UP_RECHECK_DELAY_MS]）才下结论：
     *  - A 仍 freeform → 先按「移动」flush 缓冲终帧；B 若已非 freeform（慢关闭）→
     *    用按下快照**覆盖自愈**；
     *  - A 已非 freeform → **当场**丢弃跟指/动效缓冲、**写入按下快照**（fix80b：不再等 B，
     *    见代码内注释 —— 拖动期已即时落盘，等 B 会被新手势作废导致跟指帧残留）；
     *  - B 仍活 = 移动确认；B 又活但 A 曾判死 = **关掉后立刻重开**，此时快照已写过，
     *    什么都不做（latest 里是上一轮跟指帧，绝不能再写）。
     * `barRecent` 自带超时保险（[BAR_PENDING_TIMEOUT_MS]，须大于两次采样之和）。
     */
    private fun barUpDecide(pkg: String, upTs: Long) {
        writeHandler.postDelayed({ barUpJudge(pkg, upTs, 0) }, BAR_UP_JUDGE_DELAY_MS)
    }

    private fun barUpJudge(pkg: String, upTs: Long, attempt: Int, firstAlive: Boolean = true) {
        // ★ 令牌校验：判定期间用户又做了新手势（barRecent 被新 UP 覆盖）→ 本次判定作废
        if (barRecent[pkg] != upTs) return
        val s = freeformSeen[pkg] ?: return // 已 remove：bar-close 分支已处理
        val mode = runCatching { XposedHelpers.callMethod(s.task, "getWindowingMode") as? Int }
            .getOrNull()
        val alive = mode == null || mode == FREEFORM_MODE // 读不到就按"活着"保守处理
        probeMark("BAR-UP 采样#$attempt pkg=$pkg mode=$mode alive=$alive")
        if (attempt == 0) {
            if (!alive) {
                // ★ fix80b：**当场覆盖**。拖动期已即时落盘（fix80b），采样到 mode≠freeform
                //   = 关闭 → 必须**立刻**用按下快照盖掉刚写进去的跟指帧，不能等 #1：
                //   等 #1 的话中间任何一次新手势都会把判定令牌作废，跟指帧就永久残留了。
                //   万一误判（其实只是失焦）代价也小：写的是"手势前位置"，不会乱跳。
                writeSnapshotAsClose(pkg)
                return
            }
            flushTerminalAsMove(pkg) // 移动：兜底再落一次终帧
            writeHandler.postDelayed(
                { barUpJudge(pkg, upTs, 1, alive) }, BAR_UP_RECHECK_DELAY_MS
            )
            return
        }
        // 第二次采样：下最终结论
        if (!alive) {
            writeSnapshotAsClose(pkg) // 关闭（含"#0 判活、#1 才退 freeform"的慢关闭自愈）
            return
        }
        if (!firstAlive) {
            // ★ #0 已看到"退出 freeform"、#1 却又活了 = **关掉后 2.5s 内重开**了窗口。
            //   此时 latest 里躺的是上一轮的跟指/动效帧，绝不能当新位置写（真机实证：
            //   这条分支会把关窗前那帧写进记忆 = 位置乱漂）。什么都不写，交给新手势。
            barRecent.remove(pkg)
            barSnapshot.remove(pkg)
            probeMark("BAR-UP 结论=关闭后重开(不写) pkg=$pkg")
            return
        }
        barRecent.remove(pkg)
        barSnapshot.remove(pkg)
        probeMark("BAR-UP 结论=移动 pkg=$pkg")
    }

    /** 「移动」落盘：flush 缓冲里的最后一帧（系统实际摆定后的几何）。 */
    private fun flushTerminalAsMove(pkg: String) {
        val route = routeOf[pkg] ?: return
        val key = stableKeyFor(route)
        latest[key]?.let {
            flushNow(key, pkg, Rect(it.second), route)
            probeMark("BAR-UP flush(移动) pkg=$pkg " +
                "记=${it.second.left},${it.second.top},${it.second.right},${it.second.bottom}")
            log("手势条抬起 $pkg：窗仍 freeform，按移动落盘 ${it.second}")
        }
    }

    /**
     * 「关闭」落盘：窗口已退出 freeform（澎湃关小窗 = mode→fullscreen + 隐藏，不 remove）。
     * 丢弃拖动/动效攒下的全部缓冲帧，**写入按下时的停稳快照** —— 用户"没移动过却记到
     * 最小化位置"的元凶就是这些帧。
     */
    private fun writeSnapshotAsClose(pkg: String) {
        // ★ fix89：先把「本轮用户拖过」这个信号取走（remove 即读取），再走清理。
        val trusted = userGeomPkgs.remove(pkg)
        barRecent.remove(pkg)
        freeformSeen.remove(pkg)
        val snap = barSnapshot.remove(pkg)
        val route = routeOf[pkg] ?: return
        val key = stableKeyFor(route)
        runnables.remove(key)?.let { writeHandler.removeCallbacks(it) }
        latest.remove(key)
        if (snap == null) {
            probeMark("BAR-UP 结论=关闭 pkg=$pkg 无快照（不写）")
            return
        }
        // ★★ fix89：**系统开的小窗，没拖过就不许写快照**。用户报：从微信消息横幅打开的
        //   小窗，没移动直接手势关闭，记忆位置出错。根因（真机复现）：横幅起的小窗不走
        //   我们的出生钩子，几何是系统默认值（如 286,714,794,1794）；"关闭=写按下快照"
        //   把这份默认几何当成用户位置写进记忆，顶掉了原来的正确值。我们 App 自己开的
        //   窗没事 —— 快照=我们下发的位置，写了等于幂等。
        //   只有三种可信情况才写：① 本轮用户真拖过（onMovedByResize 出现过）；
        //   ② 快照≈记忆原值（写了也不改变现状，幂等）；③ 是我们 App 开的窗（会话文件命中）。
        if (!trusted && !snapMatchesMemory(route, snap) && !isOursSession(pkg)) {
            probeMark("BAR-UP 结论=关闭(系统窗未动·不写) pkg=$pkg " +
                "拒=${snap.left},${snap.top},${snap.right},${snap.bottom}")
            log("手势关闭 $pkg：系统开的窗且用户没拖过，丢弃系统默认快照，保留原记忆")
            return
        }
        flushNow(key, pkg, Rect(snap), route)
        probeMark("BAR-UP 结论=关闭 写按下快照 pkg=$pkg " +
            "记=${snap.left},${snap.top},${snap.right},${snap.bottom}")
        log("手势关闭 $pkg：窗已退 freeform，记手势前停稳位置 " +
            "${snap.left},${snap.top},${snap.right},${snap.bottom}，跟指帧全部丢弃")
    }

    /** ★ fix89：快照是否与记忆原值一致（±8px）—— 一致则写=幂等，无害放行。 */
    private fun snapMatchesMemory(route: Pair<String, String>, snap: Rect): Boolean {
        val mem = readMemoryRect(route.first, route.second) ?: return false
        return abs(snap.left - mem.left) <= RESIZE_COORD_TOL &&
            abs(snap.top - mem.top) <= RESIZE_COORD_TOL
    }

    /**
     * ★ fix89：会话文件（App 开窗脚本写）是否指向 pkg 且新鲜（10 分钟内）→ 这个窗是
     * 「我们 App 开的」。手势关闭不走 App 关窗流程（那是先删会话再关窗），所以手势
     * 关闭时会话文件还在。包名匹配 + 新鲜度即可，不必对 taskId。
     */
    private fun isOursSession(pkg: String): Boolean = runCatching {
        val f = File(HookContract.SESSION_PATH)
        if (!f.exists()) return false
        var p = ""
        var ts = 0L
        for (tok in f.readText().trim().split(Regex("\\s+"))) {
            when {
                tok.startsWith("pkg=") -> p = tok.substring(4)
                tok.startsWith("ts=") -> ts = tok.substring(3).toLongOrNull() ?: 0L
            }
        }
        p == pkg && ts > 0 && System.currentTimeMillis() - ts < 10 * 60_000L
    }.getOrDefault(false)

    // ---------------------------------------------------------------- debounce 写盘

    /** resize 走这里：最后一次变化后 delayMs 才落盘。键是稳定键（包名+方向），见 [stableKeyFor]。 */
    private fun scheduleWrite(key: String, pkg: String, rect: Rect, src: Src, delayMs: Long = DEBOUNCE_MS) {
        latest[key] = pkg to rect
        runnables.remove(key)?.let { writeHandler.removeCallbacks(it) }
        val r = Runnable {
            runnables.remove(key)
            val v = latest.remove(key) ?: return@Runnable
            doWrite(v.first, v.second, null, src)
        }
        runnables[key] = r
        writeHandler.postDelayed(r, delayMs)
    }

    /** remove 走这里：马上落盘，不等 debounce。 */
    private fun flushNow(
        key: String, pkg: String, rect: Rect, route: Pair<String, String>? = null, src: Src = Src.MOVE
    ) {
        runnables.remove(key)?.let { writeHandler.removeCallbacks(it) }
        latest.remove(key)
        writeHandler.post { doWrite(pkg, rect, route, src) }
    }

    /**
     * 真正写记忆。所有调用都在 [writeHandler] 线程上 → 串行，不会与 App 进程抢文件。
     *
     * 落盘前按当前屏幕**收边 + 拒绝屏外**（方向自洽，绝不跨方向回退），与出生钩子 /
     * App 侧 `record()` 同口径。拿不到屏幕就原样写（总比不记好）。
     *
     * @param route 显式路由（path+key）。关窗 flush 时由 [onRemove] 算好传进来，绕过已被
     *   App 删除的会话文件；不传则复用 / 现算（drag 期间首次写）。
     * @param src   写入来源（[Src.MOVE] 移动 / [Src.RESIZE] 尺寸类），决定要不要走 fix80 的坐标保护。
     */
    private fun doWrite(
        pkg: String, rect: Rect, route: Pair<String, String>? = null, src: Src = Src.MOVE
    ) {
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        if (w < MIN_SIDE || h < MIN_SIDE) return
        val size = readDisplaySize()
        // 落盘前**收边到屏幕内容坐标系内的最大/最小位置**（保持宽高，只平移位置），
        // 拿不到屏幕就原样写（总比不记好）。
        val out: Rect
        if (size != null) {
            val (sw, sh) = size
            // ★ fix76：超屏不再「拒绝」（用户报"拖动没记录"）也不「原样记超屏值」，而是**收边**：
            //   fix42 实测 freeform 窗口正坐标超界被系统原样接受（right 到 3000 都生效，超出部分
            //   系统裁掉），但「记忆」要的是可复用的合法位置——用户把窗拖到屏幕边缘外，记住的
            //   应是「贴屏幕边」的位置（右/下超屏收到屏幕边界、左/上超屏收到 0），而不是一个
            //   每次重开都要被系统裁掉一截的超屏值。fix69 用「屏幕/0.70=1542」直接**拒绝**超屏
            //   （真机 last skip=off-screen(610,495,1690,2223)）才是"拖动没记录"的真因。
            //   只拒绝荒谬值（正常拖不到屏幕 4 倍之外 / 负 2 倍之外的垃圾帧）。
            //   ★ fix76 曾在此删除 fix74 的 exit-anim 判据（理由是"fix75 起写入来源只剩
            //   onMovedByResize / Task.resize，退出动画帧不再进 latest"）—— **该理由已被
            //   fix77 探针证伪**：退出动效的全屏帧 `0,0,1080,2400` 确实存在且成批出现，
            //   只是本批恰好走了 onResize（skip-write）才没污染。判据在 ★ fix77b 处恢复。
            if (rect.left < -sw * 2 || rect.top < -sh * 2 || rect.right > sw * 4 || rect.bottom > sh * 4) {
                lastSkip = "$pkg:absurd(${rect.left},${rect.top},${rect.right},${rect.bottom})"
                log("记忆拒绝 $pkg：荒谬几何 $rect，保留上一次记忆")
                return
            }
            // ★ fix77b：**拒绝「关闭 / 退出动效的全屏帧」**（App 侧 WindowWatcher.sane() 同款
            //   判据 `w >= sw-40 && h >= sh-40`，那里拦了多年）。
            //   真机探针（memoryfreeform_record.state.moved）抓到一批 `0,0,1080,2400` 的帧：
            //   它是关闭小窗时系统把窗放大到全屏的**退出动效**状态，不是用户的摆放意图。
            //   本批它走的是 `onResize`（fix74 起 skip-write）侥幸没污染记忆；可一旦它从
            //   `onMovedByResize` 进来就畅通无阻 —— fix76 的 doWrite 里**没有任何全屏判据**
            //   （当时误判"退出动画帧不再进 latest"把它当死代码删了），于是 `0,0,1080,2400`
            //   会被 clamp 成 `0,0,1080,2400` 原样落盘（1080<1542、2400<3428，全在内容边界内），
            //   下次重开就是一开出来接近全屏 —— 正是用户说的"关闭动效导致的位置偏移"。
            //   注意：这里与 w/h 比较的是**逻辑屏**（1080x2400）而非 sw/0.70 —— 这条全屏帧的
            //   bounds 本来就等于逻辑屏。
            if (w >= sw - 40 && h >= sh - 40) {
                lastSkip = "$pkg:exit-anim-fullscreen(${rect.left},${rect.top},${rect.right},${rect.bottom})"
                log("记忆拒绝 $pkg：退出动效全屏帧 $rect，保留上一次记忆")
                return
            }
            // ★ fix86：**横屏顶部让出状态栏**。横屏屏高只有 1080，设置高度被收边到
            //   "满屏高"之后 top 会算成 0，小窗顶边贴屏幕最上沿，澎湃画在窗口顶部的
            //   **移动手势条**正好被压在状态栏上（用户报的现象）。记忆里的 top 至少要
            //   停在状态栏下沿，App 侧重开时（WindowSizing.clampRect(minTop)）才不会再
            //   出现"一开出来手势条就在状态栏上"。竖屏不动（屏高富余，且改了会影响
            //   习惯了顶天立地大窗的摆法）。
            val topSafe = if (sw > sh) statusBarHeightSystem() else 0
            val maxRight = (sw / 0.70f).toInt()
            val maxBottom = (sh / 0.70f).toInt()
            val cw = w.coerceAtMost(maxRight)
            val ch = h.coerceAtMost(maxBottom)
            val l = rect.left.coerceIn(0, (maxRight - cw).coerceAtLeast(0))
            val t = rect.top.coerceIn(topSafe, (maxBottom - ch).coerceAtLeast(topSafe))
            out = Rect(l, t, l + cw, t + ch)
            lastSkip = if (out != rect)
                "$pkg:clamp(${rect.left},${rect.top},${rect.right},${rect.bottom}->${out.left},${out.top},${out.right},${out.bottom})"
            else "-"
        } else {
            out = rect
        }
        val landscape = size != null && size[0] > size[1]
        // ★ fix69b：路由优先用显式传入的（关窗 flush），否则复用本次生命周期 onResize 已
        //   确立的那一份（drag 期间算过）；都没有（首事件 / 窗口已关）才现算兜底。
        //   ★ fix79：路由已统一（永远主文件 + 方向键），不再有"错写 SB"的可能。
        val r = route ?: (routeOf[pkg] ?: computeRoute(pkg, landscape))
        routeOf[pkg] = r
        val (path, key) = r
        // ★★ fix80：**resize 不改坐标**。resize（我们 App 的 `am task resize` / MIUI 缩放把手 /
        //   onResize / setBounds）按定义只改**尺寸**；这类帧若把记忆坐标（left/top）改了，
        //   必是系统重摆 / 缩放补偿 / 关闭动效混进来的**无效数据**（真机反复出现"没移动小窗、
        //   重开位置却变了"，根因就是这些帧顶掉了用户真位置）。
        //   处理：坐标部分一律沿用记忆里的旧值，**只采用新的宽高**；
        //   记忆里还没有这个键（首次开窗）时放行，否则永远记不上初始值。
        var out2 = out
        if (src == Src.RESIZE) {
            val old = readMemoryRect(path, key)
            // ★ fix91：**用户在摸屏幕**时（2 秒内有触摸）坐标照单全收 ——
            //   拖缩放把手时若抓的是左上角，**坐标和大小本来就该一起变**；
            //   这时再"沿用旧坐标"会把拉伸后的位置拉回去，用户看到的就是"拉完又跳回原位"。
            //   无触摸才走 fix80 的坐标保护（系统重摆/动效帧 → 沿用旧坐标，只更新大小）。
            val touched = isTouched(pkg, 2_000L)
            if (old != null && !touched && (abs(out.left - old.left) > RESIZE_COORD_TOL ||
                    abs(out.top - old.top) > RESIZE_COORD_TOL)
            ) {
                out2 = Rect(old.left, old.top, old.left + out.width(), old.top + out.height())
                resizeCoordInvalid.incrementAndGet()
                lastSkip = "$pkg:resize-coord-invalid(帧${out.left},${out.top}→沿用${old.left},${old.top})"
                probeMark("WRITE resize改坐标=无效 $key 帧=${out.left},${out.top},${out.right},${out.bottom} " +
                    "沿用旧坐标=${old.left},${old.top} 只更新尺寸 ${out.width()}x${out.height()}")
                log("resize 改坐标 → 无效数据 $pkg：丢弃帧坐标 ${out.left},${out.top}，沿用记忆坐标 " +
                    "${old.left},${old.top}（只更新尺寸）")
            }
        }
        writeMemoryLine(path, key, "${out2.left},${out2.top},${out2.right},${out2.bottom}")
        // ★ fix91：记住这次落盘的尺寸，作为下次「用户是不是真拉伸了」的比较基准。
        lastSize[key] = out2.width() to out2.height()
        written.incrementAndGet()
        // ★ fix77 探针：落盘动作进 moved 序列，与帧对照看「哪一帧写的」。
        probeMark("WRITE MAIN $key=${out2.left},${out2.top},${out2.right},${out2.bottom}" +
            if (src == Src.RESIZE) " (resize)" else "")
        log("记忆(系统侧·${if (landscape) "横屏" else "竖屏"}) $pkg -> ${out2.left},${out2.top},${out2.right},${out2.bottom}")
    }

    /** 读记忆文件里某个键当前的矩形（`l,t,r,b`）；没有/解析失败返回 null。 */
    private fun readMemoryRect(path: String, key: String): Rect? = runCatching {
        val f = File(path)
        if (!f.exists()) return null
        for (l in f.readLines()) {
            if (!l.startsWith("$key=")) continue
            val v = l.substring(key.length + 1).split(",")
            if (v.size < 4) return null
            return Rect(v[0].trim().toInt(), v[1].trim().toInt(), v[2].trim().toInt(), v[3].trim().toInt())
        }
        null
    }.getOrNull()

    /**
     * ★ fix79：路由统一 —— 所有 freeform 小窗共用主文件一份记忆，键只按横竖屏分
     * （[HookContract.memoryKey]：竖 `pkg=` / 横 `pkg@L=`）。旧「按会话分流主文件/SB 文件」
     * 已废弃（真机实证：App 关窗流程先删会话文件，remove flush 误判成系统窗全落 SB，
     * 主文件读不回 = 记忆丢）。
     */
    private fun computeRoute(pkg: String, landscape: Boolean): Pair<String, String> =
        HookContract.WINDOW_MEMORY_PATH to HookContract.memoryKey(pkg, landscape)

    /** 只替换同一方向那一行（竖 `pkg=`/`pkg@SB=` / 横 `pkg@L=`/`pkg@SB@L=`），另一个方向不动。 */
    private fun writeMemoryLine(path: String, key: String, value: String) {
        runCatching {
            val f = File(path)
            val kept = if (f.exists()) f.readLines().filter { !it.startsWith("$key=") } else emptyList()
            val tmp = File(path + ".tmp")
            tmp.writeText((kept + "$key=$value").joinToString("\n") + "\n")
            runCatching { tmp.setReadable(true, false); tmp.setWritable(true, false) }
            tmp.renameTo(f)
        }.onFailure { log("写 $path 失败 ${it.message}") }
    }

    // ---------------------------------------------------------------- ★ fix124 学习系统默认几何

    /**
     * ★ fix124：从「系统入口出生帧」学澎湃的默认小窗几何。
     *
     * 症状：无记忆的包用悬浮球开（`am start-activity --windowingMode 5`），出生几何落到
     * AOSP 级联默认（`[286,714][794,1794]` 一族），宽高与系统入口（侧边栏/横幅）的
     * 澎湃默认对不上，且错值还会被当成记忆自我循环。
     *
     * 学法：系统入口开的窗，出生帧（首次见到的 setBounds/onResize）几何**就是**澎湃默认。
     * 全部闸门通过才学，写进 [HookContract.DEFAULT_RECT_PATH]（一次采样定音，之后不覆盖）：
     *  ① 该包**当前方向**没有记忆——有记忆的窗几何来自记忆/用户，与"默认"无关；
     *  ② 不是我们 `am start` 拉起的（[HookContract.selfLaunchFresh]）——无记忆自启动的
     *     出生几何正是要修掉的 AOSP 级联值，学进去就是污染循环；
     *  ③ 几何 sane——不是退出动效全屏帧（`w>=sw-40 && h>=sh-40`）、不是失焦角
     *     `[35,127]` 族、边长 >= [MIN_SIDE]。
     *
     * 全程 IO 挪到 writeHandler 线串行执行（回调发生在 WMS 锁内，不能当场开文件）。
     */
    private fun maybeLearnDefault(pkg: String, b: Rect, sz: IntArray?, land: Boolean) {
        if (sz == null) return
        val w = b.width()
        val h = b.height()
        if (w < MIN_SIDE || h < MIN_SIDE) return
        if (w >= sz[0] - 40 && h >= sz[1] - 40) return // 退出动效全屏帧（doWrite 同口径）
        if (b.left in 20..60 && b.top in 100..160) return // 失焦重摆角族
        val key = HookContract.defaultKey(land)
        val memKey = HookContract.memoryKey(pkg, land)
        writeHandler.post {
            runCatching {
                if (HookContract.selfLaunchFresh(pkg)) {
                    log("默认几何学习跳过 $pkg：自启动窗口（几何不是系统默认）")
                    return@runCatching
                }
                if (readMemoryRect(HookContract.WINDOW_MEMORY_PATH, memKey) != null) return@runCatching
                if (readMemoryRect(HookContract.DEFAULT_RECT_PATH, key) != null) return@runCatching
                writeMemoryLine(
                    HookContract.DEFAULT_RECT_PATH, key,
                    "${b.left},${b.top},${b.right},${b.bottom}"
                )
                log("学得系统默认几何($key) $pkg -> ${b.left},${b.top},${b.right},${b.bottom}")
            }.onFailure { log("默认几何学习失败 ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- 反射取数

    /** 安全调用返回 Boolean 的方法；方法不存在 / 反射失败返回 null。 */
    private fun boolOf(t: Any, name: String): Boolean? =
        runCatching { XposedHelpers.callMethod(t, name) as? Boolean }.getOrNull()

    // ★ fix77 探针：onResize / onMovedByResize 帧序列 + Task 全状态，落
    //   `/data/system/memoryfreeform_record.state.moved`。目的：真机对照「用户真拖动的帧」
    //   与「关闭动效重摆的帧」在 isVisible / isAnimating / isOnTop / toString 上哪个
    //   状态位分叉，据此补第二道过滤（拦超屏重摆带 [775,…][1855,…] 那一族）。
    private val movedProbe = ArrayDeque<String>()
    private val probeLock = Any()

    private fun probeMoved(b: Rect, extra: String, task: Any) {
        synchronized(probeLock) {
            runCatching {
                val s = task.toString().replace(Regex("\\s+"), " ").take(300)
                movedProbe.addLast(
                    "${System.currentTimeMillis() % 1000000} ${b.left},${b.top},${b.right},${b.bottom} $extra | $s"
                )
                while (movedProbe.size > 48) movedProbe.removeFirst()
                flushProbe()
            }
        }
    }

    private fun probeMark(msg: String) {
        synchronized(probeLock) {
            runCatching {
                movedProbe.addLast("${System.currentTimeMillis() % 1000000} === $msg ===")
                while (movedProbe.size > 48) movedProbe.removeFirst()
                flushProbe()
            }
        }
    }

    private fun flushProbe() {
        val f = File(HookContract.RECORD_STATE_PATH + ".moved")
        f.writeText(movedProbe.joinToString("\n") + "\n")
        runCatching { f.setReadable(true, false) }
    }

    private fun isFreeformTask(t: Any): Boolean {
        val m = runCatching { XposedHelpers.callMethod(t, "getWindowingMode") as? Int }.getOrNull()
        return m == FREEFORM_MODE
    }

    private fun boundsOf(t: Any): Rect? {
        return runCatching { XposedHelpers.callMethod(t, "getBounds") as? Rect }.getOrNull()
    }

    /**
     * 从 Task 取包名。★ fix73：拖动落定回调里 `no-pkg` 极多（`onResize calls=117 applied=26`），
     * 说明原四道兜底在「窗口正在被拖」这一瞬间常取不到 —— 补上 `mTopActivity`/`topActivity`
     * 两个 ActivityRecord 路径，再从 `mChildren` 里遍历取第一个 ActivityRecord 的包名。
     */
    private fun pkgOfTask(t: Any): String? {
        for (f in listOf("realActivity", "origActivity")) {
            val c = runCatching { XposedHelpers.getObjectField(t, f) }.getOrNull()
            val p = runCatching { XposedHelpers.callMethod(c, "getPackageName") as? String }
                .getOrNull()
            if (!p.isNullOrEmpty()) return p
        }
        // ★ fix73：顶层 Activity 是"用户正看着的那个"，比 realActivity 更可靠（后者可能已为 null）。
        for (f in listOf("mTopActivity", "topActivity")) {
            val c = runCatching { XposedHelpers.getObjectField(t, f) }.getOrNull()
            val p = runCatching { XposedHelpers.callMethod(c, "getPackageName") as? String }
                .getOrNull()
            if (!p.isNullOrEmpty()) return p
        }
        val i = runCatching { XposedHelpers.getObjectField(t, "intent") }.getOrNull()
        val pi = runCatching { XposedHelpers.callMethod(i, "getPackage") as? String }.getOrNull()
        if (!pi.isNullOrEmpty()) return pi
        // ★ fix73：mChildren 里第一个能拿到包名的 ActivityRecord（Task 的直接子节点）。
        runCatching {
            val children = XposedHelpers.getObjectField(t, "mChildren") as? Iterable<*>
            for (c in children ?: emptyList<Any>()) {
                val p = runCatching { XposedHelpers.callMethod(c, "getPackageName") as? String }
                    .getOrNull()
                if (!p.isNullOrEmpty()) return p
            }
        }
        return runCatching { XposedHelpers.callMethod(t, "getPackageName") as? String }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- 键与迁移（fix79）

    /**
     * ★ fix71：稳定的 debounce 键 = 路由里的 key（如 `com.tencent.mm` / `com.tencent.mm@L`）。
     * 它天然稳定、唯一、且自带「方向」语义 —— 横竖屏互不冲突。
     */
    private fun stableKeyFor(route: Pair<String, String>): String = route.second

    /**
     * ★ fix79：**一次性迁移** —— 把旧 SB 文件（`<pkg>@SB=` / `<pkg>@SB@L=`）并进主文件：
     * 键去掉 `@SB` 标记，主文件已有的键**不覆盖**（主文件优先，避免旧 SB 值冲掉较新的全尺寸记忆）；
     * 迁完删掉 SB 文件。每次启动跑一遍，幂等（SB 不存在直接返回）。
     */
    private fun migrateSbMemory() {
        runCatching {
            val sb = File(HookContract.WINDOW_MEMORY_SB_PATH)
            if (!sb.exists()) return
            val main = File(HookContract.WINDOW_MEMORY_PATH)
            val existing = HashSet<String>()
            val kept = if (main.exists()) main.readLines() else emptyList()
            for (l in kept) {
                val i = l.indexOf('=')
                if (i > 0) existing.add(l.substring(0, i).trim())
            }
            val add = ArrayList<String>()
            for (l in sb.readLines()) {
                val i = l.indexOf('=')
                if (i <= 0) continue
                val rawKey = l.substring(0, i).trim()
                // ★ 注意映射：`pkg@SB@L` → `pkg@L`（横屏标记要**保留**），
                //   `pkg@SB` → `pkg`。先判 @SB@L 再判 @SB，顺序不能反。
                val nk = when {
                    rawKey.endsWith("@SB@L") -> rawKey.removeSuffix("@SB@L") + "@L"
                    rawKey.endsWith("@SB") -> rawKey.removeSuffix("@SB")
                    else -> continue
                }
                if (nk.isEmpty() || !existing.add(nk)) continue
                add.add("$nk=${l.substring(i + 1)}")
            }
            if (add.isNotEmpty()) {
                val tmp = File(HookContract.WINDOW_MEMORY_PATH + ".tmp")
                tmp.writeText((kept + add).joinToString("\n") + "\n")
                runCatching { tmp.setReadable(true, false); tmp.setWritable(true, false) }
                tmp.renameTo(main)
            }
            sb.delete()
            log("记忆统一(fix79)：SB 记忆已并入主文件 ${add.size} 条，SB 文件已删除")
        }.onFailure { log("SB 记忆迁移失败 ${it.message}") }
    }

    /** 当前屏幕尺寸（跟随旋转）。读不到返回 null（调用方按"不收边"处理）。 */
    /**
     * ★ fix86：系统状态栏高度（px），在 system_server 里读 `android:dimen/status_bar_height`。
     * 读不到返回 0（此时顶部不避让，退化成 fix85 的行为，至少不会更糟）。
     */
    private fun statusBarHeightSystem(): Int = runCatching {
        val r = android.content.res.Resources.getSystem()
        val id = r.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) r.getDimensionPixelSize(id).coerceIn(0, 200) else 0
    }.getOrDefault(0)

    private fun readDisplaySize(): IntArray? {
        // 直接借 system_server 里的 system context（旋转后跟着更新）。
        val at = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
        }.getOrNull() ?: return null
        val ctx = runCatching { XposedHelpers.callMethod(at, "getSystemContext") }
            .getOrNull() as? android.content.Context ?: return null
        val dm = ctx.resources?.displayMetrics ?: return null
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return null
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    // ---------------------------------------------------------------- 自检

    private fun startFlusher() {
        Thread({
            while (true) {
                runCatching { Thread.sleep(1_000L) }.getOrNull() ?: return@Thread
                runCatching {
                    val sb = StringBuilder()
                    val n = stats.values.count { it.inst > 0 }
                    sb.append("installed=$n/${SITES.size} seen=${seen.get()} applied=${written.get()}\n")
                    sb.append("last=$lastPkg rect=$lastRect skip=$lastSkip\n")
                    // ★ fix78：手势条触摸锁定自检。down 不涨 = PointerEventListener 没注册上；
                    //   down 涨 hits 不涨 = DOWN 都没落在手势条带上（带高/坐标系问题）；
                    //   hits 涨 = 按下已记停稳位置并开始拦截（flags=当前拦着的包）。
                    //   ★ fix78b：plisten=0 时看 err —— 注册失败的具体原因（CNFE / no-method / proxy…）。
                    sb.append("bar(down=${barDowns.get()} hits=${barHits.get()} ups=${barUps.get()}" +
                        " flags=${barFlags.keys} plisten=${if (pointerListener != null) 1 else 0}" +
                        (if (pointerErr.isNotEmpty()) " err=$pointerErr" else "") + ")\n")
                    // ★ fix87：gm(...) 那行已随手势类探针删除（真机 hooked=0，从未钩上）。
                    // ★ fix80：resize 帧「改了坐标」被判无效数据的次数（只沿用旧坐标 + 更新尺寸）。
                    //   一直是 0 = 没抓到这类帧（正常）；涨得快说明系统重摆/缩放补偿在频繁污染。
                    sb.append("resize(coord-invalid=${resizeCoordInvalid.get()})\n")
                    for (s in SITES) sb.append("${s.label} ${stats[s.label] ?: Stat()}\n")
                    val f = File(HookContract.RECORD_STATE_PATH)
                    val tmp = File(HookContract.RECORD_STATE_PATH + ".tmp")
                    tmp.writeText(sb.toString())
                    runCatching { tmp.setReadable(true, false) }
                    tmp.renameTo(f)
                }
            }
        }, "memoryfreeform-rec-flush").apply { isDaemon = true }.start()
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG $msg")
    }
}
