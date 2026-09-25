package xiaojw.memoryFreeform.core

import android.content.Context
import xiaojw.memoryFreeform.root.RootManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ★ fix51：**全局小窗位置监听** —— 不管小窗是澎湃自己开的（侧边栏 / 通知 / 最近任务），
 * 还是本 App 开的，关闭 / 挂起时都把它的位置记下来。
 *
 * ## 为什么要有这个类
 *
 * fix48 的位置记忆只盯**我们自己启动的那一个包**（观察线程跟着会话走），所以：
 * - 用户从澎湃侧边栏开的小窗（不是我们开的）关掉时，位置**没有任何人记**；
 * - 服务不在时（单手模式没开）也无人观察。
 *
 * 现在改成**进程级单例观察线程**：只要 App 进程活着就一直在跑，盯住 `am stack list`
 * 里**所有 freeform RootTask**，按 taskId 逐个跟踪。于是"谁开的小窗"不再重要。
 *
 * ## 落盘时机（事件触发，**不是**定时落盘）
 *
 * fix48 踩过的坑：定时采样抓到的多半是垃圾帧（退出动画的全屏态 `1080x2400`、
 * 中间帧 `1079x1728 @ [-15,351]`、澎湃失焦重摆的左上角 `[35,127]`），最后写进去的
 * 反而把用户真正拖到的位置覆盖掉。所以这里只维护**内存历史**，落盘只发生在下面三种
 * **事件**上（没有任何一条是"时间到了就写"）：
 *
 *  ① 某个 taskId 从列表里**消失**（被关闭 / 被转全屏 / 被回收）→ 用消失前最后一次
 *     有效读数落盘；
 *  ② 某个 task 由 `visible=true` 变成 `visible=false`（挂起 / 按 HOME / 切走）→
 *     用**变不可见之前**的读数落盘（变不可见之后澎湃会把窗口重摆到左上角，
 *     那一帧绝不能要）。
 *
 * ★ fix59 加了第三条路：**窗口静止**（连续两拍几何完全一致）就落盘。
 *
 * 为什么不能只等 ① ②：用户用**澎湃底部那条手势条**上滑关掉小窗时，这条路径既不走
 * 我们的 `stopSelf`，任务消失/变不可见的那一帧也未必被 2 秒一拍逮到 —— 结果是
 * "用手势条关掉 app，位置没被记住"（用户反馈）。而位置这件事本来就不必等到关闭：
 * 连续两拍一动不动 = 用户已经把它放下了，那一帧就是答案。
 *
 * 关键差别在**判据**：fix48 反对的是"定时采样后直接把当前帧写下去"（会写进动画帧），
 * 这里要求**两拍完全一致**（尺寸 + 位置，比 [pick] 的"尺寸一致"更严），并且
 * [record] 会按矩形本身拒绝澎湃重摆的左上角 `[35,127]` —— 写下去的都是静止帧。
 * 去重按包名做（[persistStable]），一次拖动只写几次。
 *
 * ## 怎么从一堆读里挑出"用户最后看到的位置"
 *
 * 维护每个 task 最近 [HISTORY_MAX] 次**可见且尺寸合理**的读数，落盘时从最新往回找
 * 「**尺寸与上一帧一致**」的样本 —— 拖动只改位置不改尺寸，而退出动画的尺寸是飘的，
 * 这一条就把动画帧全滤掉了。另外跳过「澎湃失焦重摆位」（`[35,127]` 那个角）。
 *
 * ## 两条记忆通道（★ fix52：都是「下发坐标」同一个域）
 *
 * - [WindowMemory]（App 侧 SharedPreferences）：存 `l,t,r,b`，供本 App 下次启动小窗时读；
 * - `/data/system/memoryfreeform_window_memory`（同样是 `l,t,r,b`）：供 system_server
 *   里的出生钩子读，管「挂起很久之后再点开」的恢复链路（App 侧的 birth_target 有 60s TTL）。
 *
 * 两个文件内容**完全同域**，读出来直接就是能下发给系统的坐标，不做任何倍率换算。
 * （fix51 之前 App 侧存的是"视觉意图"，靠猜倍率反推下发值 —— 猜错就每开合一次
 * 缩小一圈，见 [WindowMemory] 的类注释。）
 */
object WindowWatcher {

    private const val TAG = "WindowWatcher"

