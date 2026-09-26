package xiaojw.memoryFreeform.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * ★ fix140：**干掉"开窗时小窗四角先闪一下直角"** —— 让圆角从第一帧就是最终值。
 *
 * ## 症状与实测（真机 Xiaomi 2304FPN6DC / HyperOS 3.0）
 *
 * 开窗那一刻小窗四个角**先是方的**，约半秒内才"长"出圆角。逐帧采 SurfaceFlinger：
 *
 * ```
 * Layer [16319] Task=12983#16319     bounds={286,714,1923.6,1042} scale 0.7000
 *   roundedCorner{33.4649,33.4649}   t=…423602
 *   roundedCorner{58.9807,58.9807}   t=…423721
 *   roundedCorner{63.0685,63.0685}   t=…423805
 *   roundedCorner{67.1429,67.1429}   t=…424022   ← 稳定值
 * ```
 *
 * 也就是说：圆角半径被**从 ~0 补间到 67.14** 用了约 500ms（ease-out），头几帧半径≈0
 * = 直角矩形，正是用户说的"四角闪现一下矩形，圆角没有了"。
 *
 * ## 圆角到底是什么
 *
 * 澎湃的 freeform 圆角不是 App 画的，也不是 window 的 drawable —— 它是 **SurfaceFlinger
 * 图层属性**（MIUI 扩展的 `roundedCorner` + `drawFreeformEffect`），打在 task/leash 那个
 * 图层上，父图层的圆角会向下传给子图层，所以整窗内容一起被裁成圆角。
 *
 * 稳定值 **67.1429 = 47 / 0.70**：47px 是 `18dp`（`MiuiFreeFormManagerService
 * .getMiuiFreeformCornerRadius` 里写死的 `applyDip2Px(18.0f)`），0.70 是 MIUI 给
 * freeform 强加的图层缩放 —— 半径要除以缩放才是图层空间的值
 * （`getMiuiFreeformCornerRadius(taskId) / scale`，与 `MiuiPipImpl` 同款写法）。
 *
 * ## 谁在把它从 0 补间上来
 *
 * 在 **SystemUI（com.android.systemui，pid 7950）** 里，不在 system_server：
 *
 * ```
 * D MultiTaskingTransitionHandler: canHandleTransition handler:
 *     com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAnimation
 * D MiuiFreeformModeAnimation: startMoveToFrontAnimation: … fromRotateX: -90.0 toRotateX: 0.0
 * ```
 *
 * 冷开走的是 `startMoveToFrontAnimation`（animationType 13，一个 rotateX -90°→0° 的翻入
 * 动画），它给 folme 动画引擎喂的是 `addProperty(FOLME_RADIUS, cornerRadius)`
 * **单值重载** —— 起点取 folmeControl 的当前值，而**新建 task 的 folmeControl 初值是 0**，
 * 于是照样是 0 → 47 的补间。每帧再由 `MultiTaskingFolmeControl` 落到
 * `transaction.setCornerRadius(leash, folmeRadius / folmeScaleX)`。
 *
 * 也就是说：**这是澎湃给"打开小窗"设计的入场动画的一部分，任何方式开小窗都会走**，
 * 不是我们 `am start-activity --windowingMode 5` 引入的。
 *
 * ## 修法（两条腿，互为兜底）
 *
 * 1. **主**：让 `FOLME_RADIUS` 的**起点等于终点**，圆角全程是最终值：
 *     - 三/四参 `addProperty(prop, from, to[, ease])`：`prop == FOLME_RADIUS && from <= 0 && to > 0`
 *       ⇒ 把 `from` 改成 `to`（补间退化成常量）；
 *     - 两参 `addProperty(prop, to)`（单值重载）：先把 `mFolmeControl` 的当前值
 *       `setFolmeRadius(to)` 顶上去，动画就变成 47 → 47。
 *
 *    `startMoveToFrontAnimation` 走的是第二种，所以两条都得挂。
 *
 * 2. **兜底**（fix143 新增）：直接挂 `SurfaceControl.Transaction.setCornerRadius`。
 *    上面那条依赖 folme 类的签名，ROM 一改就挂空；这条只认 framework 方法，稳得多。
 *    判据用「原始值递增 + 调用栈是 MIUI 小窗动画」：
 *     - 只抬**递增**的那一段（0→5→20→33→50→67 是入场），关闭/缩迷你窗是递减
 *       （47→12），一律不碰，所以不会破坏退出动画；
 *     - 抬的目标 = 18dp / 0.70（density 现算），并且自适应：凡是"已经够大"的帧就
 *       把它记为 stable，下次以 stable 为准，ROM 换数值也不会抬错。
 *
 * ## 作用域（fix143 关键修正）
 *
 * fix140 只在代码里装了钩子，**没在 manifest 声明 SystemUI**，LSPosed 里得用户手动勾
 * 作用域，实测用户没勾 ⇒ 钩子一行都没跑、自检文件都没生成。现在 `xposedscope` 改成
 * `@array/xposed_scope`（android + com.android.systemui），LSPosed 会预勾，
 * 更新模块后重启即可。
 *
 * ## 铁律（与 [MiuiFreeFormBirthHook] 同款）
 *
 *  - **只在 `com.android.systemui` 里装**，system_server 里没有这些类；
 *  - 按「类名 + 方法名 + 参数形态」反射匹配，**不硬编码签名** —— ROM 改签名最坏是少挂
 *    一个点，不会炸；
 *  - 全程 `runCatching`：SystemUI 是桌面/Shell 的宿主进程，这里出任何异常都必须静默；
 *  - 救命开关 [HookContract.CORNER_OFF_PATH] 存在时一个点都不挂（不影响 system_server 那两个钩子）。
 */
