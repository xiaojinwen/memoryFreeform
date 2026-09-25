package xiaojw.memoryFreeform.core

import xiaojw.memoryFreeform.hook.HookContract
import xiaojw.memoryFreeform.root.RootManager

/**
 * App 侧桥接：读取注入在 system_server 里的 LSPosed hook 的**自检结果**。
 *
 * 通信介质是 /data/system 下的文件（hook 在 system_server 里写，App 有 root 可以读）。
 * LSP 模块没装 / 没激活时，所有读取都返回"未生效"，功能自动降级到"不补偿、不介入"。
 *
 * ## fix52：这里只剩「读」，不再有「写」
 *
 * fix38 之前这个类还要把会话（小窗宽高 + 需要小窗化的包名）**发布**给 hook ——
 * 那套 `publish / addPackage / clear` 连同它依赖的 `WindowTransformHook` 一起删掉了。
 * 现在 hook 不参与窗口几何（几何由 `am start-activity --windowingMode 5` +
 * `am task resize` 交给澎湃自己管），App 只剩两件**读取**的事：
 *
 *  ① [miuiLayerScale]：澎湃给 freeform 强加的图层缩放比例（★ fix72 起直接取设置值
 *    [StateManager.layerScale]，默认 0.70，缩放中和 hook 已删除）。它决定
 *    [WindowSizing.deliverRect] 做多大的 ÷比例 放大补偿；
 *  ② [isActive] / [birthState]：诊断。装完 APK **必须重启手机**
 *    （hook 在 system_server 里，覆盖安装不会替换已加载的代码），这几个文件就是
 *    "到底装上了没有"的唯一证据。
 */
object HookBridge {

    /** 直读快路径是否可行（null = 还没试过，false = 试过不行，以后不再试）。 */
    @Volatile private var directReadOk: Boolean? = null

    /**
     * ★ fix72：澎湃 Freeform 图层缩放**当前实际值**。
     *
     * 缩放中和 hook 已删除（改系统缩放比例会波及很多场景），现在这个值**直接取自设置**
     * （[StateManager.layerScale]，默认 0.70，可在设置页调）。[WindowSizing.deliverRect]
     * 用它把「想让人看到的尺寸」÷ 比例再下发，视觉尺寸正好落在意图位置。
     *
     * 不再读 [HookContract.SCALE_STATE_PATH] 判定「是否被中和」—— 没有中和这回事了，
     * 补偿永远生效（除非用户把比例调成 1.0 = 某 ROM 无图层缩放）。
     *
     * 保守下限：比例读出来异常（≤0.01）时退回 [WindowSizing.MIUI_LAYER_SCALE]，
     * 避免除 0 / 反向放大把窗口撑出屏幕。
     */
    fun miuiLayerScale(): Float {
        val v = StateManager.current.layerScale
        return if (v > 0.01f) v else WindowSizing.MIUI_LAYER_SCALE
    }

    /**
     * ★ fix45 诊断：出生几何 hook 的自检结果（多行，压成一行返回）。
     * 排查"出生几何没生效"看它，判据见 [HookContract.BIRTH_STATE_PATH]。
     */
    fun birthState(): String =
        readRaw(HookContract.BIRTH_STATE_PATH)?.replace("\n", " ")?.trim().orEmpty()

    /**
     * LSP 模块是否已激活：system_server 会在 hook 安装成功后写标记文件，
     * 之后每 60 秒续一次（见 [xiaojw.memoryFreeform.hook.HookEntry.startActiveHeartbeat]）。
     *
     * 判据是"3 分钟内续过期"，理由见 [HookContract.ACTIVE_PATH]。
     */
    fun isActive(): Boolean {
        val raw = readRaw(HookContract.ACTIVE_PATH) ?: return false
        val ts = raw.trim().toLongOrNull() ?: return false
        return System.currentTimeMillis() - ts < 3 * 60 * 1000L
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 读一个 hook 自检文件；读不到返回 null。
     *
     * ★ fix54：先试**直读**（普通 `File.readText`，微秒级、不 fork），读不到才回退 root。
     *
     * ⚠ 直读"读不到"**不能**当成"hook 没装上" —— 那可能只是 SELinux / `/data/system`
     *   不可遍历，而两者对 [miuiLayerScale] 的结论是相反的（判成"没中和"会去做 1/0.7
     *   补偿，把窗口放大 1.43 倍）。所以直读拿不到时一律交给 root 判定，只有 **root 也
     *   说没有** 才把直读这条快路关掉，免得每轮都白试一遍。
     *   hook 侧写文件后调了 `setReadable(true, false)`，能直读时这条路就自动生效。
     */
    private fun readRaw(path: String): String? {
        if (directReadOk != false) {
            runCatching { java.io.File(path).readText() }
                .getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { directReadOk = true; return it }
        }
        val out = runCatching {
            RootManager.get().execute("cat $path 2>/dev/null").output.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (out == null) directReadOk = false
        return out
    }

    /** 从 `key=value` 空格分隔的一行里取一个字段。 */
    private fun stateField(raw: String, key: String): String? =
        Regex("$key=([^\\s]+)").find(raw)?.groupValues?.getOrNull(1)
}