    /**
     * 有 freeform 小窗时的轮询间隔。`executeFast` 走常驻查询 shell，不是每轮 fork。
     *
     * ★ fix66：**真正的"记录"已搬进 system_server（`MiuiFreeformRecordHook`）**——
     * 拖完/缩放完/关窗那一刻由系统侧直接落盘，事件驱动、不依赖本进程、不必每秒轮询。
     * 本线程现在只剩两个轻量职责：① 维护 `lastTasks()` 给悬浮球菜单做"有没有小窗"的缓存；
     * ② 当 system_server 钩子 `installed=0`（这台 ROM 没挂上）时，作为**兜底**仍按老办法记。
     * 所以轮询从 1 秒放宽到 2 秒，进一步降低"开着小窗时每秒唤醒一次"的耗电。
     *
     * ⚠ 之前 1 秒是怕"拖完马上关窗丢最后一帧"——那个问题现在由系统侧的 `Task.remove*`
     *   钩子（关窗前立即落盘）解决了，本线程不再需要那么密。
     */
    private const val POLL_ACTIVE_MS = 2_000L

    /** 一个 freeform 小窗都没有时的轮询间隔（只为及时发现"刚冒出来的"小窗）。 */
    private const val POLL_IDLE_MS = 5_000L

    /** 每个 task 保留的最近读数个数。 */
    private const val HISTORY_MAX = 4

    /** 比这更小的窗口不可能是"用户在用的小窗"（澎湃最小实测 200px）。 */
    private const val MIN_SIDE = 200

    private val started = AtomicBoolean(false)

    @Volatile
    private var stopFlag = false

    private val listeners = CopyOnWriteArrayList<(List<FfTask>) -> Unit>()

    /**
     * ★ fix58：**最近一轮**读到的 freeform 小窗快照（只要 App 进程活着就在更新）。
     *
     * 悬浮球菜单要在**主线程**上瞬间判断"屏幕上现在有没有小窗"，而 [visibleTasks] /
     * [readFreeformTasks] 都是**同步的 root 读**（`am stack list`，几百毫秒）——
     * 在主线程调就是把菜单卡住。所以让观察线程顺手把每轮结果存一份，菜单读缓存。
     *
     * 落后最多一个轮询周期（有小窗 2s / 没有 5s），对"菜单上显示哪一项"完全够用。
     */
    @Volatile
    private var lastTasksCached: List<FfTask> = emptyList()

    /** 免 root、免等待的快照（可能落后最多一个轮询周期）。 */
    fun lastTasks(): List<FfTask> = lastTasksCached

    /** 只为算屏幕尺寸（判断"这是不是全屏帧"），持 applicationContext 不会泄漏。 */
    @Volatile
    private var appCtx: Context? = null

    /** `am stack list` 里一个 freeform 小窗的现状。 */
    class FfTask(
        val taskId: Int,
        val pkg: String,
        val l: Int,
        val t: Int,
        val r: Int,
        val b: Int,
        val visible: Boolean
    ) {
        val rect: IntArray get() = intArrayOf(l, t, r, b)
    }

    /** 幂等启动。App 进程起来（[xiaojw.memoryFreeform.MemoryFreeformApp]）或任一个 Service 起来时调。 */
    fun ensureStarted(context: Context) {
        appCtx = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        stopFlag = false
        Thread({ loop() }, "memoryfreeform-ffwatch").apply { isDaemon = true }.start()
        SHLog.i(TAG, "全局小窗监听已启动：记录所有 freeform 小窗（含系统开的）的关闭位置")
    }

    fun stop() {
        stopFlag = true
    }

    fun addListener(l: (List<FfTask>) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (List<FfTask>) -> Unit) {
        listeners.remove(l)
    }

    // ------------------------------------------------------------------ 主循环

