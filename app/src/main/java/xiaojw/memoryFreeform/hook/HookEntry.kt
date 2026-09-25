package xiaojw.memoryFreeform.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "SingleHand/"

/**
 * LSPosed 入口 —— fix52 之后只剩**澎湃 Freeform 一条路线**，所以只留两个钩子。
 *
 * ## 路线沿革（为什么最后只剩这两个）
 *
 * - **refactor18~28 Display 重定向**（已删）：hook AMS 改 `launchDisplayId`，把应用搬到
 *   另一块屏上去 → task 跨屏销毁重建、闪屏、滚动位置丢失、二级页崩溃、越权权限墙。
 *   28 轮重构全在"hook 没拦住 / 拦错地方"上打转。
 * - **refactor32~fix35 Window-Transform**（已删）：改用 `setLaunchBounds` +
 *   `WindowContainer.setBounds` + `relayoutWindow` 三个钩子把窗口几何强制成小窗矩形。
 *   实测 `winHits` 562 次全是 `relayout-Y`、零 `Y` —— AMS 后续链路会把
 *   `mResolvedOptions` 覆盖回 fullscreen；而改 `WindowState.mFrame` 只是补丁，
 *   系统任何一次 `performLayout` 都会覆盖回去。整条路线已删。
 * - **fix36 起（现在）**：**不开 hook 去改几何**，改用 AOSP 标准 CLI 让系统自己开
 *   Freeform —— `am start-activity --windowingMode 5` + `am task resize`。
 *   澎湃自动接管 Caption Bar / 触摸分发 / 焦点 / 二级页归属，几何零钳制。
 *
 * 于是 hook 侧只剩两件系统从外面**做不到**的事：
 *
 * - [MiuiFreeFormBirthHook]：在 **MIUI 私有的** `updateBoundsAndScaleByOptions`
 *   上把 launchBounds 换成我们的矩形，让窗口**一出生**就是目标几何。
 *   否则只能等窗口画出来再 `am task resize`，用户会看到一段"从小到大转一圈"的动画。
 *
 * ## fix72：缩放中和 hook 已移除
 *
 * 原来的 [MiuiFreeformScaleHook]（中和澎湃强加的 0.70 图层缩放）**已删除** ——
 * 改小米缩放比例后，即使收窄到"只中和我们开的窗"，仍会在很多场景留下副作用。
 * 现在改回「App 侧下发时尺寸 ÷ 图层缩放比例」的纯补偿路线（[WindowSizing.deliverRect]），
 * 比例可配置（[StateManager.layerScale]，默认 0.70），不再动系统合成层的缩放。
 *
 * ## 已删除的钩子（连同它们的文件）
 *
 * - `WindowTransformHook`：launchBounds + setBounds + relayoutWindow（见上，路线已废）；
 * - `StartingWindowSuppressor`：启动预览抑制 —— 那是"小窗里没动画但主屏不闪"的补丁，
 *   现在小窗由系统创建，预览窗口本来就挂在正确的 task 上，不需要抑制；
 * - `ImeLayerFix` / `ImeEventHook`：键盘层级修复 + 键盘事件广播 ——
 *   前者是"自造 overlay 窗口盖住输入法"才有的问题，后者服务的是 App 侧自搬窗口避让；
 * - `ModuleConfig`：conf 协议解析（width/height/mode/packages）。已无 conf 文件，
 *   它的 `markActive()` 职责内联进本类（见 [markActive]）。
 *
 * 作用域：android（系统框架）+ ★fix140 起加 com.android.systemui（WMShell，只挂圆角修正）。
 * 模块与单手模式 App 是同一个 APK。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            // 系统框架（system_server）—— 出生几何 + 位置记忆
            "android" -> installSystemServer(lpparam)
            // ★ fix140：SystemUI（WMShell）—— 小窗入场动画的圆角补间，见 [FreeformCornerHook]
            "com.android.systemui" -> installSystemUi(lpparam)
            else -> return
        }
    }

    private fun installSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // 总闸：救命开关存在时一个钩子都不装，system_server 完全走原生路径
            if (java.io.File(HookContract.KILL_SWITCH_PATH).exists()) {
                XposedBridge.log(TAG + "hook disabled by kill switch " + HookContract.KILL_SWITCH_PATH)
                return
            }
            markActive()
            // ★ 出生即目标几何：在 MIUI 私有的 updateBoundsAndScaleByOptions 上把
            //   launchBounds 换成我们的矩形，省掉"先系统默认、再 resize"那段动画
            MiuiFreeFormBirthHook.install(lpparam)
            // ★ fix66：把"小窗位置记录"从 App 进程搬进 system_server —— 不管谁开的窗、
            //   不管 App 在不在，拖完/缩放完/关窗时直接落盘，不必每秒轮询。
            MiuiFreeformRecordHook.install(lpparam)
            // ★ fix144：小窗入场动画的圆角补间（0 → 67.14）在 SystemUI 里做，本机拿不到
            //   SystemUI 作用域，改在 system_server 侧抢到 leash 后用高频写入把圆角钉死。
            FreeformCornerKeeperHook.install(lpparam)
            // ★ fix87：`FreeformScaleProbeHook`（fix73 的临时 scale 探针，7 个挂点、只观察不改值）
            //   已删除 —— 缩放中和路线在 fix72 就废了，比例改由设置项 `layerScale` 直接配置，
            //   探针留着只是给 MIUI 的 scale 读写点白挂 7 个 inline hook。
            startActiveHeartbeat()
            XposedBridge.log(TAG + "hook installed in " + lpparam.processName)
        }.onFailure {
            XposedBridge.log(TAG + "install failed")
            XposedBridge.log(it)
        }
    }

    /**
     * ★ fix140：SystemUI（WMShell）里的圆角修正。
     *
     * 只装 [FreeformCornerHook] 一个点，且它自己全流程 `runCatching` —— SystemUI 是桌面与
     * Shell 的宿主进程，这里出问题比 system_server 更显眼，所以：
     *  - 总闸 [HookContract.KILL_SWITCH_PATH] 存在时不装；
     *  - 找不到 folme 那两个类 / `FOLME_RADIUS` 字段时直接放弃（ROM 改了就不生效，不崩）。
     *
     * ⚠ fix140 时只声明了 `android`，结果 SystemUI 得用户手动勾作用域 —— 实测用户没勾，
     *   钩子一行都没跑（自检文件都没生成）。fix143 起 `xposedscope` 改成
     *   `@array/xposed_scope`（android + com.android.systemui），LSPosed 会预勾，
     *   更新模块后只要重启即可。
     */
    private fun installSystemUi(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            if (java.io.File(HookContract.KILL_SWITCH_PATH).exists()) {
                XposedBridge.log(TAG + "corner hook disabled by kill switch")
                return
            }
            FreeformCornerHook.install(lpparam)
            XposedBridge.log(TAG + "corner hook installed in " + lpparam.processName)
        }.onFailure {
            XposedBridge.log(TAG + "corner install failed")
            XposedBridge.log(it)
        }
    }

    /**
     * 写一次"hook 已激活"时间戳（[HookContract.ACTIVE_PATH]），App 侧据此判断增强是否生效。
     *
     * 路径不可写时静默失败：这是个纯诊断标记，绝不能因为它让 system_server 出问题。
     */
    private fun markActive() {
        runCatching {
            val f = java.io.File(HookContract.ACTIVE_PATH)
            f.writeText("${System.currentTimeMillis()}\n")
            // ★ fix54：放开读权限，让 App 侧直读（省一次 su fork）。
            //   写失败 / chmod 失败都无所谓，App 侧会自动回退到 root 读取。
            f.setReadable(true, false)
        }
    }

    /**
     * 定期刷新"hook 已激活"标记。
     *
     * 为什么必须周期刷新：`markActive` 原来只在 `handleLoadPackage` 里写一次，
     * 而它只在 system_server **启动**时跑 —— 开机 5 分钟之后标记就过期，
     * App 侧 `HookBridge.isActive()` 于是开始报 `hookActive=false`
     * （真机日志里 true/false 反复横跳：00:04 true → 00:07 false、02:34 true → 02:38 false，
     *  完全跟着"距开机多久"走，跟模块有没有真的启用无关）。
     *
     * 这里起一个守护线程每 60 秒写一个时间戳。只写一个 13 字节的文件，
     * 且整段包在 runCatching 里：出任何问题都不影响 system_server。
     */
    private fun startActiveHeartbeat() {
        Thread({
            while (true) {
                runCatching { Thread.sleep(60_000L) }.getOrNull() ?: return@Thread
                runCatching { markActive() }
                    .onFailure { XposedBridge.log(TAG + "heartbeat failed: " + it.message) }
            }
        }, "memoryfreeform-hook-hb").apply { isDaemon = true }.start()
    }
}
