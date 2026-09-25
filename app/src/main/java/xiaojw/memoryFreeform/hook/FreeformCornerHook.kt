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
 * ## 修法
 *
 * 让 `FOLME_RADIUS` 的**起点等于终点**，圆角就全程是最终值（屏幕上恒定 47px）：
 *
 *  - 三/四参 `addProperty(prop, from, to[, ease])`：`prop == FOLME_RADIUS && from <= 0 && to > 0`
 *    ⇒ 把 `from` 改成 `to`（补间退化成常量）；
 *  - 两参 `addProperty(prop, to)`（单值重载）：先把 `mFolmeControl` 的当前值
 *    `setFolmeRadius(to)` 顶上去，动画就变成 47 → 47。
 *
 * `startMoveToFrontAnimation` 走的正是第二种，所以两条都必须挂。
 *
 * ## 铁律（与 [MiuiFreeFormBirthHook] 同款）
 *
 *  - **只在 `com.android.systemui` 里装**，system_server 里没有这些类；
 *  - 按「类名 + 方法名 + 参数形态」反射匹配，**不硬编码签名** —— ROM 改签名最坏是少挂
 *    一个点，不会炸；
 *  - 全程 `runCatching`：SystemUI 是桌面/Shell 的宿主进程，这里出任何异常都必须静默；
 *  - 救命开关 [HookContract.CORNER_OFF_PATH] 存在时一个点都不挂（不影响 system_server 那两个钩子）。
 *
 * ⚠ 需要用户在 LSPosed 里给本模块**勾选 `com.android.systemui` 作用域并重启**
 *   （模块 manifest 的 `xposedscope` 仍只声明 `android`，SystemUI 要手动加 ——
 *   不敢改声明是为了避免解析失败把现有作用域弄丢）。没勾就不会生效，也不会有副作用。
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
    @Volatile private var lastMsg = "-"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            if (File(HookContract.CORNER_OFF_PATH).exists()) {
                lastMsg = "disabled by ${HookContract.CORNER_OFF_PATH}"
                log(lastMsg)
                return
            }
            val stateCls = runCatching { XposedHelpers.findClass(STATE_CLS, lpparam.classLoader) }
                .getOrNull()
            if (stateCls == null) {
                lastMsg = "state class not found"
                log(lastMsg)
                return
            }
            val ctrlCls = runCatching { XposedHelpers.findClass(CTRL_CLS, lpparam.classLoader) }
                .getOrNull()
            val radiusProp = ctrlCls?.let {
                runCatching { XposedHelpers.getStaticObjectField(it, "FOLME_RADIUS") }.getOrNull()
            }
            if (radiusProp == null) {
                lastMsg = "FOLME_RADIUS not found"
                log(lastMsg)
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
                }.onFailure { lastMsg = "hook failed ${m.name}/${ps.size}: ${it.message}" }
            }
            installed = n
            lastMsg = if (n > 0) "installed $n site(s)" else "no addProperty matched"
            log(lastMsg)
            if (n > 0) startFlusher()
        }.onFailure {
            lastMsg = "install failed: ${it.message}"
            log(lastMsg)
        }
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
            while (true) {
                runCatching { Thread.sleep(5_000L) }.getOrNull() ?: return@Thread
                runCatching {
                    val f = File(HookContract.CORNER_STATE_PATH)
                    val tmp = File(HookContract.CORNER_STATE_PATH + ".tmp")
                    tmp.writeText(
                        "installed=$installed snap=$snap fromTo=$fromTo msg=$lastMsg\n"
                    )
                    runCatching { tmp.setReadable(true, false) }
                    tmp.renameTo(f)
                }
            }
        }, "memoryfreeform-corner-flush").apply { isDaemon = true }.start()
    }

    private fun log(msg: String) {
        runCatching { XposedBridge.log("$TAG $msg") }
    }
}