    private fun loop() {
        val pkgOf = HashMap<Int, String>()
        val history = HashMap<Int, ArrayDeque<IntArray>>()
        val visibleNow = HashSet<Int>()
        // ★ fix59：每个 taskId 上一拍读到的几何（只要 sane 就存），用来判"窗口静止"。
        val lastRect = HashMap<Int, IntArray>()
        // ★ fix64：每个 taskId **最近一帧 sane 几何** —— 关窗/挂起时 [pick] 挑不出
        //   （历史里全是动画帧 / 只有一帧且尺寸对不上）就用它兜底，绝不"什么都没记"。
        //   宁可记下"用户最后停下来那一刻附近的坐标"，也好过让下次开在几天前的位置上。
        val lastSane = HashMap<Int, IntArray>()

        while (!stopFlag) {
            var nap: Long
            try {
                // null = 这一轮**没读到**（root 查询 shell 忙 / 命令失败），不是"小窗没了"。
                // 必须区分开：否则一次读取失败就会被当成"所有小窗都关了"，把还在的小窗
                // 提前结算掉（虽然历史里的坐标是对的，但会白白丢掉后续跟踪）。
                val tasks = pollFreeformTasks()
                if (tasks == null) {
                    runCatching { Thread.sleep(POLL_ACTIVE_MS) }
                        .getOrNull() ?: return
                    continue
                }
                val alive = tasks.mapTo(HashSet()) { it.taskId }
                // ★ fix58：每轮留一份快照给悬浮球菜单（见 [lastTasksCached]）。
                //   只在**这一轮真的读到了**的时候更新 —— 读失败（null）不能把快照清空，
                //   否则菜单会在"有窗"和"没窗"之间来回跳。
                lastTasksCached = tasks

                // ① 从列表里消失 = 被关闭（或转全屏 / 被回收）→ 用消失前的有效读数落盘。
                //    注意：这里读不到"当前"几何了，只能用上一轮的历史 —— 而上一轮正是
                //    用户关掉它之前最后看到的画面，所以这才是准的。
                for (id in pkgOf.keys.filter { it !in alive }) {
                    val p = pkgOf.remove(id) ?: continue
                    visibleNow.remove(id)
                    lastRect.remove(id)
                    val best = pick(history.remove(id))
                    val fb = lastSane.remove(id)
                    // ★ fix64：挑不出就拿"最近一帧 sane"兜底（见 [lastSane] 的注释）。
                    if (best != null) record(p, best, "关闭")
                    else if (fb != null) record(p, fb, "关闭(兜底最后一帧)")
                }

                // ② 逐个更新历史 / 处理"由可见变不可见"（挂起、HOME、切走）。
                for (t in tasks) {
                    // taskId 复用：同一个 id 换了包，旧历史必须丢掉（否则新 App 会拿到旧 App 的坐标）
                    if (pkgOf[t.taskId] != null && pkgOf[t.taskId] != t.pkg) history[t.taskId]?.clear()
                    pkgOf[t.taskId] = t.pkg

                    // ★ fix59：稳定帧落盘（见 [persistStable]）。
                    //
                    // 为什么不能只靠"关窗事件"：用户用**澎湃底部那条手势条**上滑关掉
                    // 小窗时，那条路既不经过我们的 stopSelf，任务消失/变不可见的那一帧
                    // 也未必被 2 秒一拍读到 —— 于是记忆压根没写下（用户反馈"用手势条关
                    // 掉 app 时位置没被记住"）。位置这种事**不需要**等到关闭那一刻：
                    // 窗口连续两拍一动不动，就说明用户已经把它放下了。
                    val prev = lastRect[t.taskId]
                    lastRect[t.taskId] = t.rect
                    // ★ fix64：这一帧"**能不能被记下来**"（[record] 的全部闸门）由 [acceptable] 判，
                    //   只有它才进 [lastSane] 与 history —— 否则关窗时会挑到一帧 [record] 必然
                    //   拒绝的几何（澎湃重摆的左上角 / 没落在屏幕内），一路 reject 之后
                    //   **什么都记不下**，用户看到的就是"用澎湃关窗不记位置"。
                    val okNow = acceptable(t.rect)
                    if (okNow) lastSane[t.taskId] = t.rect
                    if (okNow && prev != null && prev.contentEquals(t.rect)) {
                        persistStable(t.pkg, t.rect)
                    }

                    val h = history.getOrPut(t.taskId) { ArrayDeque() }
                    if (t.visible) {
                        if (okNow) {
                            h.addLast(t.rect)
                            while (h.size > HISTORY_MAX) h.removeFirst()
                        }
                        visibleNow.add(t.taskId)
                    } else if (visibleNow.remove(t.taskId)) {
                        // 刚变成不可见：这一帧澎湃可能已经把窗口重摆走了，所以用**之前**的历史
                        val picked = pick(h)
                        val best = picked ?: lastSane[t.taskId]
                        best?.let { record(t.pkg, it, if (picked != null) "挂起" else "挂起(兜底)") }
                        h.clear()
                    }
                }

                for (l in listeners) runCatching { l(tasks) }

                nap = if (tasks.isEmpty()) POLL_IDLE_MS else POLL_ACTIVE_MS
            } catch (t: Throwable) {
                // ★ fix59：**这条线程死不起**。它是"系统自己开的小窗也能记住位置"的唯一
                //   保障（fix51），而它没有任何人重启：以前循环体里抛一次异常，整条线程
                //   就默默结束了，之后所有小窗的位置都不再有人记（也没有任何日志提示）。
                //   现在一轮出错只跳过这一轮，下一轮照跑。
                SHLog.w(TAG, "监听轮异常（跳过本轮，线程继续跑）: ${t.message}")
                nap = POLL_ACTIVE_MS
            }
            runCatching { Thread.sleep(nap) }.getOrNull() ?: return
        }
    }