object FreeformCornerHook {

    private const val TAG = "SingleHand/Corner"

    private const val STATE_CLS =
        "com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeState"
    private const val CTRL_CLS =
        "com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeControl"
    /** `MultiTaskingFolmeState` 里持有 folmeControl 的字段名（反编译实测）。 */
    private const val CTRL_FIELD = "mFolmeControl"

    @Volatile private var installed = 0
    @Volatile private var snap = 0
    @Volatile private var fromTo = 0
    /** 兜底（setCornerRadius）成功抬升的帧数。 */
    @Volatile private var clamped = 0
    @Volatile private var clampSites = 0
    /** 头几帧半径为 0（纯直角）被抬上去的次数 —— 治"闪直角"的正主。 */
    @Volatile private var zeroFixed = 0
    /** 学到过几次稳定值（应 ≥1，否则说明钳制从没见过小窗动画）。 */
    @Volatile private var learned = 0
    /** 自适应学到的稳定圆角（图层空间值，实测 67.14）。 */
    @Volatile private var stable = 0f
    /** 学习候选值：必须连续 N 帧落在候选附近才转正为 stable，避免一过冲峰被学成稳定值。 */
    @Volatile private var candidate = 0f
    @Volatile private var candidateCount = 0
    private const val CANDIDATE_EPSILON = 0.1f
    private const val CANDIDATE_NEED = 3
    @Volatile private var lastMsg = "-"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            if (File(HookContract.CORNER_OFF_PATH).exists()) {
                lastMsg = "disabled by ${HookContract.CORNER_OFF_PATH}"
                log(lastMsg)
                return
            }
            val msgs = ArrayList<String>(3)

            // ① 主方案：folme 动画起点抬到终点
            runCatching { installFolme(lpparam, msgs) }
                .onFailure { msgs += "folme failed: ${it.message}" }

            // ② 兜底：直接钳 Transaction.setCornerRadius（不依赖 folme 签名）
            runCatching { installRadiusClamp(lpparam, msgs) }
                .onFailure { msgs += "clamp failed: ${it.message}" }

