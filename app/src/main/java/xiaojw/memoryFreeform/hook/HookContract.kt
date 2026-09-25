package xiaojw.memoryFreeform.hook

import java.io.File

/**
 * App 侧与注入到 system_server 的 hook 代码之间的约定。
 * 两边跑在不同进程，靠 /data/system 下的文件通信（App 有 root 负责写，hook 负责读/写）。
 *
 * ## fix52：只剩「澎湃 Freeform 一条路线」，通信面被砍到最小
 *
 * 两边只剩三件事要沟通：
 *  ① **几何**（[BIRTH_TARGET_PATH] / [WINDOW_MEMORY_PATH]）—— 让新开的小窗**一出生**
 *     就是目标矩形，而不是系统默认几何（本机默认 `[286,714][794,1794]`），
 *     省掉「先默认、再 resize」那段可见的缩放动画；
 *  ② **身份**（[SESSION_PATH]）—— 记录钩子靠它区分「我们开的窗」和「系统侧边栏开的窗」，
 *     位置记忆才不至于写错文件；
 *  ③ **自检**（[ACTIVE_PATH] / [BIRTH_STATE_PATH] / [RECORD_STATE_PATH]）——
 *     hook 跑在 system_server 里，XposedBridge 日志在 LSPosed 管理器里，用户导出的
 *     memoryfreeform.log 看不到，所以每个 hook 都要落一个 App 读得到的自检文件。
 *
 * ★ fix72：缩放中和 hook 已删除，[SCALE_STATE_PATH] 随之废弃（不再有进程写它）。
 */
object HookContract {

    /**
     * **hook 存活心跳**：system_server 侧的 hook 安装成功后写入当前毫秒时间戳，
     * 之后每 60 秒续一次（见 [HookEntry.startActiveHeartbeat]）。
     *
     * App 侧 [xiaojw.memoryFreeform.core.HookBridge.isActive] 的判据是「3 分钟内续过期」：
     *  - 为什么必须周期续：只在 `handleLoadPackage` 写一次的话，那只在 system_server
     *    **启动**时跑 —— 开机 5 分钟后标记就过期，App 侧会开始报 `hookActive=false`
     *    （真机日志里 true/false 反复横跳，完全跟着"距开机多久"走，与模块是否启用无关）；
     *  - 为什么是 3 分钟：既容忍 system_server 的调度延迟，又能在用户禁用模块并重启后
     *    （没人再续期）自动判定为未激活，不至于永远顶着一个过期的 true。
     */
    const val ACTIVE_PATH = "/data/system/memoryfreeform_hook.active"

    /**
     * **总闸（救命开关）**：这个文件存在时，hook 一个都不装 —— system_server 完全按原生
     * 行为跑，手机必定能正常开机。
     *
     * 用途：万一某个 ROM 上 hook 导致反复崩溃 / 进不了系统，在 LSPosed 或 Recovery 里
     * 建一个空文件就能"没开机也关得掉"，不必当场卸载模块或清数据。
     * root 建法：`touch /data/system/memoryfreeform_hook.off`（root 文件管理器亦可）。
     * 放在 /data/system 下：与会话文件同一处，system_server 读得到、root 写得了。
     */
    const val KILL_SWITCH_PATH = "/data/system/memoryfreeform_hook.off"

    /**
     * ★ fix140：**圆角修复的单独开关**（SystemUI 侧读）。
     *
     * 总闸 [KILL_SWITCH_PATH] 会连 system_server 那两个钩子一起关掉（那些是核心功能），
     * 而"四角不闪直角"只是观感修正，万一在某些 ROM 上 [FreeformCornerHook] 挂点命中了
     * 不该命中的动画，得能单独摘掉它。
     * root 建法：`touch /data/system/memoryfreeform_corner.off` + 重启 SystemUI（或重启手机）。
     */
    const val CORNER_OFF_PATH = "/data/system/memoryfreeform_corner.off"