    /**
     * 从历史里挑「用户最后停留的那个位置」。
     *
     * 判据：尺寸必须与**上一帧**一致（拖动只改位置不改尺寸；退出动画 / 缩放动画的
     * 每帧尺寸都不同，会被自动跳过），并且不能是澎湃失焦重摆的那个角。
     */
    private fun pick(h: List<IntArray>?): IntArray? {
        if (h.isNullOrEmpty()) return null
        for (i in h.size - 1 downTo 1) {
            val cur = h[i]
            if (sameSize(cur, h[i - 1]) && !isLostFocusCorner(cur, h[i - 1])) return cur
        }
        val last = h.last()
        return if (h.size == 1 && !isLostFocusCorner(last, null)) last else null
    }

    private fun sameSize(a: IntArray, b: IntArray): Boolean =
        (a[2] - a[0]) == (b[2] - b[0]) && (a[3] - a[1]) == (b[3] - b[1])

    /**
     * 澎湃在窗口**失去顶层地位**后会把小窗重摆到左上角 `[35,127]`（fix40 实测，
     * 尺寸保留、只改位置）。那不是用户放的，不能记。只在「上一帧在别处」时才敢判定，
     * 免得用户真把窗口拖到那个角落反而被无视。
     */
    private fun isLostFocusCorner(cur: IntArray, prev: IntArray?): Boolean {
        if (prev == null) return false
        if (cur[0] == prev[0] && cur[1] == prev[1]) return false
        return isLostFocusCornerRect(cur)
    }

    /**
     * ★ fix59：单纯看矩形坐标，不管上一帧 —— 就是"澎湃重摆的那个左上角"吗。
     *
     * [isLostFocusCorner] 那条判据要求"与上一帧位置不同"才算重摆，可窗口如果就**停在**
     * 那个角落好几秒（挂起后没人再动它），连续两拍一模一样，就会被"稳定帧落盘"当成
     * 用户自己摆放的位置记下来。所以 [record] 里按矩形本身再挡一道。
     *
     * 区域取 `x∈20..60 && y∈100..160`：我们自己摆的左下角是 `x=0`、右下角是
     * `x=屏宽-宽`，都不会落进来，所以不会误伤正常摆放。
     */
    private fun isLostFocusCornerRect(b: IntArray): Boolean =
        b[0] in 20..60 && b[1] in 100..160

    /**
     * ★ fix64：这一帧**能不能被记下来** —— 即它能否通过 [record] 的全部闸门
     * （[sane] + 完整落在屏幕内 + 不是澎湃重摆的左上角）。
     *
     * 为什么要单独抽出来给"记录候选"用：以前只判 [sane]，于是关窗时挑出的候选
     * 可能在 [record] 里被拒（例如窗口失去顶层后被澎湃挪到左上角、或几何跑到屏幕外），
     * 结果**一条都没落下**，用户看到的就是"关窗时位置没被记住"。
     */
    private fun acceptable(b: IntArray): Boolean {
        if (!sane(b)) return false
        if (isLostFocusCornerRect(b)) return false
        val ctx = appCtx ?: return true
        val (sw, sh) = WindowSizing.realScreenSize(ctx)
        return WindowSizing.isInsideScreen(b, sw, sh)
    }

