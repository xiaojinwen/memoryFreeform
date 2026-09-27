package xiaojw.memoryFreeform.hook

import android.graphics.Rect
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ★ fix46：**出生即目标几何**（第二版 —— fix45 的单点赌签名已被真机证伪）
 *
 * ## fix45 为什么失败
 *
 * ```
 * adb shell "su -c 'cat /data/system/memoryfreeform_birth.state'"
 *   installed=1 calls=0 applied=0 installed=1
 * ```
 * `installed=1`（挂上了）但 `calls=0`（从未被调用），而且末尾那个重复的 `installed=1`
 * 说明 state 文件**自 install 之后就没被写过一次** —— 方法一次都没进。
 *
 * 反编译 `miui-services.jar` 查了全部 4 处引用，真相是：
 * `updateBoundsAndScaleByOptions` **只有一个调用点**，位于
 * `ActivityTaskSupervisorImpl.scheduleStartActivityFromRecents(...)` —— 而且调用前还有
 * 两道闸：`MiuiFreeFormManagerServiceStub.isInEludeStatus(...)` 为假就直接跳过，
 * 且必须 `MiuiFreeformServiceStub.isDeskTopModeActive()` 为真才会调。
 * 即：**只有在「桌面模式」下从最近任务启动**才走这条路。我们用
 * `am start-activity --windowingMode 5` 冷启动，压根不经过它。
 *
 * ## 真正的落地点（重新挖）
 *
 * 全 dex 只有 5 处 `ActivityOptions.setLaunchBounds`，归属四个方法：
 *  - `MiuiFreeFormManagerService.updateBoundsAndScaleByOptions` —— 只走 recents（已证伪）
 *  - `MiuiFreeformServiceImpl.addLaunchFreeformActivityOptionIfNeed` —— 通用启动链路，
 *    但设置 bounds 的那一段被 `MiuiDesktopModeUtils.isDesktopActive()` 挡着
 *  - `MiuiDesktopModeLaunchParamsModifier.calculate`（private，被 `onCalculate` 调用）
 *    —— 是 `LaunchParamsModifier` 接口实现，经 `LaunchParamsController.registerModifier`
 *    注册；同样被 `isDesktopActive()` 挡住（未激活时整个方法 return 0）
 *  - `MiuiMultiWindowUtils` 系 —— 提供系统默认矩形
 *
 * 结论：**MIUI 这一整套"出生几何"逻辑都挂在"桌面模式"开关后面**，本机没激活，
 * 所以出生几何落到的是 AOSP `LaunchParamsController` 的默认结果。
 *
 * ## 这一版怎么做：不赌单点，末点定音
 *
 * 与其继续猜哪个点会被调用，这里把**候选点全部挂上**，每个点独立统计
 * `inst/calls/applied`，并在**最下游那个点**（`LaunchParamsController.calculate`
 * 的 after）写最终 bounds —— 无论中间谁算过，最后都由我们定音。
 * 写入的是同一个矩形，多点重复写入幂等无害。
 *
 * 方法签名**不硬编码**：按「类名 + 方法名 + 参数个数」反射匹配（`declaredMethods`
 * 递归父类）。ROM 改签名也不会 `installed=0`，最坏只是少挂一个点。
 *
 * ## 顺带打通「记忆之前的位置」
 *
 * `LaunchParamsController` 上有 MIUI 加的三个方法（AOSP 没有）：
 * ```
 * hasFreeformDesktopMemory(Task)Z   getFreeformLastPosition(Task)Rect   getFreeformLastScale(Task)F
 * ```
 * 这就是澎湃官方的小窗位置记忆通道，且在 `updateBoundsAndScaleByOptions` 里
 * **优先级高于传参**（分支①在分支②之前）。这里对目标包伪造这三个返回值，
 * 于是「出生即目标几何」与「记住上次位置」走的是同一条系统通道。
 */
object MiuiFreeFormBirthHook {

    private const val TAG = "SingleHand/Birth"

    /** 目标文件新鲜度闸门：超过这个时长的目标一律作废（防陈旧矩形乱摆位）。 */
    private const val TARGET_TTL_MS = 60_000L

    /** 读目标文件节流：启动链路很热，不能每次都开一次文件。 */
    private const val READ_INTERVAL_MS = 300L

    private const val FREEFORM_MODE = 5