    /** ★ fix140：圆角钩子的自检文件（SystemUI 进程每 5 秒写一次）。 */
    const val CORNER_STATE_PATH = "/data/system/memoryfreeform_corner.state"

    /**
     * **出生几何目标**（App 写、hook 读）。一行空格分隔：
     * `pkg=<包名> l=<左> t=<上> r=<右> b=<下> ts=<秒级时间戳> done=<0|1>`
     *
     * `am` CLI **没有 bounds 参数**（usage 里只有 --windowingMode / --activityType / --display），
     * 所以 `am start-activity --windowingMode 5` 生出来的小窗几何是系统默认值。真正在
     * **出生那一刻**吃 bounds 的是 MIUI 私有的
     * `MiuiFreeFormManagerService.updateBoundsAndScaleByOptions(ActivityOptions, Task)`：
     * 它从 ActivityOptions 里读 launchBounds，写回 options 并设 windowingMode=5。
     * 本文件就是喂给那个挂点的通道 —— 由 App 在下发 `am start-activity` **之前**用 root 写好。
     *
     * ⚠ `ts` 是新鲜度闸门：只有 60 秒内写下的目标才作数。否则用户后来手动把这个应用
     *   开成系统小窗时，会被一份陈旧的矩形强行摆位。
     * ⚠ `done=1` = 「这次启动已经落地」。缩放中和钩子只在 `done` 不是 1 的短窗口里按
     *   "我们正在启动"处理，免得这十几秒内用户从侧边栏开系统小窗也被误中和。
     */
    const val BIRTH_TARGET_PATH = "/data/system/memoryfreeform_birth_target"

    /**
     * **小窗位置记忆**（App 写、hook 读 / ★fix64 起 hook 也写）—— **不带新鲜度闸门**。
     *
     * ★ fix64 行格式（**位置按横竖屏分开记**）：
     * ```
     * <pkg>=left,top,right,bottom        竖屏
     * <pkg>@L=left,top,right,bottom      横屏
     * ```
     * ★ **fix79 起统一一份**：不再区分「我们的窗」与「侧边/系统窗」（旧 `@SB` 双文件方案
     *   废弃）—— 所有 freeform 小窗共用这一份记忆，键只按横竖屏区分。
     * 坐标是**下发**坐标（非视觉尺寸）。为什么要分方向：竖屏记下的
     * `[300,800][1100,2000]` 在横屏（本机屏高只有 1080）就是"底边在屏幕外" ⇒
     * 用户看到的是**小窗不见了**（真机实测）。所以 hook 侧只认**当前方向**那一条，
     * 绝不跨方向回退；找不到就按"没有记忆"处理（退回系统默认 / 设置值）。
     * 键名规则由 [memoryKey] 统一给出，两边不许各写一套。
     *
     * 与 [BIRTH_TARGET_PATH] 的区别就在那个 TTL：
     *  - `birth_target` 只管"新建小窗那一下"，写完 60 秒失效，防止陈旧矩形乱摆位；
     *  - 这里要管的是**挂起后恢复** —— 用户把小窗最小化成气泡、过很久再点开，
     *    那个时刻早就超过 60 秒了。而恢复走的正是
     *    `ActivityTaskSupervisorImpl.scheduleStartActivityFromRecents` 那条路
     *    （fix46 实测 `updateBoundsAndScaleByOptions` 只在 recents 启动时被调用）。
     *
     * ★ fix52：**这个文件与 App 侧的 `WindowMemory`（SharedPreferences）是同一套语义**
     *   —— 都存下发给系统的矩形，中间不做任何倍率换算。fix48~fix51 期间 App 侧存的是
     *   "视觉意图"（下发 x 图层倍率），而倍率要靠猜，猜错一次就缩一圈，形成一个
     *   "每开合一次小窗就小一点"的乘法螺旋；用户一清空记忆回到设置尺寸，看到的就是
     *   "小窗一下子大了很多、和原来的大小对不上"。同域之后两边不会再互相打架。
     */
    const val WINDOW_MEMORY_PATH = "/data/system/memoryfreeform_window_memory"