    /** 这一帧像不像"用户正看着的小窗"：不是全屏、不是极小、不出屏幕。 */
    private fun sane(b: IntArray): Boolean {
        if (b[0] < 0 || b[1] < 0) return false
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        if (w < MIN_SIDE || h < MIN_SIDE) return false
        val ctx = appCtx ?: return true
        val (sw, sh) = WindowSizing.realScreenSize(ctx)
        if (w > sw || h > sh) return false
        // 全屏态 = 退出动画的放大帧，不要
        if (w >= sw - 40 && h >= sh - 40) return false
        if (b[0] >= sw - 100 || b[1] >= sh - 100) return false
        return true
    }

    // ------------------------------------------------------------------ 落盘

    /**
     * 记一份记忆。
     *
     * ★ fix52：**下发坐标原样存**，不做任何倍率换算（那是乘法螺旋的来源，见类注释）。
     *   读到什么就写什么，所以"记下来的"与"下次下发的"必然一致 ——
     *   除非缩放状态真的变了（hook 装上/卸掉），那时窗口会整体变大或变小一次，
     *   但那是系统的真实变化，不是我们算出来的漂移。
     *
     * ★ fix59：两处加固 ——
     *  ① 拒绝"澎湃重摆帧"（[isLostFocusCornerRect]）。窗口失去顶层地位后会被挪到
     *     左上角 `[35,127]`，尺寸不变、能过一切尺寸校验；以前只有 [pick] 里"与上一帧
     *     不同"那条防线，窗口停在那儿几秒就漏得进来 —— 记进去下次就开在那个角落。
     *  ② 落盘拆两步：SharedPreferences **同步**写（App 侧恢复位置读的就是它，一次内存
     *     写），`/data/system` 那份**异步**写（要 fork su，见 [writeSharedAsync]）。
     *
     * @param bounds 从 `am stack list` 读回的 **task bounds**（= 下发给系统的坐标）
     * @param reason 落盘原因，只进日志（"关闭" / "挂起" / "关闭前" / "稳定"）
     */
    fun record(pkg: String, bounds: IntArray, reason: String) {
        if (pkg.isBlank()) return
        val w = bounds[2] - bounds[0]
        val h = bounds[3] - bounds[1]
        if (w < MIN_SIDE || h < MIN_SIDE) return
        if (isLostFocusCornerRect(bounds)) {
            SHLog.w(
                TAG,
                "记忆拒绝($reason) $pkg：停在澎湃重摆的左上角 " +
                    "[${bounds[0]},${bounds[1]}]，保留上一次记忆"
            )
            return
        }

        // ★ fix64：**方向上必须自洽**。判定与收边都要用"当前这一帧的屏幕"。
        val ctx = appCtx
        var landscape = false
        if (ctx != null) {
            val (sw, sh) = WindowSizing.realScreenSize(ctx)
            landscape = sw > sh
            // ★ fix64：拒绝"没完整落在屏幕内"的矩形。
            //   真机日志里出现过 `[775,127][1575,1927]`（屏宽 1080）—— 右边界超出屏幕 495px，
            //   用户不可能把它拖到那儿，这是澎湃重摆 / 动画帧；记进去下次就直接开在屏幕外
            //   （横屏时更明显：直接"小窗不见了"）。以前只有"左上角 [35,127]"那一条判据，
            //   覆盖不到这种"只错一半"的帧。
            if (!WindowSizing.isInsideScreen(bounds, sw, sh)) {
                SHLog.w(
                    TAG,
                    "记忆拒绝($reason) $pkg：几何没落在屏幕内 " +
                        "[${bounds[0]},${bounds[1]}][${bounds[2]},${bounds[3]}] " +
                        "（当前屏幕 ${sw}x$sh），保留上一次记忆"
                )
                return
            }
        }

        WindowMemory.put(pkg, landscape, bounds[0], bounds[1], bounds[2], bounds[3])
        lastRecordAt[pkg] = android.os.SystemClock.uptimeMillis()

        SHLog.i(
            TAG,
            "记忆($reason) $pkg -> ${w}x$h @ [${bounds[0]},${bounds[1]}]" +
                "（${if (landscape) "横屏" else "竖屏"}，下发值原样存）"
        )
        writeSharedAsync(pkg, bounds, landscape)
    }