            lastMsg = msgs.joinToString(" | ").ifEmpty { "nothing installed" }
            log(lastMsg)
            // 只要有一个点挂上就开自检落盘；两个都没挂也落盘，方便排查作用域问题
            startFlusher()
        }.onFailure {
            lastMsg = "install failed: ${it.message}"
            log(lastMsg)
        }
    }

    private fun installFolme(lpparam: XC_LoadPackage.LoadPackageParam, msgs: ArrayList<String>) {
        val stateCls = runCatching { XposedHelpers.findClass(STATE_CLS, lpparam.classLoader) }
            .getOrNull()
        if (stateCls == null) {
            msgs += "state class not found"
            return
        }
        val ctrlCls = runCatching { XposedHelpers.findClass(CTRL_CLS, lpparam.classLoader) }
            .getOrNull()
        val radiusProp = ctrlCls?.let {
            runCatching { XposedHelpers.getStaticObjectField(it, "FOLME_RADIUS") }.getOrNull()
        }
        if (radiusProp == null) {
            msgs += "FOLME_RADIUS not found"
            return
        }

        var n = 0
        for (m in stateCls.declaredMethods) {
            if (m.name != "addProperty") continue
            val ps = m.parameterTypes
            // 只认「第一个参数是 ValueProperty（对象、非 String），第二个是 float」的形态
            if (ps.size < 2) continue
            if (ps[0].isPrimitive || ps[0] == String::class.java) continue
            if (ps[1] != java.lang.Float.TYPE) continue
            // from/to 形态 = 第三个参数也是 float；单值形态 = 后面跟 ease/config
            val hasTo = ps.size >= 3 && ps[2] == java.lang.Float.TYPE
            if (!hasTo && ps.size > 4) continue
            runCatching { m.isAccessible = true }
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { fix(param, radiusProp, hasTo) }
                    }
                })
                n++
            }.onFailure { msgs += "hook failed ${m.name}/${ps.size}" }
        }
        installed = n
        msgs += if (n > 0) "folme $n site(s)" else "no addProperty matched"
    }

    /**
     * 兜底：挂 `android.view.SurfaceControl$Transaction.setCornerRadius`。
     *
     * 只对「MIUI 小窗入场动画里的递增帧」生效，抬到最终圆角；递减帧（关闭、缩迷你窗）
     * 一律放行，避免把退出动画搞坏。
     */
    private fun installRadiusClamp(lpparam: XC_LoadPackage.LoadPackageParam, msgs: ArrayList<String>) {
        val txnCls = runCatching {
            XposedHelpers.findClass("android.view.SurfaceControl\$Transaction", lpparam.classLoader)
        }.getOrNull()
        if (txnCls == null) {
            msgs += "Transaction not found"
            return
        }
        var n = 0
        for (m in txnCls.declaredMethods) {
            if (m.name != "setCornerRadius" && m.name != "setCornerRadii") continue
            val ps = m.parameterTypes
            if (ps.size < 2) continue
            if (ps[0].name != "android.view.SurfaceControl") continue
            if (ps[1] != java.lang.Float.TYPE) continue
            runCatching { m.isAccessible = true }
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { clamp(param) }
                    }
                })
                n++
            }.onFailure { msgs += "clamp hook failed ${m.name}/${ps.size}" }
        }
        clampSites = n
        msgs += if (n > 0) "clamp $n site(s)" else "no setCornerRadius matched"
    }

    /** 每个 SurfaceControl 上一次见到的「原始」圆角值（不是改写后的值）。 */
    private val lastRawLock = Any()
    private val lastRaw = java.util.WeakHashMap<Any, Float>()

    /** 圆角合理区间（图层空间）。超出这个范围的值不是小窗圆角，一律不认。 */
    private const val MIN_R = 30f
    private const val MAX_R = 120f

    private fun clamp(param: XC_MethodHook.MethodHookParam) {
        val args = param.args
        if (args.size < 2) return
        val sc = args[0] ?: return
        // `setCornerRadii` 可能带 4 个半径参数（四角各一），`setCornerRadius` 只有 1 个。
        // 全部收进来统一钳，否则只钳第一个会让其余角短暂露方（实测 splash 屏第二个半径补到 67）。
        val radii = (1 until args.size).mapNotNull { args[it] as? Float }
        if (radii.isEmpty()) return
        val raw = radii[0]   // 用第一个作代表判据（prev / 递增 / 学习），与旧逻辑一致
        val prev: Float?
        synchronized(lastRawLock) {
            prev = lastRaw.put(sc, raw)
        }
        val target = stable.takeIf { it > 0f } ?: defaultTarget()
        // 把所有半径参数（不止第一个）都抬到终值
        fun rewrite() {
            for (k in 1 until args.size) if (args[k] is Float) args[k] = target
        }
        when {
            // ① 半径是 0（直角矩形）—— 只要还在小窗动画栈里就全部抬到终值。
            //    入场头几帧 folme 起点是 0；动画末尾 SystemUI 也可能 reset 写 0；
            //    这两类都必须钳，否则用户会在动画末尾再闪一下直角。
            //    （关闭/缩迷你窗的递减段写 0 也在这里被钳，但窗口正在消失，圆角保持终值比露方角好）
            raw <= 0f -> {
                if (!isFreeformAnimStack()) return
                rewrite()
                zeroFixed++
            }
            // ② 看起来已经到位：记下来当基准 —— ★ 但不能超过设计值，也不能把一过冲峰当 stable。
            //    folme 是弹簧动画，会**过冲**（实测冲到 70.88 再回落到 67.14），
            //    早先 `stable = max(stable, raw)` 把过冲值当成稳定值 ⇒ 圆角被永久钉大一圈。
            //    fix147/148 用上限 `defaultTarget()` 拦住了 70.88，但 67.49 这种紧贴上线的过冲仍会被
            //    学成 stable（log 里 `stable=67.49067`），导致 target 偏高、系统真值 67.14 帧被放行。
            //    现在要求候选值连续 3 帧一致（误差 <0.1）才转正，一过冲峰只存在 1~2 帧，自然被淘汰。
            raw >= target * 0.99f -> {
                val cap = defaultTarget()
                val maxR = radii.maxOrNull() ?: raw
                if (maxR in MIN_R..MAX_R && maxR <= cap) {
                    if (stable <= 0f) {
                        if (kotlin.math.abs(maxR - candidate) < CANDIDATE_EPSILON) {
                            candidateCount++
                        } else {
                            candidate = maxR
                            candidateCount = 1
                        }
                        if (candidateCount >= CANDIDATE_NEED) {
                            stable = candidate
                            candidateCount = 0
                            learned++
                        }
                    }
                }
            }
            // ③ 递增段（0→…→67 的入场）：抬到终值
            else -> {
                if (prev != null && raw <= prev) return      // ④ 递减段 = 关闭/缩迷你窗，放行
                if (!isFreeformAnimStack()) return
                rewrite()
                clamped++
            }
        }
    }

    /** 终值 = 18dp / 0.70（18dp 是 MIUI 写死的，0.70 是 freeform 固定图层缩放）。 */
    private fun defaultTarget(): Float {
        val density = runCatching { android.content.res.Resources.getSystem().displayMetrics.density }
            .getOrDefault(2.625f)
        return (18f * density / 0.70f).coerceIn(MIN_R, MAX_R)
    }

    /**
     * 调用栈里有没有 MIUI 小窗动画 —— 有才敢钳，避免误伤别处（比如气泡、PIP、桌面）
     * 的圆角动画。
     */
    private fun isFreeformAnimStack(): Boolean {
        val st = runCatching { Thread.currentThread().stackTrace }.getOrNull() ?: return false
        // 只扫最近 12 帧，够盖住 folme/动画回调，开销也可控
        val limit = kotlin.math.min(st.size, 12)
        for (i in 0 until limit) {
            val cn = st[i].className
            if (cn.contains("miuifreeform", true) ||
                cn.contains("MiuiFreeForm", true) ||
                cn.contains("multitasking", true) ||
                cn.contains("MultiTasking", true)
            ) return true
        }
        return false
    }

    /**
     * 把 `FOLME_RADIUS` 的补间起点抬到终点。
     *
     * ⚠ 只认这一个属性、只认"从 0（或负）往正数补间"这一个方向 —— 其它动画
     * （freeform→mini 的 18dp→12dp、关闭时 → 设备圆角）起点都不是 0，一律不动。
     */
    private fun fix(param: XC_MethodHook.MethodHookParam, radiusProp: Any, hasTo: Boolean) {
        if (param.args.isEmpty() || param.args[0] !== radiusProp) return
        if (hasTo) {
            val from = param.args[1] as? Float ?: return
            val to = param.args[2] as? Float ?: return
            if (from <= 0f && to > 0f) {
                param.args[1] = to
                fromTo++
            }
            return
        }
        val to = param.args[1] as? Float ?: return
        if (to <= 0f) return
        // 单值重载：起点是 folmeControl 的当前值（新 task 是 0），先把它顶到终点
        val ctrl = runCatching { XposedHelpers.getObjectField(param.thisObject, CTRL_FIELD) }
            .getOrNull() ?: return
        runCatching { XposedHelpers.callMethod(ctrl, "setFolmeRadius", to) }
        snap++
    }

    /** 自检落盘（后台线程，SystemUI 里绝不在回调栈上做 IO）。 */
    private fun startFlusher() {
        Thread({
            var round = 0
            while (true) {
                runCatching { Thread.sleep(4_000L) }.getOrNull() ?: return@Thread
                round++
                val line = "installed=$installed clampSites=$clampSites snap=$snap fromTo=$fromTo " +
                    "clamped=$clamped zero=$zeroFixed learned=$learned stable=$stable " +
                    "candidate=$candidate/${candidateCount} msg=$lastMsg"
                // 自检文件（写不进去也不影响功能，错误只记一次）
                runCatching {
                    val tmp = File(HookContract.CORNER_STATE_PATH + ".tmp")
                    tmp.writeText("$line\n")
                    runCatching { tmp.setReadable(true, false) }
                    tmp.renameTo(File(HookContract.CORNER_STATE_PATH))
                }.onFailure { if (round == 1) log("state write failed: ${it.message}") }
                // 每 5 轮（20s）往 LSPosed 日志刷一次计数，文件读不到时还有这条渠道
                if (round % 5 == 1) log(line)
            }
        }, "memoryfreeform-corner-flush").apply { isDaemon = true }.start()
    }

    private fun log(msg: String) {
        runCatching { XposedBridge.log("$TAG $msg") }
    }
}