    // ---------------------------------------------------------------- 挂点定义

    private enum class Kind {
        /** 参数里带 ActivityOptions：after 里对它 setLaunchBounds（+ 强制 mode 5） */
        OPTIONS,
        /** 参数里带 LaunchParams：after 里写 mBounds（+ mWindowingMode=5） */
        PARAMS,
        /** 官方记忆通道：before 直接替换返回值 */
        MEM_BOOL,
        MEM_RECT,
        MEM_FLOAT,
        /** 只埋点统计，不改行为 */
        PROBE
    }

    private data class Site(
        val label: String,
        val clazz: String,
        val method: String,
        val arity: Int,
        val kind: Kind
    )

    private val SITES = listOf(
        // ★ 真机实测（fix46 首次开机）：**唯一真正生效的点** —— calls=16 applied=3。
        //   AOSP 内置 modifier，每次 Activity 启动都过，outParams 就是最终落地 bounds。
        Site("TaskLaunchParamsModifier.onCalculate", "com.android.server.wm.TaskLaunchParamsModifier",
            "onCalculate", 9, Kind.PARAMS),
        // MIUI 通用启动链路：给 options 补 freeform 属性，返回 options。
        // fix46 实测 calls=4 applied=0 —— 那是 OPTIONS 分支取 args[0](RootWindowContainer)
        // 而不是 ActivityOptions 的 bug，本版已修。★ 真机（fix88 读 birth.state）：
        //   calls=74 applied=51 —— 第二个真正生效的点。
        Site("addLaunchFreeformActivityOptionIfNeed", "com.android.server.wm.MiuiFreeformServiceImpl",
            "addLaunchFreeformActivityOptionIfNeed", 8, Kind.OPTIONS),
        // ★ fix87 + fix88：以下 5 个点已删除（真机 `memoryfreeform_birth.state` 实证是死点）：
        //   LaunchParamsController.calculate/8  inst=1 calls=296 applied=**0**（挂上了但
        //     applyToLaunchParams 在这条签名里找不到 LaunchParams 参数，一次没写成；
        //     它还是调用量最大的一个点，删掉省掉几百次回调 + 每次的文件/反射开销）
        //   LaunchParamsController.calculate/9、/10  inst=0（签名对不上，压根没挂上）
        //   hasFreeformDesktopMemory  calls=58 applied=0 skip=no-pkg（Task 参数取不到包名）
        //   getFreeformLastPosition / getFreeformLastScale  calls=0（官方记忆通道没走通）
        //   —— 出生几何实际由上面两个点定音，删这些不影响效果。
        // ★ fix87：这两个点已删除 —— fix46 真机实证是死路，留着只是白挂两个 inline hook：
        //   updateBoundsAndScaleByOptions  inst=1 calls=0（只有「桌面模式下从最近任务启动」才走）
        //   DesktopModeModifier.onCalculate inst=1 calls=0（isDesktopActive 未激活，整个方法 return 0）
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

    /** after 记下"这次要覆盖"，供写 state 用（每个线程独立，防并发串味）。 */
    private val armed = ThreadLocal<IntArray>()

    /** 目标来源：① App 启动脚本写的 `birth_target`（带 done、60s TTL）；② 持久记忆文件；
     *  ③ ★ fix124：学得的「系统默认几何」（无记忆 + 自启动时兜底，见 HookContract）。 */
    private enum class TargetSource { BIRTH_TARGET, MEMORY, DEFAULT }

    private data class Target(
        val pkg: String, val l: Int, val t: Int, val r: Int, val b: Int,
        val ts: Long, val source: TargetSource
    )

    @Volatile private var cachedAt = 0L
    @Volatile private var cachedRaw = ""

    /** 记忆文件变化很少，缓存可以久一点，省掉恢复路径上的重复 IO。 */
    private const val MEM_READ_INTERVAL_MS = 3_000L
    @Volatile private var memCachedAt = 0L
    @Volatile private var memCachedRaw = ""

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
            val methods = locate(cls, site.method, site.arity)
            if (methods.isEmpty()) {
                log("${site.label}: no method ${site.method}/${site.arity}")
                continue
            }
            for (m in methods) {
                runCatching { m.isAccessible = true }
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching { before(site, stat, param) }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching { after(site, stat, param) }
                        }
                    })
                    stat.inst++
                }.onFailure { log("${site.label}: hook failed ${it.message}") }
            }
        }
        startFlusher()
        log("installed ${stats.values.count { it.inst > 0 }}/${SITES.size} sites")
    }

    /** 按「名字 + 参数个数」在类及其父类里找方法（不校验参数类型，抗 ROM 改签名）。 */
    private fun locate(cls: Class<*>, name: String, arity: Int): List<Method> {
        val out = LinkedHashMap<String, Method>()
        var c: Class<*>? = cls
        while (c != null && c.name != "java.lang.Object") {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterTypes.size == arity) {
                    out[m.toString()] = m
                }
            }
            c = c.superclass
        }
        return out.values.toList()
    }

    // ---------------------------------------------------------------- 拦截

    /** 记跳过原因：全局一份（最新）+ 每个挂点一份（该点最后一次），逐个点排查才够用。 */
    private fun skip(stat: Stat, site: Site, reason: String) {
        stat.skip = reason
        lastSkip = "${site.label}:$reason"
    }

    private fun before(site: Site, stat: Stat, param: XC_MethodHook.MethodHookParam) {
        seen.incrementAndGet()
        stat.calls.incrementAndGet()

        val pkg = pkgOf(param.args)
        if (pkg.isNullOrEmpty()) {
            skip(stat, site, "no-pkg")
            return
        }
        val t = targetFor(pkg, param.args)
        if (t == null) {
            skip(stat, site, "no-target($pkg)")
            return
        }
        // ★ fix63：**这个包屏幕上已经有一个 freeform 小窗** ⇒ 这次是"恢复 / 挂起后点开"，
        //   几何是用户自己弄出来的（位置 + 澎湃缩放把手），绝不能再拿记忆里的旧矩形去顶。
        //   这条就是"点一下小窗，尺寸/位置突然跳回去"的直接原因（真机实测见方法注释）。
        if (isResumingExisting(param.args)) {
            skip(stat, site, "已存在小窗(恢复)不压几何")
            return
        }
        if (!isFreeform(param.args)) {
            skip(stat, site, "not-freeform")
            return
        }

        lastPkg = pkg
        armed.set(intArrayOf(t.l, t.t, t.r, t.b))

        when (site.kind) {
            // 官方记忆通道：直接顶掉返回值（before 里写 result，原方法不再执行）
            Kind.MEM_BOOL -> param.result = true
            Kind.MEM_RECT -> param.result = Rect(t.l, t.t, t.r, t.b)
            Kind.MEM_FLOAT -> param.result = 1.0f
            // 旧点 fix45 的老逻辑：留着 launchBounds 会被系统默认顶掉，先清空
            Kind.OPTIONS -> if (site.method == "updateBoundsAndScaleByOptions") clearBounds(param.args)
            else -> Unit
        }
        if (site.kind == Kind.MEM_BOOL || site.kind == Kind.MEM_RECT || site.kind == Kind.MEM_FLOAT) {
            stat.applied.incrementAndGet()
            written.incrementAndGet()
            lastRect = "${t.l},${t.t},${t.r},${t.b}"
        }
    }

    private fun after(site: Site, stat: Stat, param: XC_MethodHook.MethodHookParam) {
        val t = armed.get() ?: return
        armed.remove()
        val rect = Rect(t[0], t[1], t[2], t[3])
        var ok = false

        when (site.kind) {
            Kind.OPTIONS -> {
                // 返回值本身就是 ActivityOptions（addLaunch... 返回它）。
                // ⚠ fix46 这里写的是 `param.args.firstOrNull()` —— 对 addLaunch 而言
                //   第一个参数是 RootWindowContainer，不是 options，于是 applied 恒 0
                //   （真机实测 calls=4 applied=0）。必须按类型找。
                ok = applyToOptions(param.result, rect) ||
                    applyToOptions(param.args.firstOrNull { isOptions(it) }, rect)
            }
            Kind.PARAMS -> {
                ok = applyToLaunchParams(param.args, rect)
                // 顺带把 options 也对齐，免得下游还有路径读 options
                applyToOptions(param.args.firstOrNull { isOptions(it) }, rect)
            }
            else -> Unit
        }
        if (ok) {
            stat.applied.incrementAndGet()
            written.incrementAndGet()
            lastRect = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
            log("applied via ${site.label} pkg=$lastPkg rect=$rect")
        } else {
            skip(stat, site, "write-failed")
        }
    }

    // ---------------------------------------------------------------- 写值

    private fun isOptions(o: Any?) = o != null && o.javaClass.name == "android.app.ActivityOptions"

    private fun applyToOptions(o: Any?, rect: Rect): Boolean {
        if (!isOptions(o)) return false
        val a = runCatching {
            XposedHelpers.callMethod(o, "setLaunchBounds", rect)
            // 只在我们已经决定要开小窗时才强制 mode；mode 0 = 未指定，不动
            val mode = XposedHelpers.callMethod(o, "getLaunchWindowingMode") as? Int
            if (mode == FREEFORM_MODE) {
                XposedHelpers.callMethod(o, "setLaunchWindowingMode", FREEFORM_MODE)
            }
        }.isSuccess
        return a
    }

    private fun clearBounds(args: Array<Any?>) {
        for (a in args) {
            if (isOptions(a)) {
                runCatching { XposedHelpers.callMethod(a, "setLaunchBounds", *arrayOf<Any?>(null)) }
                break
            }
        }
    }

    /** 找参数里最后一个 `LaunchParamsController$LaunchParams`（= outParams），写 mBounds。 */
    private fun applyToLaunchParams(args: Array<Any?>, rect: Rect): Boolean {
        val target = args.lastOrNull { it != null && it.javaClass.name.endsWith("LaunchParams") }
            ?: return false
        val bounds = runCatching { XposedHelpers.getObjectField(target, "mBounds") }.getOrNull()
        if (bounds !is Rect) return false
        bounds.set(rect)
        runCatching { XposedHelpers.setIntField(target, "mWindowingMode", FREEFORM_MODE) }
        return true
    }

    // ---------------------------------------------------------------- 判定

    /** 从 Task / ActivityRecord 参数里取包名（哪个有取哪个）。 */
    private fun pkgOf(args: Array<Any?>): String? {
        for (a in args) {
            val n = a?.javaClass?.name ?: continue
            if (n == "com.android.server.wm.Task") pkgOfTask(a)?.let { return it }
            if (n.endsWith("ActivityRecord")) {
                val p = runCatching { XposedHelpers.getObjectField(a, "packageName") as? String }
                    .getOrNull()
                if (!p.isNullOrEmpty()) return p
                val c = runCatching { XposedHelpers.getObjectField(a, "mActivityComponent") }.getOrNull()
                val p2 = runCatching { XposedHelpers.callMethod(c, "getPackageName") as? String }
                    .getOrNull()
                if (!p2.isNullOrEmpty()) return p2
            }
        }
        return null
    }

    /**
     * 从 Task 取包名。
     *
     * ⚠ `Task.getPackageName()` 在这台机器上有时会返回 null —— fix46 真机实测
     * `hasFreeformDesktopMemory calls=1 skip=no-pkg` 就是这个（该方法只有一个 Task 参数，
     * 取不到包名就整个放弃了）。这里补三道兜底：realActivity / origActivity / intent。
     */
    private fun pkgOfTask(t: Any): String? {
        for (f in listOf("realActivity", "origActivity")) {
            val c = runCatching { XposedHelpers.getObjectField(t, f) }.getOrNull()
            val p = runCatching { XposedHelpers.callMethod(c, "getPackageName") as? String }
                .getOrNull()
            if (!p.isNullOrEmpty()) return p
        }
        val i = runCatching { XposedHelpers.getObjectField(t, "intent") }.getOrNull()
        val pi = runCatching { XposedHelpers.callMethod(i, "getPackage") as? String }.getOrNull()
        if (!pi.isNullOrEmpty()) return pi
        return runCatching { XposedHelpers.callMethod(t, "getPackageName") as? String }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * ★ fix63：这次启动是不是"**复用屏幕上那个已经在跑的 freeform 小窗**"
     * （挂起后点开 / 从最近任务恢复 / 再次 start 同一个包）。
     *
     * 判据：参数里能拿到的 `Task`（或 `ActivityRecord.getTask()`）**已经是 freeform 模式**。
     *
     * ## 为什么必须放过它
     *
     * 那种情况下窗口的几何是**用户自己**弄出来的（位置 + 澎湃小窗那个缩放把手），
     * 而记忆里的 `l,t,r,b` 是**上一次落盘**的旧值 —— 拿它去顶，用户看到的就是
     * "调整完小窗大小，点一下窗外再点窗内，尺寸突然变回去"。
     *
     * 真机实测（fix63）：把 `mark.via` 的记忆写成 `300,900,700,1300`，
     * `am start-activity --windowingMode 5` 出来的窗口**精确**就是 `[300,900][700,1300]`
     * —— 说明恢复链路的几何确实由这里定音。
     *
     * ## 为什么不会误伤"出生那一下"
     *
     * 我们自己开小窗时，目标包的 task 要么不存在、要么还在 `fullscreen` 模式，
     * 只有"屏幕上已经有小窗"才会命中这条判据。而且 App 侧启动脚本随后还有一次
     * **显式** `am task resize`，几何该摆的还是它摆。
     */
    private fun isResumingExisting(args: Array<Any?>): Boolean {
        for (a in args) {
            val n = a?.javaClass?.name ?: continue
            if (n == "com.android.server.wm.Task") {
                if (isFreeformTask(a)) return true
            } else if (n.endsWith("ActivityRecord")) {
                val t = runCatching { XposedHelpers.callMethod(a, "getTask") }.getOrNull() ?: continue
                if (isFreeformTask(t)) return true
            }
        }
        return false
    }

    /** 这个 `Task` 是不是 freeform。拿不到（反射失败 / task 为 null）一律按"不是"。 */
    private fun isFreeformTask(t: Any?): Boolean {
        if (t == null) return false
        val m = runCatching { XposedHelpers.callMethod(t, "getWindowingMode") as? Int }.getOrNull()
        return m == FREEFORM_MODE
    }

    /** 只有在"本来就要进小窗"的启动上动手，绝不能把主屏上的普通启动塞进小窗。 */
    private fun isFreeform(args: Array<Any?>): Boolean {
        for (a in args) {
            if (isOptions(a)) {
                val mode = runCatching { XposedHelpers.callMethod(a, "getLaunchWindowingMode") as? Int }
                    .getOrNull()
                if (mode == FREEFORM_MODE) return true
            }
            val n = a?.javaClass?.name ?: continue
            if (n == "com.android.server.wm.Task") {
                val f = runCatching { XposedHelpers.callMethod(a, "inFreeformWindowingMode") as? Boolean }
                    .getOrNull()
                if (f == true) return true
            }
            if (n.endsWith("LaunchParams")) {
                val m = runCatching { XposedHelpers.getIntField(a, "mWindowingMode") }.getOrNull()
                if (m == FREEFORM_MODE) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- 目标文件

    /**
     * 按包名取"这一次要摆到哪儿"，两个来源依次尝试：
     *  ① [HookContract.BIRTH_TARGET_PATH] —— 新建小窗那一下（60s TTL）；
     *  ② [HookContract.WINDOW_MEMORY_PATH] —— 持久记忆（**无 TTL**），专供
     *     「小窗挂起后过很久再点开」这条路：那时 ① 早就过期了，而恢复走的正是
     *     recents 启动链路（fix46 实测 updateBoundsAndScaleByOptions 只在这里被调用）。
     *
     * ★ fix64：记忆**按横竖屏分开取**，并且**必须能放进当前屏幕**才用。
     *
     * 真机实测（用户报"横屏时呼出的小窗不见了"）：屏幕转成横屏后是 `2400x1080`，
     * 而记忆里那条是竖屏的 `[300,800][1100,2000]`（底边 2000 > 屏高 1080）——
     * 原样下发的结果是窗口**大部分落在屏幕下方**，用户看到的就是"小窗不见了"。
     * 所以这里：
     *  - 只认**当前方向**那一条（`pkg@L=…` / `pkg=…`，见 [HookContract.memoryKey]），
     *    **绝不跨方向回退** —— 另一个方向的矩形对当前屏幕没有意义；
     *  - 读到的矩形先用当前屏幕（+ 图层缩放）判"放得下吗"，放不下就**当没有记忆**
     *    （退回系统默认几何；App 侧随后还有一次 `am task resize` 会按设置值摆）；
     *  - 位置再夹一道，保证左上角一定在屏幕内。
     */
    private fun targetFor(pkg: String, args: Array<Any?>): Target? {
        val now = System.currentTimeMillis()

        val raw = if (now - cachedAt > READ_INTERVAL_MS) {
            cachedAt = now
            runCatching { File(HookContract.BIRTH_TARGET_PATH).readText() }
                .getOrDefault("").also { cachedRaw = it }
        } else cachedRaw
        if (raw.isNotBlank()) {
            val map = HashMap<String, String>()
            for (tok in raw.split(Regex("\\s+"))) {
                val i = tok.indexOf('=')
                if (i > 0) map[tok.substring(0, i)] = tok.substring(i + 1)
            }
            if (map["pkg"] == pkg) parse(map, now, TargetSource.BIRTH_TARGET)?.let { return it }
        }

        val mraw = if (now - memCachedAt > MEM_READ_INTERVAL_MS) {
            memCachedAt = now
            runCatching { File(HookContract.WINDOW_MEMORY_PATH).readText() }
                .getOrDefault("").also { memCachedRaw = it }
        } else memCachedRaw

        val size = displaySize(args)
        val landscape = size != null && size[0] > size[1]

        // ★ fix124：学得的「系统默认几何」（RecordHook 从系统入口出生帧学得，按方向一行）。
        //   fix128 把读取提到记忆分支前 —— 开关关掉时记忆命中要用它的**宽高**（只恢复位置）。
        val drawRaw = runCatching { File(HookContract.DEFAULT_RECT_PATH).readText() }.getOrDefault("")
        val drect = parseMemory(drawRaw, HookContract.defaultKey(landscape))

        // ★ ① 持久记忆：只认**当前方向**那一条，且必须放得进当前屏幕才用。
        //   ⚠ 不在这里改尺寸：App 侧故意下发的"超屏补偿矩形"（图层缩放 0.7 时才出现）
        //   不能被这里缩小，否则窗口会比用户设置的小一圈。
        if (mraw.isNotBlank()) {
            val key = HookContract.memoryKey(pkg, landscape)
            val rect = parseMemory(mraw, key)
            // ★ fix76：fit 上限从「屏幕 / 0.70」改为「荒谬值兜底（屏幕 × 4）」。fix42 实测
            //   freeform 窗口正坐标超界被系统原样接受（right 到 3000 都生效），只有负坐标非法；
            //   fix69 用「屏幕/0.70 = 1542」当上限会把用户拖到屏幕边缘外的合法位置（right 1690）
            //   误判成"放不下"丢弃，导致"拖动记了但重开读不回"。
            if (rect != null && size != null &&
                rect[0] >= 0 && rect[1] >= 0 &&
                rect[2] <= size[0] * 4 && rect[3] <= size[1] * 4 &&
                rect[2] - rect[0] > 0 && rect[3] - rect[1] > 0
            ) {
            // ★ fix76：收边与记录侧 doWrite 同口径 —— **保持宽高，只平移位置**。
            //   ★ fix124b：补上 doWrite 有而这里漏了的「横屏状态栏地板」（topSafe）——
            //   没有它，横屏记忆 top=108 会被 `coerceIn(0, maxB-h=0)` 强压成 top=0
            //   （maxB=1080/0.70=1542，满高窗 h=1542），每次重开窗顶都顶进状态栏；
            //   且整个包络必须是任务坐标的膨胀空间（÷0.70，锚点左上），与 doWrite 完全一致。
            //   ★ fix128：「记住小窗大小」开关关 = 位置照记忆、宽高换学得的系统默认
            //   （默认没学到就退记忆宽高 —— 开关缺省为开，这里只是兜底）。
                var w = rect[2] - rect[0]
                var h = rect[3] - rect[1]
                if (!HookContract.rememberSizeEnabled() &&
                    drect != null && drect[2] - drect[0] >= 200 && drect[3] - drect[1] >= 200
                ) {
                    w = drect[2] - drect[0]
                    h = drect[3] - drect[1]
                }
                val maxR = (size[0] / 0.70f).toInt()
                val maxB = (size[1] / 0.70f).toInt()
                val topSafe = if (size[0] > size[1]) statusBarHeightSystem() else 0
                val l = rect[0].coerceIn(0, (maxR - w).coerceAtLeast(0))
                val t = rect[1].coerceIn(topSafe, (maxB - h).coerceAtLeast(topSafe))
                memSkip = if (w == rect[2] - rect[0]) "$key:ok" else "$key:ok(default-size)"
                return Target(pkg, l, t, l + w, t + h, now / 1000, TargetSource.MEMORY)
            } else if (rect != null) {
                memSkip = "$key:not-fit(${size?.get(0) ?: 0}x${size?.get(1) ?: 0})"
            }
        }

        // ★ fix79：记忆统一一份 —— 旧「侧边独立记忆」（SB 文件）已废弃，主文件里就是全部记忆
        //   （RecordHook 启动时已把旧 SB 数据按 `@SB@L→@L`、`@SB→(无)` 并入主文件）。

        // ★ fix124：③ 学得的「系统默认几何」（RecordHook 从系统入口出生帧学得，按方向一行）。
        //   只对**自启动**生效：系统入口自己带着澎湃默认几何，不能被我们顶掉（否则系统入口
        //   的级联错位也被钉死成一成不变）。自启动 + 无记忆 = 悬浮球开首窗的那一下 ——
        //   没有这层兜底时它落到 AOSP 级联默认，宽高与系统入口对不上（用户报的 bug）。
        //   坐标是 task bounds 空间（与 LaunchParams 同域），直接收边进屏即可，不做 0.70 补偿。
        if (drect != null && size != null &&
            drect[2] <= size[0] * 4 && drect[3] <= size[1] * 4 &&
            drect[2] - drect[0] >= 200 && drect[3] - drect[1] >= 200 &&
            HookContract.selfLaunchFresh(pkg)
        ) {
            // ★ fix124b：位置收边与 MEMORY 分支同口径（膨胀包络 + 横屏状态栏地板）。
            //   最初版本用「完全在屏内」收边是错的：默认窗在任务坐标里本来就允许悬挂出屏
            //   （竖屏默认 left=162 会被 1080-1080=0 压成 x=0 —— 用户报"坐标不对"）。
            val w = drect[2] - drect[0]
            val h = drect[3] - drect[1]
            val maxR = (size[0] / 0.70f).toInt()
            val maxB = (size[1] / 0.70f).toInt()
            val topSafe = if (size[0] > size[1]) statusBarHeightSystem() else 0
            val l = drect[0].coerceIn(0, (maxR - w).coerceAtLeast(0))
            val t = drect[1].coerceIn(topSafe, (maxB - h).coerceAtLeast(topSafe))
            memSkip = "default:ok"
            return Target(pkg, l, t, l + w, t + h, now / 1000, TargetSource.DEFAULT)
        } else if (drect != null) {
            memSkip = if (!HookContract.selfLaunchFresh(pkg)) "default:not-self-launch" else "default:not-fit"
        }
        return null
    }

    /** ★ fix124b：系统状态栏高度（px），与 RecordHook 同款（横屏 topSafe 地板用）。 */
    private fun statusBarHeightSystem(): Int = runCatching {
        val r = android.content.res.Resources.getSystem()
        val id = r.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) r.getDimensionPixelSize(id).coerceIn(0, 200) else 0
    }.getOrDefault(0)

    /** 最近一次记忆通道的判定结果（进自检文件的 `skip=`，便于真机排查）。 */
    @Volatile private var memSkip = "-"

    // ---------------------------------------------------------------- 屏幕尺寸

    /** 屏幕尺寸缓存（跟随旋转，但不必每次反射）。 */
    @Volatile private var dispAt = 0L
    @Volatile private var dispSize: IntArray? = null
    private const val DISP_TTL_MS = 2_000L

    /**
     * ★ fix64：**当前屏幕尺寸**（跟随旋转）—— 判定"横屏/竖屏"和"记忆放不放得下"要用。
     *
     * 取值顺序：
     *  ① 参数里的 `Task` → `getDisplayContent()` → `DisplayInfo.appWidth/appHeight`
     *     （这是**当前旋转后**的可用区尺寸，真机实测竖屏 `1080x2400`／横屏 `2400x1080`）；
     *  ② 兜底用 system context 的 `displayMetrics`。
     *
     * 全程 runCatching + 2 秒缓存：启动链路很热，读不到就返回 null（调用方按"不知道方向"
     * 处理，即不收边、不跨方向取记忆）。
     */
    private fun displaySize(args: Array<Any?>): IntArray? {
        val now = System.currentTimeMillis()
        if (now - dispAt < DISP_TTL_MS) return dispSize
        val v = runCatching { readDisplaySize(args) }.getOrNull()
        dispAt = now
        dispSize = v
        return v
    }

    private fun readDisplaySize(args: Array<Any?>): IntArray? {
        for (a in args) {
            if (a == null || a.javaClass.name != "com.android.server.wm.Task") continue
            val dc = runCatching { XposedHelpers.callMethod(a, "getDisplayContent") }
                .getOrNull() ?: continue
            val info = runCatching { XposedHelpers.callMethod(dc, "getDisplayInfo") }
                .getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(dc, "mDisplayInfo") }.getOrNull()
                ?: continue
            val w = runCatching { XposedHelpers.getIntField(info, "appWidth") }
                .getOrDefault(0)
            val h = runCatching { XposedHelpers.getIntField(info, "appHeight") }
                .getOrDefault(0)
            if (w > 0 && h > 0) return intArrayOf(w, h)
        }
        // 兜底：system_server 里的 system context（旋转后会跟着更新配置）
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

    /** 新建小窗的目标（`pkg=… l=… t=… r=… b=… ts=…`，带 60s 新鲜度闸门）。 */
    private fun parse(map: Map<String, String>, now: Long, source: TargetSource): Target? {
        val pkg = map["pkg"] ?: return null
        val ts = map["ts"]?.toLongOrNull() ?: return null
        if (now - ts * 1000L > TARGET_TTL_MS) return null
        val l = map["l"]?.toIntOrNull() ?: return null
        val t = map["t"]?.toIntOrNull() ?: return null
        val r = map["r"]?.toIntOrNull() ?: return null
        val b = map["b"]?.toIntOrNull() ?: return null
        if (r <= l || b <= t) return null
        return Target(pkg, l, t, r, b, ts, source)
    }

    /**
     * 持久记忆（每行 `<key>=l,t,r,b`，无 TTL）。
     *
     * ★ fix64：[key] 由 [HookContract.memoryKey] 给出（竖屏就是包名、横屏是 `包名@L`），
     *   **只认这一条** —— 找不到就返回 null（= 当没有记忆，退回系统默认几何），
     *   绝不跨方向回退：拿竖屏的矩形去横屏用，结果就是"小窗落在屏幕外、看不见"。
     */
    private fun parseMemory(raw: String, key: String): IntArray? {
        if (raw.isBlank()) return null
        for (line in raw.split("\n")) {
            val i = line.indexOf('=')
            if (i <= 0 || line.substring(0, i).trim() != key) continue
            val v = line.substring(i + 1).split(",").mapNotNull { it.trim().toIntOrNull() }
            if (v.size != 4 || v[2] <= v[0] || v[3] <= v[1]) return null
            return intArrayOf(v[0], v[1], v[2], v[3])
        }
        return null
    }

    // ---------------------------------------------------------------- 落盘

    /** 启动链路很热，state 文件由后台线程每秒刷一次，不在调用栈里做 IO。 */
    private fun startFlusher() {
        Thread({
            while (true) {
                runCatching { Thread.sleep(1_000L) }.getOrNull() ?: return@Thread
                runCatching {
                    val sb = StringBuilder()
                    val n = stats.values.count { it.inst > 0 }
                    sb.append("installed=$n/${SITES.size} seen=${seen.get()} applied=${written.get()}\n")
                    sb.append("last=$lastPkg rect=$lastRect skip=$lastSkip\n")
                    // ★ fix64：记忆通道单独的判定结果（`no-target` 时看不出是"没那么包"还是"放不下"）。
                    sb.append("memskip=$memSkip\n")
                    for (s in SITES) {
                        sb.append("${s.label} ${stats[s.label] ?: Stat()}\n")
                    }
                    val f = File(HookContract.BIRTH_STATE_PATH)
                    val tmp = File(HookContract.BIRTH_STATE_PATH + ".tmp")
                    tmp.writeText(sb.toString())
                    // ★ fix54：同上 —— 放开读权限，让 App 侧能直读而不必 fork su。
                    runCatching { tmp.setReadable(true, false) }
                    tmp.renameTo(f)
                }
            }
        }, "memoryfreeform-birth-flush").apply { isDaemon = true }.start()
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG $msg")
    }
}