    /** 每个包最后一次**真正落盘**的时刻（[android.os.SystemClock.uptimeMillis]）。 */
    private val lastRecordAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * ★ fix59：[pkg] 刚才有没有被记过 —— 给"关闭前采集"那道闸用。
     *
     * 现在窗口**静止两拍**就会落盘（[persistStable]），所以关窗时记忆通常已经是最新的
     * 实测值了。而 [xiaojw.memoryFreeform.service.CornerWindowService] 的"关闭前采集"
     * 读不到现场时会退回它自己的 `lastStableRect` 缓存 —— 那是**本次下发几何**校验过的
     * 值：用户拖过之后又用把手改过大小的话，新几何过不了那条尺寸校验，于是一份**更旧**
     * 的矩形会盖掉刚记下的新值。有了这个时间戳，那边就能让位。
     */
    fun recordedRecently(pkg: String, within: Long = 15_000L): Boolean {
        val at = lastRecordAt[pkg] ?: return false
        return android.os.SystemClock.uptimeMillis() - at <= within
    }

    /**
     * ★ fix59：「窗口静止」就落盘（[loop] 里连续两拍几何完全一致时调用）。
     *
     * 按包名去重：拖动过程中每拍坐标都在变，落盘只发生在两拍一致的那一刻，
     * 所以一次拖动最多写几次，不构成"每 2 秒往 /data/system 写一次"。
     * 只有 watcher 线程会调，所以 [lastPersisted] 用普通 HashMap 就够。
     */
    private fun persistStable(pkg: String, rect: IntArray) {
        val key = "${rect[0]},${rect[1]},${rect[2]},${rect[3]}"
        if (lastPersisted[pkg] == key) return
        lastPersisted[pkg] = key
        record(pkg, rect, "稳定")
    }

    /** 每个包**最后一次落盘**的几何（watcher 线程私有，见 [persistStable]）。 */
    private val lastPersisted = HashMap<String, String>()

