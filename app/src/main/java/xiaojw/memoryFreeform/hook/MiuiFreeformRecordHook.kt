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
import xiaojw.memoryFreeform.core.WindowSizing

/**
 * ★ fix195：**记录钩子架构重写**（替代 fix66~fix194 的写-回滚 + 多闸门状态机）。
 *
 * ## 为什么要重写
 *
 * 旧架构 2300+ 行、12 轮补丁（fix183~194），状态有 barFlags/barRecent/barSnapshot/
 * downScale/dragScaled/closingPkgs/lastSize/userGeomPkgs/latest… 十几个互相耦合的容器，
 * 写盘路径 6 道闸。每个新 bug 都是"补一道闸"，而每道闸又与既有闸门产生新的时序竞争：
 *  - **拖角改大小有时不保存**：缩放写入依赖手势证据链（DOWN 快照 vs 抬手读数 / dragScaled
 *    锁存），证据的置位、消费、清除分散在 5 处，任何一处时序错开（mode 抖动、#0/#1 重入、
 *    DOWN 覆盖快照）就丢一次写入；
 *  - **手势条短快上划关闭位置错乱**：#0 采样（UP+700ms）无法区分「稳定活着」与「正在关闭
 *    动效中」——上划甩出的动画帧只改位置不改面积，旧的所有"面积变大/全屏帧"闸全部不命中，
 *    动效中间帧被当成"移动"写盘；而 fix186 删掉了回滚，污染值无人纠正。
 *
 * ## 新架构：两条独立链路，状态只有 6 个容器
 *
 * ### 链路 S（大小 = freeformScale）—— 事件流 debounce，与手势判定完全解耦
 *  - 事件源：`Task.setMiuiFreeformScale(F)` after（fix191 定论：系统改缩放必经）。
 *  - **写入条件只有一个**：事件发生在「拖动窗口」期间（[barFlags] 命中，或抬手后
 *    [SCALE_TAIL_MS] 内的弹簧余波）。没有事件 = 永不写：
 *      · 纯移动（不改缩放）→ 无事件 → 不写（旧版"注入丢失把 0.7 合法化"的结构性根除）；
 *      · 冷开动画 / 系统摆窗（无手势）→ 不写（旧版靠 isTouched 挡，这里天然不进）；
 *      · 关窗动效还原 1.0 → 值域闸（≥[SCALE_MAX_SANE]）拒 + 清待写。
 *  - 落定：最后一次事件 +[SCALE_SETTLE_MS] debounce → 终值复验（值域 / mode==5）→ 写。
 *    拖动中另按 [SCALE_LIVE_THROTTLE_MS] 节流实时写（fix194 用户要求：s 跟着 live 走），
 *    弹簧收敛后的终值由 debounce 覆盖。
 *
 * ### 链路 P（位置 = bounds）—— UP 后单一写入时机 + 稳定确认
 *  - resize 类回调（resize/onResize/setBounds/onMovedByResize）**只登记不写**：登记
 *    [freeformSeen]、刷新路由、出生默认几何学习、探针。
 *  - bounds 的唯一合法来源是用户拖动，拖动必有 DOWN/UP ⇒ 唯一写入时机 = UP 后判定：
 *      ① +[BAR_UP_JUDGE_DELAY_MS] 读当下 bounds，与 **UP 时刻快照**（[upBounds]）比较；
 *      ② 位移 ≤[MOVE_STABLE_PX] → 稳定 → 写；
 *      ③ 位移大 = 动效/惯性进行中 → 推迟再判（最多 [BOUNDS_MAX_RETRY] 次）；
 *      ④ 判定时 mode 明确 ≠5 → 关闭 → 不写（记忆保留手势前值）。
 *    **短快上划关闭在这里被结构性修复**：+700ms 时 bounds 正在飞 → 推迟；下一拍窗口已
 *    退出 freeform → 不写。不再有"动效中间帧被当移动落盘"的路径。
 *  - **关闭 = 清 pending，永不写**：scale 跳变 ≥0.92（非拖动）/ Task 销毁 → 取消该包全部
 *    待写任务。没有 closingPkgs 长效标记、没有 TTL、没有回滚——因为动效期间本来就没有
 *    任何东西被写出去，"不写"即等于"保留原记忆"。
 *  - 不清 pending 的注意点：resize 回调里的 not-freeform 帧**不取消**任何任务（fix168b：
 *    松手瞬间 mode 会抖成 1），抖动由判定任务 700ms 后的 mode 复验自然消化。
 *
 * ### 手势标记（共享）
 *  - 全局 PointerEventListener（fix78 设施保留）：DOWN 对所有活 freeform 窗置 [barFlags]，
 *    UP 清除并驱动链路 P 判定 + 链路 S 的余波窗口（[lastUpTs]）。
 *  - 15s 保险丝（UP 丢失）：barFlags 超时只清标记，不排判定。
 *
 * ### 与旧版等价保留的外围设施
 *  挂点安装（类名+方法名+参数个数匹配）、反射取数（pkgOfTask/boundsOf/isFreeformScale
 *  三级取值）、MFFS.onStartActivity 缩放注入、默认几何学习（fix124）、SB 记忆迁移（fix79）、
 *  探针与自检文件、clamp 落盘（fix164/166/177 口径）。挂点 `ATMS.resizeTask` 删除——
 *  fix191 实证本 ROM 该路径拿不到 scale（getFreeFormScale 大写 F 方法不存在，恒 skip），
 *  且它监听的缩放变化必经 setMiuiFreeformScale 事件源，属于死代码。
 */
object MiuiFreeformRecordHook {