    /**
     * ★ fix124：**「系统默认几何」**（hook 写 hook 读，App 不参与）。
     *
     * 行格式与 [WINDOW_MEMORY_PATH] 同构，按方向一行：
     * ```
     * default=l,t,r,b        竖屏
     * default@L=l,t,r,b      横屏
     * ```
     * 来源：**学习**。RecordHook 在一次 freeform 窗口**出生**（首次见到的 setBounds/onResize 帧）
     * 上，若满足——① 该包此方向**没有**记忆 ② 不是我们 `am start` 拉起的（见
     * [SELF_LAUNCH_PATH]）③ 几何不是全屏帧 / 失焦角族——就把这帧几何学进这里。
     * 经系统入口（侧边栏 / 消息横幅）开的窗，出生几何就是澎湃自己算的默认矩形，学一次定音。
     *
     * 消费方：出生钩子 [MiuiFreeFormBirthHook]（**仅在悬浮球自启动且无记忆时**）用它兜底
     * —— 没有它，无记忆的包走 `am start-activity --windowingMode 5` 落到的是 AOSP
     * 级联默认（本机 `[286,714][794,1794]` 一族），宽高与系统入口默认对不上，
     * 而且这个错值还会被当成记忆自我循环。键名由 [defaultKey] 统一给出。
     */
    const val DEFAULT_RECT_PATH = "/data/system/memoryfreeform_default_rect"

    /**
     * ★ fix124：**「本次是悬浮球自启动」瞬时标记**（App 写、hook 读）。
     * 一行 `pkg=<包名> ts=<秒级时间戳>`，App 在下发 `am start-activity` **之前**经 root 写入。
     *
     * 两个用途（判据统一收在 [selfLaunchFresh]，两边语义必须一致）：
     *  - RecordHook 学习默认几何时**跳过**自启动的出生帧——我们的 am start 在无记忆时
     *    出生几何是 AOSP 级联默认，学进去就是污染；
     *  - 出生钩子只在「自启动 + 无记忆」时才应用 [DEFAULT_RECT_PATH]——系统入口自带的
     *    默认几何不许被我们顶掉（否则系统入口的级联错位也被钉死）。
     * 新鲜窗口 15 秒：覆盖 am start → task 出生（亚秒级）的间隔，过期自愈。
     */
    const val SELF_LAUNCH_PATH = "/data/system/memoryfreeform_self_launch"

    /** ★ fix124：[DEFAULT_RECT_PATH] 的键名（竖 `default` / 横 `default@L`）。 */
    fun defaultKey(landscape: Boolean): String = if (landscape) "default@L" else "default"

    /**
     * ★ fix128：**设置开关下发**（App 写、hook 读）。一行 `rememberSize=<0|1>`。
     *
     * 为什么必须走文件：开关存在 App 的 SharedPreferences 里，hook 在 system_server
     * 里读不到 —— 旧行为是「出生钩子永远应用记忆」，开关只管住 App 侧 adopt 复核
     * 那一条路，主开窗链路上它什么也不管（用户观察到的"位置本来就会记"）。
     * 语义（用户定音）：**位置始终按记忆恢复；本开关只管大小** —— 关 = 大小用
     * 学得的系统默认（[DEFAULT_RECT_PATH]），开 = 位置和大小都按记忆。
     * ★ fix142：文件缺失**按关（false）**处理 —— 与 App 侧开关默认值一致
     * （fix128 定的是"缺失按开"，随开关默认改关一起翻转）。重装/重启后 App 起来
     * 会补写真实值（[xiaojw.memoryFreeform.core.SingleHandManager.pushRememberSizeFlag]）。
     */
    const val FLAGS_PATH = "/data/system/memoryfreeform_flags"