    /**
     * ★ fix59：`/data/system/memoryfreeform_window_memory` 的**异步串行**写通道。
     *
     * 这个文件是给 system_server 里的出生钩子读的，**必须写成功**（挂起很久之后再打开
     * 的恢复链路只认它），所以走的仍是 fork 版 `execute()` 而不是常驻通道。
     * 但 fork 一个 su 要 200~300ms —— 而调用方可能是**监听轮询线程**（fix59 起"窗口
     * 静止"也会落盘），同步做会把轮询周期拖长一倍。丢给一条专用线程，单线程串行保证
     * 同一个包的先后顺序（最后一次写一定最后落地）。
     */
    private val memoryWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "memoryfreeform-ffmem").apply { isDaemon = true }
    }

    /**
     * ★ fix69：系统侧记录钩子装上时，[xiaojw.memoryFreeform.hook.HookContract.WINDOW_MEMORY_PATH]
     * 由它**独占**写（事件驱动、实时、比本进程轮询更准）。本进程不再写，避免两份写互相覆盖
     * （陈旧值盖掉实时值 → 用户报"拖完立刻重开位置不对、要等几秒才记上"）。
     * 只在钩子没装上（[RECORD_STATE_PATH] 里 `installed=0` 或文件不存在）时才兜底写，
     * 保留老 ROM 的降级路径。
     */
    private fun recordHookActive(): Boolean {
        val txt = runCatching {
            File(xiaojw.memoryFreeform.hook.HookContract.RECORD_STATE_PATH).readText()
        }.getOrNull() ?: return false
        val m = Regex("installed=(\\d+)").find(txt) ?: return false
        return (m.groupValues[1].toIntOrNull() ?: 0) > 0
    }

    private fun writeSharedAsync(pkg: String, bounds: IntArray, landscape: Boolean) {
        if (recordHookActive()) return // 系统钩子独占系统文件，本进程不写，消除竞态
        runCatching { memoryWriter.execute { writeSharedMemory(pkg, bounds, landscape) } }
            .onFailure { SHLog.w(TAG, "记忆异步写排队失败 ${it.message}") }
    }

    /**
     * 把记忆写进 `/data/system/memoryfreeform_window_memory`（system_server 里的钩子读它）。
     *
     * ★ fix64：键名带方向（见 [xiaojw.memoryFreeform.hook.HookContract.memoryKey]），
     *   **只替换同一方向那一行** —— 竖屏写 `pkg=...` 时不能把横屏的 `pkg@L=...` 删掉，
     *   否则用户一转屏，另一个方向刚记下的位置就没了（而用户要的正是"两个方向分别记"）。
     */
    private fun writeSharedMemory(pkg: String, bounds: IntArray, landscape: Boolean) {
        val f = xiaojw.memoryFreeform.hook.HookContract.WINDOW_MEMORY_PATH
        val key = xiaojw.memoryFreeform.hook.HookContract.memoryKey(pkg, landscape)
        // 走 fork 版 `execute()` 而不是常驻通道：这里一天写不了几次，但**必须写成功**
        // （挂起很久后的恢复链路只认这个文件），而常驻通道遇忙会直接放弃本轮。
        runCatching {
            RootManager.get().execute(
                "grep -v '^$key=' $f > $f.tmp 2>/dev/null; " +
                    "echo '$key=${bounds[0]},${bounds[1]},${bounds[2]},${bounds[3]}' >> $f.tmp; " +
                    "mv $f.tmp $f; chmod 666 $f 2>/dev/null"
            )
        }.onFailure { SHLog.w(TAG, "写 $f 失败 ${it.message}") }
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 读一轮 `am stack list`。
     *
     * @return 解析结果；**null 表示这一轮没读到**（查询 shell 忙 / 命令失败）。
     *   `executeFast` 走的是常驻查询 shell，带"上一条还在飞就放弃本轮"的防雪崩规则，
     *   所以失败是常态而不是异常 —— 调用方必须把"读失败"和"没有小窗"分开处理。
     */
    private fun pollFreeformTasks(): List<FfTask>? {
        val r = runCatching {
            RootManager.get().executeWatch("am stack list", 3000)
        }.getOrNull() ?: return null
        if (!r.success || r.output.isBlank()) return null
        return parseFreeform(r.output)
    }

    /** 读失败当作"没有小窗"的宽松版（诊断 / 采集用，不参与"消失判定"）。 */
    fun readFreeformTasks(): List<FfTask> = pollFreeformTasks() ?: emptyList()

    /**
     * 解析 `am stack list`，取出所有 **freeform** RootTask 里的 task 行。
     *
     * 为什么按 `mWindowingMode=freeform` 分段而不是直接 grep 包名：同一个包在全屏栈里
     * 也会出现（应用既可以全屏也可以小窗），只看包名分不清"小窗还在不在"。
     *
     * 真机输出结构：
     * ```
     * RootTask id=9 bounds=[...] displayId=0 userId=0
     *  configuration={... winConfig={ ... mWindowingMode=freeform ...} ...}
     *   taskId=10208: com.xxx/.Ui bounds=[l,t][r,b] userId=0 visible=true topActivity=...
     * ```
     */
    private fun parseFreeform(out: String): List<FfTask> {
        val list = ArrayList<FfTask>()
        val taskRe = Regex(
            "taskId=(\\d+):\\s*([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/" +
                "[^\\n]*?bounds=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]"
        )
        val visRe = Regex("visible=(true|false)")
        var freeform = false
        for (line in out.lineSequence()) {
            if (line.startsWith("RootTask")) {
                freeform = false
                continue
            }
            if (line.contains("mWindowingMode=freeform")) {
                freeform = true
                continue
            }
            if (!freeform) continue
            val m = taskRe.find(line) ?: continue
            list.add(
                FfTask(
                    taskId = m.groupValues[1].toIntOrNull() ?: continue,
                    pkg = m.groupValues[2],
                    l = m.groupValues[3].toIntOrNull() ?: continue,
                    t = m.groupValues[4].toIntOrNull() ?: continue,
                    r = m.groupValues[5].toIntOrNull() ?: continue,
                    b = m.groupValues[6].toIntOrNull() ?: continue,
                    visible = visRe.find(line)?.groupValues?.get(1) == "true"
                )
            )
        }
        return list
    }

    /** 当前可见的 freeform 小窗（诊断 / 会话收尾用）。 */
    fun visibleTasks(): List<FfTask> = readFreeformTasks().filter { it.visible }

    /** 读回某包当前**可见**小窗的下发 bounds `[l,t,r,b]`；没有则 null。 */
    fun visibleBoundsOf(pkg: String): IntArray? =
        readFreeformTasks().firstOrNull { it.pkg == pkg && it.visible }?.rect
}
