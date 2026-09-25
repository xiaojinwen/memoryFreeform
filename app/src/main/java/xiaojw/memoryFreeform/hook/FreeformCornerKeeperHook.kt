package xiaojw.memoryFreeform.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * ★ fix144：**在 system_server 侧把小窗入场动画的圆角钉死**，干掉"四角先闪一下直角"。
 *
 * ## 为什么不再走 SystemUI 那条路
 *
 * fix140 在 SystemUI 里拦 folme 动画（`FreeformCornerHook`），思路正确但**装不进去**：
 * 本机是 KernelSU + Zygisk LSPosed，`xposedscope` 声明不生效、作用域列表里也没有
 * SystemUI。fix143 试过直接往 `/data/adb/lspd/config/modules_config.db` 的 `scope` 表
 * 插 `com.android.systemui`，结果**整机 LSPosed 失效**（模块日志只剩一行 part start），
 * 回退后才恢复。**结论：这台机器拿不到 SystemUI 作用域，别再试。**
 *
 * ## 本方案的立足点
 *
 * 圆角是 SurfaceFlinger 图层属性，谁拿到 leash 谁就能写：
 *  - SystemUI 的 folme 动画**每 16ms**写一次补间值（0 → 67.14）；
 *  - leash 是 **system_server** 建的，我们在这里就能抓到同一个 `SurfaceControl`；
 *  - 于是用更高频率（5ms）持续写终值，把补间帧盖掉。
 *
 * 动画的缩放/位移/透明度照旧，只有圆角恒定 —— 也就是"从头到尾都是圆角"。
 *
 * ## 抓 leash 的两条路（ROM 不同走不同的）
 *
 *  - `SurfaceAnimator.createAnimationLeash(...)`（老写法）：返回值就是 leash，
 *    参数 0 是 `Animatable`（= WindowContainer），能直接判断是不是 freeform；
 *  - `SurfaceControl$Builder.makeAnimationLeash()` + `build()`（Android 15+/16 的新写法）：
 *    `makeAnimationLeash()` 返回 builder 自己，用 ThreadLocal 记下这个 builder 实例，
 *    紧跟着的 `build()` 若是同一个实例，它的结果就是 leash。
 *
 * 第二条路拿不到 WindowContainer，所以用 [markBirth] 开的**时间窗**（开小窗后 2 秒内）
 * 来限定，避免给全屏窗口乱加圆角。
 *
 * ## 数值
 *
 * 终值 **67.1429 = 47 / 0.70**：47px = `18dp`（MIUI 写死），0.70 = freeform 图层缩放。
 * 优先反射 `MiuiFreeFormManagerService.getMiuiFreeformCornerRadius / getFreeformScale`
 * 现算（`Resources.getSystem().displayMetrics` 在 system_server 里给的是 3.5 不是 2.625，
 * 拿它算会得到 90 —— 错的，别用），算不出来就用实测稳定值 67.1429。
 *
 * ## 副作用与控制
 *
 *  - 维持 900ms（入场动画约 500ms），之后交还给系统的 finish transaction；
 *  - 关闭动画也会建 leash，那 900ms 内圆角同样被钉住（只是关闭时圆角不收缩，几乎看不出），
 *    动画结束后系统写回正确值；
 *  - 开关 [HookContract.CORNER_OFF_PATH]；自检 [HookContract.CORNER_STATE_PATH]
 *    （`keeperSites` / `seen` / `kept` / `radius`）。
 */
object FreeformCornerKeeperHook {

    private const val TAG = "SingleHand/CornerKeeper"

    private const val KEEP_MS = 900L
    private const val INTERVAL_MS = 5L
    /** 开小窗后允许认领 leash 的时间窗。 */
    private const val BIRTH_WINDOW_MS = 2_000L
    /** 真机实测的稳定圆角（= 18dp×2.625 / 0.70），反射拿不到时用它。 */
    private const val FALLBACK_RADIUS = 67.1429f

    @Volatile private var sites = 0
    @Volatile private var seen = 0
    @Volatile private var kept = 0
    @Volatile private var radius = 0f
    @Volatile private var lastMsg = "-"
    @Volatile private var lastBirthMs = 0L