    // fix128：rememberSizeEnabled 的文件缓存（birth 链路热）。
    private var flagAt = 0L
    private var flagRaw = ""

    /** ★ fix128 / ★ fix142：[FLAGS_PATH] 里 `rememberSize` 的当前值（300ms 缓存；缺失 = false）。 */
    fun rememberSizeEnabled(): Boolean = synchronized(this) {
        val now = System.currentTimeMillis()
        if (now - flagAt > 300) {
            flagAt = now
            flagRaw = runCatching { File(FLAGS_PATH).readText() }.getOrDefault("")
        }
        var on = false
        for (tok in flagRaw.split(Regex("\\s+"))) {
            val i = tok.indexOf('=')
            if (i > 0 && tok.substring(0, i) == "rememberSize") {
                on = tok.substring(i + 1).trim() != "0"
            }
        }
        on
    }

    // fix124：selfLaunchFresh 的文件缓存（onResize 链路热，不能每次开文件）。
    private var selfAt = 0L
    private var selfRaw = ""

    /**
     * ★ fix124：[SELF_LAUNCH_PATH] 的新鲜判定（App 与两次 am start 之间的间隔远大于
     * 15s，不需要按包逐个失效；文件里的 `ts` 就是闸门）。300ms 文件缓存。
     */
    fun selfLaunchFresh(pkg: String): Boolean = synchronized(this) {
        val now = System.currentTimeMillis()
        if (now - selfAt > 300) {
            selfAt = now
            selfRaw = runCatching { File(SELF_LAUNCH_PATH).readText() }.getOrDefault("")
        }
        var p = ""
        var ts = 0L
        for (tok in selfRaw.split(Regex("\\s+"))) {
            val i = tok.indexOf('=')
            if (i <= 0) continue
            when (tok.substring(0, i)) {
                "pkg" -> p = tok.substring(i + 1).trim()
                "ts" -> ts = tok.substring(i + 1).trim().toLongOrNull() ?: 0L
            }
        }
        p == pkg && ts > 0 && now / 1000L - ts in 0L..15L
    }

    /**
     * ★ fix64：小窗位置记忆的**键名**（App 侧 `WindowMemory` 的 SP key 后缀、
     * 以及 [WINDOW_MEMORY_PATH] 里 `=` 左边那一段）。
     *
     * 两边**必须**用同一个函数，否则会出现"App 记为横屏、hook 按竖屏找"这种
     * 静默错位（表现就是"记忆有时生效有时不生效"）。
     */
    fun memoryKey(pkg: String, landscape: Boolean): String = if (landscape) "$pkg@L" else pkg

    /**
     * ★ fix67b（已废弃）→ ★ **fix79：只作为迁移源保留**。
     * 旧方案曾把「系统侧边栏窗」单独存这里（`<pkg>@SB=` / `<pkg>@SB@L=`），与主文件互不污染；
     * 实际用下来双文件只会造成"写了 A 文件读 B 文件"的路由竞态（真机实证：会话文件被 App
     * 关窗流程先删后，remove flush 全部落进 SB，主文件永远读不回）。fix79 起读写全部走
     * [WINDOW_MEMORY_PATH]；[MiuiFreeformRecordHook] 启动时把这份旧数据按
     * `@SB@L→@L`、`@SB→(无)` 并入主文件（主文件已有的键不覆盖），然后删除本文件。
     */
    const val WINDOW_MEMORY_SB_PATH = "/data/system/memoryfreeform_window_memory_sb"