    private const val TAG = "SingleHand/Record"

    private const val FREEFORM_MODE = 5

    /** 记下来的窗口最小边（比这小的不是"用户在用的小窗"）。 */
    private const val MIN_SIDE = 200

    /**
     * ★ fix192/195：缩放记忆**上界**。≥0.95（典型 1.0）只有两种可能：关窗/最小化动效把
     * 缩放还原成 1；或假信号（Task float 字段恒 1.0，fix194 实证）。两种都不进记忆。
     * 用户拖角实测合法值在 0.56~0.91。
     */
    private const val SCALE_MAX_SANE = 0.95f

    /** ★ fix195：抬手后缩放"弹簧余波"仍可进 debounce 落定的窗口。 */
    private const val SCALE_TAIL_MS = 1_200L

    /** ★ fix195：缩放事件流 debounce —— 最后一次事件后这么久才落盘终值。 */
    private const val SCALE_SETTLE_MS = 400L

    /** ★ fix195：拖动中实时写缩放的节流（fix194 口径）。 */
    private const val SCALE_LIVE_THROTTLE_MS = 150L

    /** ★ fix195：UP 后第一次判定的延时。 */
    private const val BAR_UP_JUDGE_DELAY_MS = 700L

    /** ★ fix195：稳定确认的位移阈值（四边差都在内 = 落定）。 */
    private const val MOVE_STABLE_PX = 24

    /** ★ fix195：稳定确认最多推迟次数（700ms × 3 ≈ 2.8s，覆盖惯性滑行）。 */
    private const val BOUNDS_MAX_RETRY = 3

    /** barFlags 超时保险（UP 事件丢失）：最多拦这么久。 */
    private const val BAR_FLAG_TIMEOUT_MS = 15_000L

    // ---------------------------------------------------------------- 挂点

    private enum class Kind { RESIZE, REMOVE }

    private data class Site(
        val label: String,
        val clazz: String,
        val method: String,
        val kind: Kind,
        /** true = 只钩本类**自己声明**的方法（不追父类），避免波及 WindowContainer 全局。 */
        val declaredOnly: Boolean = false
    )

    // 只认「类名 + 方法名」，挂该类下所有参数量的同名方法；回调里再按 thisObject 过滤。
    // ★ fix195：这些回调**只负责登记**（freeformSeen / 路由 / 默认几何学习 / 探针），
    //   不再有任何写盘路径（旧版这里分支出了 size-write / no-touch write / bar-drag 缓冲…）。
    private val SITES = listOf(
        Site("Task.resize", "com.android.server.wm.Task", "resize", Kind.RESIZE, declaredOnly = true),
        Site("Task.onResize", "com.android.server.wm.Task", "onResize", Kind.RESIZE, declaredOnly = true),
        Site("Task.onMovedByResize", "com.android.server.wm.Task", "onMovedByResize", Kind.RESIZE, declaredOnly = true),
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
    private val scaleWrites = AtomicInteger()
    @Volatile private var lastPkg = "-"
    @Volatile private var lastRect = "-"
    @Volatile private var lastSkip = "-"

    // ---------------------------------------------------------------- 写盘线程

    /** 所有记忆写都在这条线程上串行，避免与 App 进程抢写同一个文件。 */
    private val writeThread = HandlerThread("memoryfreeform-rec").also { it.start() }
    private val writeHandler = Handler(writeThread.looper)

    // ---------------------------------------------------------------- 状态（fix195 全量）

    /** 近期见过的 freeform 任务：pkg → (task, bounds 快照, 首次见到时刻)。DOWN/判定/学习用。 */
    private class Seen(val task: Any, val bounds: Rect, val at: Long)
    private val freeformSeen = ConcurrentHashMap<String, Seen>()

    /** ★ "正在拖"标记（pkg → 按下时刻）。DOWN 置 / UP 清 / 15s 保险丝。 */
    private val barFlags = ConcurrentHashMap<String, Long>()

    /** ★ 抬手时刻（pkg → ms）：链路 S 的"弹簧余波"窗口判据。 */
    private val lastUpTs = ConcurrentHashMap<String, Long>()

    /** ★ UP 时刻 bounds 快照（pkg → Rect）：链路 P 稳定确认的基准。 */
    private val upBounds = ConcurrentHashMap<String, Rect>()

    /** 链路 P：待执行的判定任务（pkg → runnable，用于取消）。 */
    private val boundsTasks = ConcurrentHashMap<String, Runnable>()

    /** 链路 S：debounce 待写值（pkg → 最后一次合法 scale 事件值）。 */
    private val pendingScale = ConcurrentHashMap<String, Float>()

    /** 链路 S：debounce 定时任务（pkg → runnable，用于取消/重置）。 */
    private val scaleTasks = ConcurrentHashMap<String, Runnable>()

    /** 缩放写盘去重：稳定键 → 上次写的值。 */
    private val lastScaleWritten = ConcurrentHashMap<String, Float>()

    /** 拖动中实时写的节流。 */
    private val lastScaleLiveAt = ConcurrentHashMap<String, Long>()

    /** 写盘路由缓存（pkg → path+key）。fix69b：App 关窗先删会话文件，onRemove 时实时算会误判。 */
    private val routeOf = ConcurrentHashMap<String, Pair<String, String>>()

    // ---- 缩放活值缓存（fix191 事件源优先；给 freeformScaleOf 与调试浮层用）----
    /** fix191：Task.setMiuiFreeformScale 事件源 → taskId 的最新真值（带时间戳）。 */
    private val liveScale = ConcurrentHashMap<Int, Pair<Long, Float>>()
    /** fix175：同 task 120ms 内复用的读缓存。 */
    private val scaleCache = ConcurrentHashMap<Int, Pair<Long, Float>>()
    /** fix191：包名 → 系统当下真实缩放（调试浮层 live 行）。 */
    private val liveByPkg = ConcurrentHashMap<String, Float>()
    /** fix191：setMiuiFreeformScale 探针节流（300ms 一条）。 */
    private val lastScaleProbeAt = ConcurrentHashMap<Int, Long>()

    // ---------------------------------------------------------------- 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (site in SITES) {
            val stat = stats.getOrPut(site.label) { Stat() }
            val cls = runCatching { XposedHelpers.findClass(site.clazz, lpparam.classLoader) }
                .getOrNull()
            if (cls == null) {
                log("${site.label}: class not found")
                continue
            }
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
        writeHandler.post { migrateSbMemory() }
        installScaleSites(lpparam)
        startFlusher()
        log("installed record hook(fix195): ${stats.values.count { it.inst > 0 }}/${SITES.size} sites")
    }