    private val running = AtomicInteger(0)
    /** `makeAnimationLeash()` 返回的 Builder（链式调用紧接着就是 `build()`）。 */
    private val pendingBuilder = ThreadLocal<Any?>()
    /** 上面那个 Builder 属于哪个 WindowContainer —— 用它判断是不是小窗。 */
    private val pendingWc = ThreadLocal<Any?>()

    /** 开小窗的那一刻由 [MiuiFreeFormBirthHook] 调用，开一个认领 leash 的时间窗。 */
    fun markBirth() {
        lastBirthMs = System.currentTimeMillis()
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            if (File(HookContract.CORNER_OFF_PATH).exists()) {
                lastMsg = "disabled by ${HookContract.CORNER_OFF_PATH}"
                log(lastMsg)
                return
            }
            val msgs = ArrayList<String>(3)
            runCatching { installOld(lpparam, msgs) }.onFailure { msgs += "old: ${it.message}" }
            runCatching { installWc(lpparam, msgs) }.onFailure { msgs += "wc: ${it.message}" }
            runCatching { installBuilder(lpparam, msgs) }.onFailure { msgs += "bld: ${it.message}" }
            lastMsg = msgs.joinToString(" | ").ifEmpty { "nothing installed" }
            log(lastMsg)
            startFlusher()
        }.onFailure {
            lastMsg = "install failed: ${it.message}"
            log(lastMsg)
        }
    }

    /** 老写法：`SurfaceAnimator.createAnimationLeash` 直接返回 leash。 */
    private fun installOld(lpparam: XC_LoadPackage.LoadPackageParam, msgs: ArrayList<String>) {
        val animCls = runCatching {
            XposedHelpers.findClass("com.android.server.wm.SurfaceAnimator", lpparam.classLoader)
        }.getOrNull() ?: run {
            msgs += "no SurfaceAnimator"
            return
        }
        var n = 0
        for (m in animCls.declaredMethods) {
            if (m.name != "createAnimationLeash" || m.parameterTypes.isEmpty()) continue
            runCatching { m.isAccessible = true }
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val animatable = param.args.firstOrNull()
                            if (animatable != null && isFreeform(animatable)) {
                                radius = radiusOf(animatable)
                                claim(param.result)
                            }
                        }
                    }
                })
                n++
            }
        }
        sites += n
        msgs += "old $n"
    }

    /**
     * Android 15+/16 的写法：`WindowContainer.makeAnimationLeash()` 返回
     * `SurfaceControl.Builder`，链式 `.setName()...build()` 之后才是 leash。
     * 这里只负责"记下这个 builder 是谁的"，真正的 leash 在 `build()` 里拿。
     */
    private fun installWc(lpparam: XC_LoadPackage.LoadPackageParam, msgs: ArrayList<String>) {
        val wcCls = runCatching {
            XposedHelpers.findClass("com.android.server.wm.WindowContainer", lpparam.classLoader)
        }.getOrNull() ?: run {
            msgs += "no WindowContainer"
            return
        }
        var n = 0
        for (m in wcCls.declaredMethods) {
            if (m.name != "makeAnimationLeash" || m.parameterTypes.isNotEmpty()) continue
            runCatching { m.isAccessible = true }
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            pendingBuilder.set(param.result)
                            pendingWc.set(param.thisObject)
                        }
                    }
                })
                n++
            }
        }
        sites += n
        msgs += "wc $n"
    }

    /** `SurfaceControl$Builder.build()`：认领刚才记下的 builder 的产物。 */
    private fun installBuilder(lpparam: XC_LoadPackage.LoadPackageParam, msgs: ArrayList<String>) {
        val bldCls = runCatching {
            XposedHelpers.findClass("android.view.SurfaceControl\$Builder", lpparam.classLoader)
        }.getOrNull() ?: run {
            msgs += "no Builder"
            return
        }
        var nMake = 0
        var nBuild = 0
        for (m in bldCls.declaredMethods) {
            when (m.name) {
                "makeAnimationLeash" -> {
                    runCatching { m.isAccessible = true }
                    runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    pendingBuilder.set(param.thisObject ?: param.result)
                                    pendingWc.set(null)
                                }
                            }
                        })
                        nMake++
                    }
                }
                "build" -> {
                    if (m.parameterTypes.isNotEmpty()) continue
                    runCatching { m.isAccessible = true }
                    runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    if (pendingBuilder.get() !== param.thisObject) return@runCatching
                                    val wc = pendingWc.get()
                                    pendingBuilder.set(null)
                                    pendingWc.set(null)
                                    seen++
                                    if (wc != null) {
                                        if (isFreeform(wc)) {
                                            radius = radiusOf(wc)
                                            claim(param.result)
                                        }
                                    } else if (System.currentTimeMillis() - lastBirthMs < BIRTH_WINDOW_MS) {
                                        // 拿不到 WindowContainer（老 ROM），退回时间窗
                                        claim(param.result)
                                    }
                                }
                            }
                        })
                        nBuild++
                    }
                }
            }
        }
        sites += nMake + nBuild
        msgs += "bld make=$nMake build=$nBuild"
    }

    private fun isFreeform(wc: Any): Boolean {
        runCatching {
            if (XposedHelpers.callMethod(wc, "getWindowingMode") as? Int == 5) return true
        }
        runCatching {
            if (XposedHelpers.callMethod(wc, "inFreeformWindowingMode") as? Boolean == true) return true
        }
        return false
    }

    /** 终值 = getMiuiFreeformCornerRadius / getFreeformScale；拿不到就用实测值。 */
    private fun radiusOf(wc: Any): Float {
        runCatching {
            val wms = XposedHelpers.getObjectField(wc, "mWmService")
            val atm = XposedHelpers.getObjectField(wms, "mAtmService")
            val ffms = XposedHelpers.getObjectField(atm, "mMiuiFreeFormManagerService")
            val rootId = XposedHelpers.callMethod(wc, "getRootTaskId")
            val r = XposedHelpers.callMethod(ffms, "getMiuiFreeformCornerRadius", rootId) as Float
            val s = XposedHelpers.callMethod(ffms, "getFreeformScale", rootId) as Float
            if (r > 0f && s > 0.1f) return r / s
        }
        return FALLBACK_RADIUS
    }

    private fun claim(leash: Any?) {
        if (leash == null) return
        if (running.get() >= 4) return
        val r = radius.takeIf { it > 0f } ?: FALLBACK_RADIUS
        running.incrementAndGet()
        Thread({
            val txn = runCatching { newTransaction() }.getOrNull()
            if (txn == null) {
                running.decrementAndGet()
                return@Thread
            }
            val end = System.currentTimeMillis() + KEEP_MS
            while (System.currentTimeMillis() < end) {
                val ok = runCatching {
                    XposedHelpers.callMethod(txn, "setCornerRadius", leash, r)
                    XposedHelpers.callMethod(txn, "apply")
                }.isSuccess
                if (!ok) break
                kept++
                runCatching { Thread.sleep(INTERVAL_MS) }
            }
            running.decrementAndGet()
        }, "memoryfreeform-corner-keep").apply { isDaemon = true }.start()
    }

    private fun newTransaction(): Any? {
        val cls = Class.forName("android.view.SurfaceControl\$Transaction")
        val ctor = cls.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private fun startFlusher() {
        Thread({
            while (true) {
                runCatching { Thread.sleep(5_000L) }.getOrNull() ?: return@Thread
                runCatching {
                    val f = File(HookContract.CORNER_STATE_PATH)
                    val tmp = File(HookContract.CORNER_STATE_PATH + ".tmp")
                    tmp.writeText(
                        "keeperSites=$sites seen=$seen kept=$kept radius=$radius msg=$lastMsg\n"
                    )
                    runCatching { tmp.setReadable(true, false) }
                    tmp.renameTo(f)
                }
            }
        }, "memoryfreeform-corner-keeper-flush").apply { isDaemon = true }.start()
    }

    private fun log(msg: String) {
        runCatching { XposedBridge.log("$TAG $msg") }
    }
}