    /**
     * **当前小窗会话的身份**（App 写、hook 读）。一行空格分隔：
     * `pkg=<包名> task=<taskId> ts=<毫秒时间戳>`；小窗关闭时由 App 删除。
     *
     * ★ fix79：记忆不再按会话分流（统一主文件），本文件只剩「当前开的是哪个包、
     * 哪个 task」这一层语义，供仍需要会话判定的链路（如缩放中和、探针）使用。
     *
     * 与 [WINDOW_MEMORY_PATH] 的分工：memory 是**历史**（开过哪些包、摆在哪），
     * 本文件是**当前**（这一次正在开的是哪个包、哪个 task）。
     */
    const val SESSION_PATH = "/data/system/memoryfreeform_session"

    /**
     * ★ fix72 已废弃：**澎湃 Freeform 图层缩放的中和结果**（原来是 hook 写、App 读）。
     *
     * 缩放中和 hook（[MiuiFreeformScaleHook]）已删除，不再有进程写这个文件。
     * 图层缩放改由 App 侧配置（[xiaojw.memoryFreeform.core.StateManager.layerScale]）直接提供，
     * 开窗时按「÷比例」补偿。此常量保留仅为历史引用可读，实际已无读写方。
     */
    // ★ fix111：SCALE_STATE_PATH 死常量已删（缩放中和 hook fix72 就删了，无读写方）。
    //   注意：0.70 图层缩放本身是澎湃的渲染行为（仍存在），App 侧按它 ÷比例补偿是
    //   [WindowSizing.MIUI_LAYER_SCALE] 常量，不是 hook，去不掉。

    // ★ fix87：`SCALE_PROBE_PATH`（fix73 的 scale 探针文件）随探针一起删除，已无读写方。

    /**
     * **出生几何 hook 的自检结果**（hook 写、App 读）。
     * 多行；App 侧压成一行进日志。形如：
     * `installed=n/8 seen=N applied=M` / `last=<pkg> rect=<l,t,r,b>` / `skip=<原因>`
     *
     * 「出生即目标几何」有没有生效只能看这里 —— 它发生在 system_server 里，App 侧无从感知：
     *  - `installed=0` → 方法签名对不上（ROM 版本变了，去 dex 里重新确认签名）；
     *  - `seen` 不涨 → 这台机器的启动链路根本不经过那个 MIUI 私有入口；
     *  - `seen` 涨、`applied` 不涨 → 看 `skip=`：
     *    `no-target` 目标文件没写/已过期、`pkg-mismatch(x)` 包名不是目标包、
     *    `not-freeform` 这次启动不是小窗、`write-failed` 字段写不进去。
     */
    const val BIRTH_STATE_PATH = "/data/system/memoryfreeform_birth.state"

    /**
     * ★ fix66：**小窗位置记录 hook 的自检结果**（hook 写、App 读）。
     * `installed=N seen=N applied=M last=<pkg> rect=<l,t,r,b> skip=<原因>`
     *
     * 这条 hook 把"记录小窗位置"从 App 进程搬进 system_server：不管小窗是澎湃开的还是
     * 我们开的、也不管我们的 App 进程是不是活着，只要在 `Task.resize`（拖动/缩放落定）或
     * `Task.remove*`（关窗）上钩到 freeform 窗口，就直接把它的最终几何写进
     * [WINDOW_MEMORY_PATH]。这样"手势条关窗 / App 被杀"都不再漏记，且是事件驱动、
     * 不用 App 侧每秒 `am stack list` 轮询。
     *
     *  - `installed=0` → `resize` / `remove*` 这些名字在这个 ROM 上没找到（去 dex 复核）；
     *  - `seen` 不涨 → 这台机器的 freeform 几何变更没经过 `Task.resize`（改挂 `setBounds` 之类）；
     *  - `applied` 不涨 → 看 `skip=`（`not-freeform` 这次不是小窗、`no-pkg` 取不到包名、
     *    `off-screen` 几何没落在屏内被拒）。
     */
    const val RECORD_STATE_PATH = "/data/system/memoryfreeform_record.state"

    /** 小窗 App 的包名（广播定向用） */
    const val APP_PKG = "xiaojw.memoryFreeform"
}