    // ------------------------------------------------ 缩放事件源 + 出生注入（fix191/194 设施）

    private fun installScaleSites(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ---- fix191：缩放事件源（系统改缩放必经；拖边角逐帧 / 动画 / 出生注入都走它）----
        runCatching {
            val taskCls = XposedHelpers.findClass("com.android.server.wm.Task", lpparam.classLoader)
            val stat = stats.getOrPut("Task.setMiuiFreeformScale") { Stat() }
            val m = runCatching {
                XposedHelpers.findMethodExact(
                    taskCls, "setMiuiFreeformScale", java.lang.Float.TYPE)
            }.getOrNull()
            if (m == null) {
                log("Task.setMiuiFreeformScale: no method")
                return@runCatching
            }
            runCatching { m.isAccessible = true }
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        stat.calls.incrementAndGet()
                        runCatching { onScaleChanged(stat, param) }
                    }
                })
                stat.inst++
            }.onFailure { log("Task.setMiuiFreeformScale: hook failed ${it.message}") }
        }.onFailure { log("Task.setMiuiFreeformScale: class not found") }

        // ---- 恢复：MiuiFreeFormManagerService.onStartActivity（HyperOS 3 实际入口）----
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.MiuiFreeFormManagerService", lpparam.classLoader)
            val stat = stats.getOrPut("MFFS.onStartActivity") { Stat() }
            for (m in locateByName(cls, "onStartActivity")) {
                runCatching { m.isAccessible = true }
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            stat.calls.incrementAndGet()
                            runCatching { onMFFSStartActivity(stat, param) }
                        }
                    })
                    stat.inst++
                }.onFailure { log("MFFS.onStartActivity: hook failed ${it.message}") }
            }
            if ((stats["MFFS.onStartActivity"]?.inst ?: 0) == 0)
                log("MFFS.onStartActivity: no method onStartActivity")
        }.onFailure { log("MFFS.onStartActivity: class not found") }
    }

    /**
     * ★ fix195：缩放事件 → 链路 S。
     * 写入的**唯一门票**是"拖动期间的事件流"（[barFlags] 或抬手余波），一票两用：
     *  - 实时写（150ms 节流）：拖动中让调试浮层 `s` 跟着 `live` 走；
     *  - debounce 落定：事件停止 400ms 后终值复验落盘（弹簧收敛值）。
     * 没有事件就永远不写 —— 纯移动、冷开动画、系统摆窗、关窗动效（值域闸另挡）全部天然免疫。
     */
    private fun onScaleChanged(stat: Stat, param: XC_MethodHook.MethodHookParam) {
        val v = param.args.getOrNull(0) as? Float ?: run { stat.skip = "no-arg"; return }
        if (v !in 0.05f..2.0f) { stat.skip = "absurd($v)"; return }
        val task = param.thisObject ?: return
        val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrNull()
            ?: run { stat.skip = "no-tid"; return }
        val now = System.currentTimeMillis()
        // 刷新活值缓存（freeformScaleOf 事件源优先；调试浮层 live 行）。
        liveScale[taskId] = now to v
        scaleCache[taskId] = now to v
        val pkg = runCatching { pkgOfTask(task) }.getOrNull()
        if (pkg != null) liveByPkg[pkg] = v
        stat.applied.incrementAndGet()

        if (pkg == null) { stat.skip = "no-pkg"; return }

        // ---- 值域闸：≥0.95 = 关窗/最小化动效还原 / 全屏假信号 → 清待写，永不写。
        //      非"正在拖"才清（用户自己把窗拉向很大时 barFlags 命中，是真拉伸不误伤）。
        if (v >= SCALE_MAX_SANE) {
            if (!barFlags.containsKey(pkg)) {
                cancelScaleWrite(pkg)
                probeMark("SCALE-EXIT pkg=$pkg v=$v → 清缩放待写（关窗动效/假信号）")
            }
            stat.skip = "sane($v)"
            probeScale(stat, taskId, pkg, v, now)
            return
        }

        val dragging = barFlags.containsKey(pkg)
        val tail = now - (lastUpTs[pkg] ?: 0L) < SCALE_TAIL_MS
        if (dragging) {
            // 拖动中实时写（fix194：s 跟着 live 走；150ms 节流 + 值去重）
            recordScaleLive(pkg, v)
            scheduleScaleSettle(pkg, v)
            stat.skip = "live"
        } else if (tail) {
            // 抬手后的弹簧余波：只进 debounce，等收敛后终值落盘
            scheduleScaleSettle(pkg, v)
            stat.skip = "tail"
        } else {
            stat.skip = "no-gesture"
        }
        probeScale(stat, taskId, pkg, v, now)
    }

    /** 探针节流（300ms 一条）+ live 快照文件（调试浮层）。 */
    private fun probeScale(stat: Stat, taskId: Int, pkg: String?, v: Float, now: Long) {
        val last = lastScaleProbeAt[taskId] ?: 0L
        if (now - last <= 300L) return
        lastScaleProbeAt[taskId] = now
        probeMark("SCALE-CHG tid=$taskId pkg=$pkg v=$v bar=${pkg?.let { barFlags.containsKey(it) } ?: false}")
        val snap = liveByPkg.entries.joinToString("\n") { "${it.key}=${it.value}" }
        writeHandler.post {
            runCatching { HookContract.writeSnapshotFile(HookContract.LIVE_SCALE_PATH, snap) }
        }
    }

    /** 拖动中实时写缩放（150ms 节流；值域已在调用点保证 <[SCALE_MAX_SANE]）。 */
    private fun recordScaleLive(pkg: String, v: Float) {
        val now = System.currentTimeMillis()
        val last = lastScaleLiveAt[pkg] ?: 0L
        if (now - last < SCALE_LIVE_THROTTLE_MS) return
        lastScaleLiveAt[pkg] = now
        writeScale(pkg, v, "live")
    }

    /** 链路 S debounce：重置待写值与定时器（最后一次事件后 [SCALE_SETTLE_MS] 落终值）。 */
    private fun scheduleScaleSettle(pkg: String, v: Float) {
        pendingScale[pkg] = v
        scaleTasks.remove(pkg)?.let { writeHandler.removeCallbacks(it) }
        val r = Runnable {
            scaleTasks.remove(pkg)
            val v2 = pendingScale.remove(pkg) ?: return@Runnable
            writeScale(pkg, v2, "settle")
        }
        scaleTasks[pkg] = r
        writeHandler.postDelayed(r, SCALE_SETTLE_MS)
    }

    private fun cancelScaleWrite(pkg: String) {
        scaleTasks.remove(pkg)?.let { writeHandler.removeCallbacks(it) }
        pendingScale.remove(pkg)
    }

    /**
     * 缩放记忆落盘（终值复验三道：开关 / 值域 / 窗口仍在 freeform）。
     * ★ fix195：这是链路 S 唯一的写盘函数。
     */
    private fun writeScale(pkg: String, scale: Float, via: String) {
        if (!HookContract.rememberSizeEnabled()) return
        if (scale.isNaN() || scale !in 0.05f..SCALE_MAX_SANE) return
        val s = freeformSeen[pkg] ?: return
        val mode = runCatching { XposedHelpers.callMethod(s.task, "getWindowingMode") as? Int }
            .getOrNull()
        if (mode != null && mode != FREEFORM_MODE) {
            probeMark("SCALE-REJECT pkg=$pkg v=$scale via=$via（mode=$mode 已非小窗）")
            return
        }
        val sz = readDisplaySize()
        val land = sz != null && sz[0] > sz[1]
        val key = HookContract.memoryKey(pkg, land)
        val prev = lastScaleWritten[key] ?: HookContract.readWindowScale(pkg, land)
        if (prev != null && abs(prev - scale) < 0.005f) return
        lastScaleWritten[key] = scale
        scaleWrites.incrementAndGet()
        probeMark("SCALE-WRITE pkg=$pkg scale=$scale via=$via")
        log("缩放记忆 $pkg -> $scale（via=$via）")
        writeHandler.post { HookContract.writeWindowScale(pkg, land, scale) }
    }

    /** ★ fix194→195：出生注入记忆缩放 + 无条件清 pending（窗口生命周期重开）。 */
    private fun onMFFSStartActivity(stat: Stat, param: XC_MethodHook.MethodHookParam) {
        val options = param.args.getOrNull(1) ?: run { stat.skip = "no-opts"; return }
        val mode = runCatching {
            XposedHelpers.callMethod(options, "getLaunchWindowingMode") as? Int
        }.getOrNull()
        if (mode != 5) { stat.skip = "not-freeform($mode)"; return }
        val pkg = param.args.getOrNull(0)?.let { pkgOfTask(it) }
            ?: run { stat.skip = "no-pkg"; return }
        // 窗口出生 = 上一轮的所有待写作废（无标记架构：清 pending 即是"解除关闭态"）。
        cancelScaleWrite(pkg)
        cancelBoundsJudge(pkg)
        lastUpTs.remove(pkg)
        if (HookContract.rememberSizeEnabled()) {
            val sz = readDisplaySize()
            val land = sz != null && sz[0] > sz[1]
            val scale = HookContract.readWindowScale(pkg, land)
            if (scale != null) {
                val ok = runCatching {
                    val injector = XposedHelpers.callMethod(options, "getActivityOptionsInjector")
                    runCatching { XposedHelpers.callMethod(injector, "setFreeformScale", scale) }
                        .onFailure { first ->
                            val f = injector.javaClass.declaredFields.firstOrNull { it.name == "mFreeformScale" }
                            if (f != null) {
                                f.isAccessible = true
                                f.setFloat(injector, scale)
                            } else throw first
                        }
                }.isSuccess
                stat.applied.incrementAndGet()
                probeMark("MFFS-START pkg=$pkg applyScale=$scale ok=$ok")
                if (ok) log("恢复小窗缩放 $pkg -> $scale")
                else stat.skip = "injector-fail"
                return
            }
        }
        stat.skip = if (stat.skip == "-") "no-scale-mem" else stat.skip
    }

    // ---------------------------------------------------------------- resize 类回调（只登记）

    private fun onResize(stat: Stat, param: XC_MethodHook.MethodHookParam) {
        seen.incrementAndGet()
        stat.calls.incrementAndGet()
        val task = param.thisObject
        if (task?.javaClass?.name != "com.android.server.wm.Task") {
            stat.skip = "not-task"
            return
        }
        ensurePointerListener(task)
        // ★ fix195：退出 freeform 的帧**不再钉任何标记、不清任何 pending**（fix168b：
        //   松手瞬间 mode 会抖成 1）。关闭的确认由链路 P 判定任务的 mode 复验完成。
        if (!isFreeformTask(task)) {
            stat.skip = "not-freeform"
            return
        }
        val pkg = pkgOfTask(task) ?: run { stat.skip = "no-pkg"; return }
        val b = boundsOf(task) ?: run { stat.skip = "no-bounds"; return }
        val now = System.currentTimeMillis()
        val prevSeen = freeformSeen[pkg]
        freeformSeen[pkg] = Seen(task, Rect(b), prevSeen?.at ?: now)
        stat.applied.incrementAndGet()

        val sz = readDisplaySize()
        val land = sz != null && sz[0] > sz[1]
        routeOf[pkg] = computeRoute(pkg, land)

        // barFlags 15s 保险丝（UP 丢失）：只清标记。缩放已由拖动期 debounce 落盘。
        barFlags[pkg]?.let { ts ->
            if (now - ts > BAR_FLAG_TIMEOUT_MS) {
                barFlags.remove(pkg, ts)
                probeMark("BAR-FLAG 超时清 pkg=$pkg")
            }
        }

        val methodName = (param.method as? java.lang.reflect.Method)?.name
        // ★ fix124：出生帧学「系统默认几何」（Task.resize 是我们 am task resize 的通道，不学）。
        if (prevSeen == null && methodName != "resize") {
            maybeLearnDefault(pkg, Rect(b), sz, land)
        }
        lastPkg = pkg
        lastRect = "${b.left},${b.top},${b.right},${b.bottom}"
        stat.skip = "$methodName(登记)"
    }

    // ---------------------------------------------------------------- 销毁（只清不写）

    private fun onRemove(stat: Stat, param: XC_MethodHook.MethodHookParam) {
        seen.incrementAndGet()
        stat.calls.incrementAndGet()
        val task = param.thisObject
        if (task?.javaClass?.name != "com.android.server.wm.Task") {
            stat.skip = "not-task"
            return
        }
        val pkg = pkgOfTask(task)
        if (pkg == null) { stat.skip = "no-pkg"; return }
        // ★ fix195：关闭 = 清 pending，永不写。动效期间没有任何东西被写出去，
        //   记忆里留着的就是手势前的参照位置 —— 不需要回滚（旧版 writeSnapshotAsClose 删除）。
        cancelScaleWrite(pkg)
        cancelBoundsJudge(pkg)
        upBounds.remove(pkg)
        lastUpTs.remove(pkg)
        freeformSeen.remove(pkg)
        routeOf.remove(pkg)
        stat.applied.incrementAndGet()
        stat.skip = "clear-pending"
        probeMark("REMOVE pkg=$pkg → 清全部待写（不写，保留原记忆）")
    }

    // ---------------------------------------------------------------- 手势（fix78 设施 + fix195 判定）

    private val barDowns = AtomicInteger()
    private val barHits = AtomicInteger()
    private val barUps = AtomicInteger()

    /** 已注册的全局 PointerEventListener（非空 = 注册成功，不再重复注册）。 */
    @Volatile private var pointerListener: Any? = null
    private var pointerRegTried = 0
    private var appClassLoader: ClassLoader? = null
    @Volatile private var pointerErr: String = ""

    /**
     * ★ fix78d：懒注册全局 pointer 监听（自适应：不猜类名/方法名）。
     * 实现与 fix78e 版一致，见各探针文件；fix195 只换回调内容。
     */
    private fun ensurePointerListener(task: Any?) {
        if (pointerListener != null) return
        synchronized(this) {
            if (pointerListener != null) return
            pointerRegTried++
            if (pointerRegTried > 300) return
            val cl = appClassLoader
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
                else regM.invoke(regTarget, listener, 0)
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
                log("已注册 PointerEventListener（api=${regTarget.javaClass.simpleName}.${regM.name}/${regM.parameterTypes.size}p）")
            } else if (pointerRegTried % 50 == 0) {
                log("PointerEventListener 注册失败（$pointerRegTried 次）：$pointerErr")
            }
        }
    }

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
                val wms = runCatching { XposedHelpers.getObjectField(target, "this\$0") }.getOrNull()
                if (wms != null) {
                    sb.append("== ${wms.javaClass.name} ==\n")
                    for (m in wms.javaClass.methods.sortedBy { it.name }) {
                        if (m.name.contains("Pointer")) {
                            sb.append("${m.name}(${m.parameterTypes.joinToString(",") { it.name }})\n")
                        }
                    }
                }
            }
            val dump = sb.toString()
            if (dump == lastProbeDump) return
            lastProbeDump = dump
            val f = File(HookContract.RECORD_STATE_PATH + ".wm")
            f.writeText(dump)
            runCatching { f.setReadable(true, false) }
        }
    }

    /**
     * ★ fix195：DOWN = 置"正在拖"标记（对所有活 freeform 窗；不再做按下快照 ——
     * 旧版 barSnapshot/downScale 是"写-回滚"模式的燃料，新架构没有回滚也就不需要快照）。
     */
    private fun onPointerEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                barDowns.incrementAndGet()
                probeMark("BAR-DOWN seen=${freeformSeen.entries.joinToString("|") {
                    "${it.key}:${if (isFreeformTask(it.value.task)) "ff" else "no-ff" }" }}")
                for ((pkg, s) in freeformSeen) {
                    if (!isFreeformTask(s.task)) continue
                    if (barFlags.putIfAbsent(pkg, System.currentTimeMillis()) == null) {
                        barHits.incrementAndGet()
                        probeMark("BAR-DOWN mark pkg=$pkg")
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (barFlags.isEmpty()) return
                barUps.incrementAndGet()
                probeMark("BAR-UP 开始 ${barUps.get()}")
                for ((pkg, ts) in barFlags) {
                    barFlags.remove(pkg, ts)
                    onGestureUp(pkg)
                }
            }
        }
    }

    /** UP：记录抬手时刻与 bounds 快照，排链路 P 判定（链路 S 由余波窗口自然覆盖）。 */
    private fun onGestureUp(pkg: String) {
        val now = System.currentTimeMillis()
        lastUpTs[pkg] = now
        val s = freeformSeen[pkg] ?: return
        val b = runCatching { boundsOf(s.task) }.getOrNull() ?: s.bounds
        upBounds[pkg] = Rect(b)
        scheduleBoundsJudge(pkg, 0)
    }

    private fun scheduleBoundsJudge(pkg: String, attempt: Int) {
        boundsTasks.remove(pkg)?.let { writeHandler.removeCallbacks(it) }
        val r = Runnable { boundsJudge(pkg, attempt) }
        boundsTasks[pkg] = r
        writeHandler.postDelayed(r, BAR_UP_JUDGE_DELAY_MS)
    }

    private fun cancelBoundsJudge(pkg: String) {
        boundsTasks.remove(pkg)?.let { writeHandler.removeCallbacks(it) }
        upBounds.remove(pkg)
    }

    /**
     * ★ fix195：链路 P 判定 —— bounds 的唯一写入时机。
     *  ① 新手势已开始（barFlags 命中）→ 让位（新一轮 UP 会重排）；
     *  ② 窗口没了 / mode 明确非 freeform → 关闭 → 不写（保留原记忆）；
     *  ③ 当下 bounds 与 UP 快照四边差都 ≤[MOVE_STABLE_PX] → 稳定 → 写；
     *  ④ 否则动效/惯性进行中 → 推迟再判（最多 [BOUNDS_MAX_RETRY] 次，防无限推迟）。
     * 短快上划关闭：+700ms bounds 在飞 → 推迟；下一拍 mode≠5 → 不写。★ 结构性修复
     * 「短快速上划关闭位置错乱」（旧版 #0 无法区分"稳定活着"与"动效中"，动效帧被当移动落盘）。
     */
    private fun boundsJudge(pkg: String, attempt: Int) {
        boundsTasks.remove(pkg)
        if (barFlags.containsKey(pkg)) {
            probeMark("BAR-UP 判定#$attempt pkg=$pkg 新手势进行中 → 让位")
            return
        }
        val s = freeformSeen[pkg]
        if (s == null) {
            upBounds.remove(pkg)
            probeMark("BAR-UP 判定#$attempt pkg=$pkg 窗口已无 → 不写")
            return
        }
        val mode = runCatching { XposedHelpers.callMethod(s.task, "getWindowingMode") as? Int }
            .getOrNull()
        if (mode != null && mode != FREEFORM_MODE) {
            upBounds.remove(pkg)
            probeMark("BAR-UP 判定#$attempt pkg=$pkg mode=$mode → 关闭，不写（保留原记忆）")
            return
        }
        val nowB = runCatching { boundsOf(s.task) }.getOrNull()
        if (nowB == null) { upBounds.remove(pkg); return }
        val up0 = upBounds[pkg]
        val stable = up0 == null || (
            abs(nowB.left - up0.left) <= MOVE_STABLE_PX &&
                abs(nowB.top - up0.top) <= MOVE_STABLE_PX &&
                abs(nowB.right - up0.right) <= MOVE_STABLE_PX &&
                abs(nowB.bottom - up0.bottom) <= MOVE_STABLE_PX
            )
        if (!stable && attempt < BOUNDS_MAX_RETRY) {
            probeMark("BAR-UP 判定#$attempt pkg=$pkg 未落定(${nowB.left},${nowB.top} vs " +
                "${up0.left},${up0.top}) → 推迟")
            scheduleBoundsJudge(pkg, attempt + 1)
            return
        }
        upBounds.remove(pkg)
        flushMove(pkg, Rect(nowB))
    }

    /** 链路 P 落盘（clamp + 闸后写主记忆）。 */
    private fun flushMove(pkg: String, rectIn: Rect) {
        val w = rectIn.width()
        val h = rectIn.height()
        if (w < MIN_SIDE || h < MIN_SIDE) {
            probeMark("MOVE-REJECT pkg=$pkg 太小 ${w}x$h")
            return
        }
        val sz = readDisplaySize()
        if (sz != null) {
            val (sw, sh) = sz
            if (rectIn.left < -sw * 2 || rectIn.top < -sh * 2 ||
                rectIn.right > sw * 4 || rectIn.bottom > sh * 4
            ) {
                lastSkip = "$pkg:absurd($rectIn)"
                probeMark("MOVE-REJECT pkg=$pkg 荒谬几何 $rectIn")
                return
            }
            // 退出动效的全屏帧（fix77b 口径，双保险 —— 稳定确认通常已挡住）
            if (w >= sw - 40 && h >= sh - 40) {
                lastSkip = "$pkg:fullscreen($rectIn)"
                probeMark("MOVE-REJECT pkg=$pkg 全屏帧 $rectIn")
                return
            }
        }
        val land = sz != null && sz[0] > sz[1]
        val route = routeOf[pkg] ?: computeRoute(pkg, land)
        routeOf[pkg] = route
        var out = rectIn
        if (sz != null) {
            val fs = HookContract.effectiveScale(pkg, land)
            val fitted = WindowSizing.clampRect(
                intArrayOf(rectIn.left, rectIn.top, rectIn.right, rectIn.bottom),
                sz[0], sz[1],
                if (land) statusBarHeightSystem() else 0, fs,
                bottomSafe = WindowSizing.GESTURE_BOTTOM_GAP,
                sideSafe = WindowSizing.sideSafeSystemPx()
            )
            if (fitted != null) out = Rect(fitted[0], fitted[1], fitted[2], fitted[3])
        }
        val key = route.second
        HookContract.putMemoryRect(route.first, key, "${out.left},${out.top},${out.right},${out.bottom}")
        written.incrementAndGet()
        lastPkg = pkg
        lastRect = "${out.left},${out.top},${out.right},${out.bottom}"
        lastSkip = if (out != rectIn) "clamp($rectIn->$out)" else "-"
        probeMark("WRITE MAIN $key=${out.left},${out.top},${out.right},${out.bottom}（移动落定）")
        log("位置记忆 $pkg -> ${out.left},${out.top},${out.right},${out.bottom}")
    }

    // ---------------------------------------------------------------- 反射取数

    /** 安全调用返回 Boolean 的方法；方法不存在 / 反射失败返回 null。 */
    private fun boolOf(t: Any, name: String): Boolean? =
        runCatching { XposedHelpers.callMethod(t, name) as? Boolean }.getOrNull()

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

    // ---- freeformScale 三级取值（fix191/194：事件源优先；Task float 字段在本机恒 1.0 是假信号）----

    @Volatile private var cachedFfms: Any? = null
    @Volatile private var mGetStack: java.lang.reflect.Method? = null
    @Volatile private var taskScaleField: String? = null
    @Volatile private var mGetScaleByTask: java.lang.reflect.Method? = null

    private fun norm(r: Any?): Float = when (r) {
        is Float -> if (r in 0.05f..2.0f) r else Float.NaN
        is Number -> { val f = r.toFloat(); if (f in 0.05f..2.0f) f else Float.NaN }
        else -> Float.NaN
    }

    private fun readTaskScaleField(task: Any): Float {
        taskScaleField?.let { n ->
            val v = runCatching { XposedHelpers.getFloatField(task, n) }.getOrDefault(Float.NaN)
            if (!v.isNaN() && v in 0.05f..SCALE_MAX_SANE) return v
            taskScaleField = null
        }
        for (n in listOf("mMiuiFreeformScale", "miuiFreeformScale", "mFreeformScale", "freeformScale")) {
            val v = runCatching { XposedHelpers.getFloatField(task, n) }.getOrDefault(Float.NaN)
            if (!v.isNaN() && v in 0.05f..SCALE_MAX_SANE) { taskScaleField = n; return v }
        }
        return Float.NaN
    }

    private fun readManagerScale(task: Any, taskId: Int): Float {
        val svc = cachedFfms ?: runCatching {
            val atms = XposedHelpers.getObjectField(task, "mAtmService")
            XposedHelpers.getObjectField(atms, "mMiuiFreeFormManagerService")
        }.getOrNull()?.also { cachedFfms = it } ?: return Float.NaN
        val mg = mGetScaleByTask ?: runCatching {
            svc.javaClass.getMethod("getFreeformScale", Int::class.javaPrimitiveType)
        }.getOrNull()?.also { mGetScaleByTask = it } ?: return Float.NaN
        return runCatching { norm(mg.invoke(svc, taskId)) }.getOrDefault(Float.NaN)
    }

    private fun readStackScale(task: Any, taskId: Int): Float {
        val svc = cachedFfms ?: return Float.NaN
        val ms = mGetStack ?: runCatching {
            svc.javaClass.getMethod("getMiuiFreeFormActivityStack", Int::class.javaPrimitiveType)
        }.getOrNull()?.also { mGetStack = it } ?: return Float.NaN
        val stack = runCatching { ms.invoke(svc, taskId) }.getOrNull() ?: return Float.NaN
        for (name in listOf("getFreeformScale", "getFreeFormScale")) {
            val v = runCatching {
                norm(stack.javaClass.getMethod(name).invoke(stack))
            }.getOrDefault(Float.NaN)
            if (!v.isNaN()) return v
        }
        return Float.NaN
    }

    private fun freeformScaleOf(task: Any): Float {
        val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrNull()
            ?: return Float.NaN
        val now = System.currentTimeMillis()
        liveScale[taskId]?.let { (ts, v) -> if (now - ts < 5_000L) return v }
        scaleCache[taskId]?.let { (ts, v) -> if (now - ts < 120L) return v }
        var v = readTaskScaleField(task)
        if (v.isNaN()) v = readManagerScale(task, taskId)
        if (v.isNaN()) v = readStackScale(task, taskId)
        if (!v.isNaN()) scaleCache[taskId] = now to v
        return v
    }

    private fun isFreeformTask(t: Any): Boolean {
        val m = runCatching { XposedHelpers.callMethod(t, "getWindowingMode") as? Int }.getOrNull()
        return m == FREEFORM_MODE
    }

    private fun boundsOf(t: Any): Rect? {
        return runCatching { XposedHelpers.callMethod(t, "getBounds") as? Rect }.getOrNull()
    }

    private fun pkgOfTask(t: Any): String? {
        for (f in listOf("realActivity", "origActivity")) {
            val c = runCatching { XposedHelpers.getObjectField(t, f) }.getOrNull()
            val p = runCatching { XposedHelpers.callMethod(c, "getPackageName") as? String }
                .getOrNull()
            if (!p.isNullOrEmpty()) return p
        }
        for (f in listOf("mTopActivity", "topActivity")) {
            val c = runCatching { XposedHelpers.getObjectField(t, f) }.getOrNull()
            val p = runCatching { XposedHelpers.callMethod(c, "getPackageName") as? String }
                .getOrNull()
            if (!p.isNullOrEmpty()) return p
        }
        val i = runCatching { XposedHelpers.getObjectField(t, "intent") }.getOrNull()
        val pi = runCatching { XposedHelpers.callMethod(i, "getPackage") as? String }.getOrNull()
        if (!pi.isNullOrEmpty()) return pi
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

    // ---------------------------------------------------------------- 方法定位 / 探针

    /** 按「名字」在类及其父类里找所有参数量的同名方法（不校验参数类型，抗 ROM 改签名）。 */
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

    /** 只找本类自己声明的同名方法（不追父类）。 */
    private fun locateDeclared(cls: Class<*>, name: String): List<java.lang.reflect.Method> {
        return cls.declaredMethods.filter { it.name == name }
    }

    /** 把 Task 上与几何/销毁相关的方法签名写进探针文件（抗改签名实查）。 */
    private fun probeSignatures(cls: Class<*>) {
        runCatching {
            val sb = StringBuilder()
            var c: Class<*>? = cls
            while (c != null && c.name != "java.lang.Object") {
                for (m in c.declaredMethods) {
                    val n = m.name
                    if (n.contains("resize") || n.contains("setBounds") || n.contains("moveTo") ||
                        n.contains("onResize") || n.contains("onMoved") || n.contains("remove") ||
                        n.contains("FreeformScale", ignoreCase = true)
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

    // ---------------------------------------------------------------- 默认几何学习（fix124 保留）

    /**
     * ★ fix124：从「系统入口出生帧」学澎湃的默认小窗几何（保留原逻辑，只在
     * isFirstSeen && 非 resize 通道时触发；写盘在 writeHandler 串行）。
     */
    private fun maybeLearnDefault(pkg: String, b: Rect, sz: IntArray?, land: Boolean) {
        if (sz == null) return
        val w = b.width()
        val h = b.height()
        if (w < MIN_SIDE || h < MIN_SIDE) return
        if (w >= sz[0] - 40 && h >= sz[1] - 40) return // 退出动效全屏帧
        if (b.left in 20..60 && b.top in 100..160) return // 失焦重摆角族
        val key = HookContract.defaultKey(land)
        val memKey = HookContract.memoryKey(pkg, land)
        writeHandler.post {
            runCatching {
                if (HookContract.selfLaunchFresh(pkg)) {
                    log("默认几何学习跳过 $pkg：自启动窗口（几何不是系统默认）")
                    return@runCatching
                }
                if (HookContract.readMemoryRect(HookContract.WINDOW_MEMORY_PATH, memKey) != null) return@runCatching
                if (HookContract.readMemoryRect(HookContract.DEFAULT_RECT_PATH, key) != null) return@runCatching
                HookContract.putMemoryRect(
                    HookContract.DEFAULT_RECT_PATH, key,
                    "${b.left},${b.top},${b.right},${b.bottom}"
                )
                log("学得系统默认几何($key) $pkg -> ${b.left},${b.top},${b.right},${b.bottom}")
            }.onFailure { log("默认几何学习失败 ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- 键与迁移（fix79 保留）

    private fun computeRoute(pkg: String, landscape: Boolean): Pair<String, String> =
        HookContract.WINDOW_MEMORY_PATH to HookContract.memoryKey(pkg, landscape)

    /** ★ fix79：一次性把旧 SB 文件并进主文件（幂等）。 */
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

    /** 系统状态栏高度（px）；读不到返回 0。 */
    private fun statusBarHeightSystem(): Int = runCatching {
        val r = android.content.res.Resources.getSystem()
        val id = r.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) r.getDimensionPixelSize(id).coerceIn(0, 200) else 0
    }.getOrDefault(0)

    /** 当前屏幕尺寸（跟随旋转）。读不到返回 null。 */
    private fun readDisplaySize(): IntArray? {
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
                    sb.append("installed=$n/${SITES.size} seen=${seen.get()} " +
                        "rectWrites=${written.get()} scaleWrites=${scaleWrites.get()}\n")
                    sb.append("last=$lastPkg rect=$lastRect skip=$lastSkip\n")
                    sb.append("bar(down=${barDowns.get()} hits=${barHits.get()} ups=${barUps.get()}" +
                        " flags=${barFlags.keys} plisten=${if (pointerListener != null) 1 else 0}" +
                        (if (pointerErr.isNotEmpty()) " err=$pointerErr" else "") + ")\n")
                    sb.append("pending(scale=${pendingScale.keys} bounds=${boundsTasks.keys})\n")
                    for (s in SITES) sb.append("${s.label} ${stats[s.label] ?: Stat()}\n")
                    sb.append("MFFS.onStartActivity ${stats["MFFS.onStartActivity"] ?: Stat()}\n")
                    sb.append("Task.setMiuiFreeformScale " +
                        "${stats["Task.setMiuiFreeformScale"] ?: Stat()}\n")
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
