package xiaojw.memoryFreeform.core

import android.content.Context
import android.content.Intent
import android.util.Log
import xiaojw.memoryFreeform.hook.HookContract
import xiaojw.memoryFreeform.root.RootManager
import xiaojw.memoryFreeform.service.CornerWindowService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SingleHandManager：协调器（角落小窗方案）。
 *
 * fix52 之后只有一条路线 —— 把目标应用交给**澎湃自己的 Freeform 小窗**打开
 * （`am start-activity --windowingMode 5` + `am task resize`）：
 * enable -> 启动 [CornerWindowService]（贴在小窗外的控制球 + 下发几何）；
 * disable -> 停止服务并收掉我们开的那个 Freeform task。
 */
class SingleHandManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun enable(pkg: String? = null, act: String? = null) {
        scope.launch {
            val state = StateManager.current
            // 已经在跑就必须先停掉：Service 走 onStartCommand 时会复用上一次的目标
            // （pendingPkg 保留旧值 + launched 已为 true），直接再 start 的结果是
            // 「点了整机模式/换了目标却毫无变化」。
            if (state.isEnabled) {
                runCatching {
                    context.stopService(Intent(context, CornerWindowService::class.java))
                }
                kotlinx.coroutines.delay(200)
            }
            val i = Intent(context, CornerWindowService::class.java)
            i.putExtra(CornerWindowService.EXTRA_CORNER, state.corner.name)
            // 空值也要写进去（空串）：整机模式没有具体目标，必须显式清掉上一次会话留下的
            // pendingPkg，否则会继续按"只搬那个应用"的老路径走。
            i.putExtra(CornerWindowService.EXTRA_PACKAGE, pkg ?: "")
            i.putExtra(CornerWindowService.EXTRA_ACTIVITY, act ?: "")
            // 记住目标，供悬浮球 / 下次冷启动一键复现
            if (!pkg.isNullOrEmpty()) StateManager.updateLastTarget(pkg, act)
            runCatching { context.startForegroundService(i) }
            StateManager.updateEnabled(true)
        }
    }

    /** 悬浮球一键开启：复用上次目标；没有记录则把当前前台应用搬进小窗。 */
    fun enableLast() {
        val s = StateManager.current
        enable(s.lastPackage, s.lastActivity)
    }

    fun disable() {
        scope.launch {
            runCatching { context.stopService(Intent(context, CornerWindowService::class.java)) }
            StateManager.updateEnabled(false)
        }
    }

    /**
     * ★ fix54：让**已在运行**的会话按当前设置热重摆（不重启服务、不重启目标应用）。
     *
     * 旧做法是 `stopService` → `delay(200)` → `startForegroundService`，那条路走下来
     * `CornerWindowService.onDestroy` 会抓记忆、清会话文件、`am stack remove` 收掉窗口，
     * 用户看到的是**整个小窗关掉重开**。现在只发一条带
     * [CornerWindowService.EXTRA_HOT_REAPPLY] 的 intent，服务自己重摆 overlay
     * 并把当前窗口平移到新位置，目标应用全程不动。
     *
     * 未开启时什么都不做（设置已经更新，下次启动自然按新设置走）。
     */
    private fun hotReapply() {
        if (!StateManager.current.isEnabled) return
        scope.launch {
            runCatching {
                val i = Intent(context, CornerWindowService::class.java)
                i.putExtra(CornerWindowService.EXTRA_HOT_REAPPLY, true)
                context.startForegroundService(i)
            }.onFailure { Log.w("SingleHandManager", "hot reapply failed", it) }
        }
    }

    /**
     * ★ fix88：开关「记住小窗大小」。★ fix128 语义修正：**只管大小**。
     *
     * 位置记忆始终生效（记录钩子照旧写、出生钩子照旧恢复位置）；本开关只决定
     * 「记忆里的宽高」用不用 —— 关 = 大小用学得的系统默认（DEFAULT_RECT）。
     * 开关值必须下发到 [HookContract.FLAGS_PATH]：出生钩子在 system_server 里，
     * 读不到 App 的 SP —— 不下发的话开关在主开窗链路上什么也管不了（旧 bug）。
     * ⚠ execAsync 会在命令尾追加 `>/dev/null`，自己的 `>` 重定向会被顶掉
     *   （文件截空），必须用 `| tee 文件`（fix124 真机实证）。
     * 运行中开着小窗的话走一次热重摆，让新规则立刻套到当前窗口上。
     */
    fun updateRememberWindowSize(enabled: Boolean) {
        StateManager.updateRememberWindowSize(enabled)
        pushRememberSizeFlag(enabled)
        hotReapply()
    }

    /**
     * ★ fix128：把「记住大小」开关写进 [HookContract.FLAGS_PATH]（hook 读）。
     * App 每次进程启动也要补写一次（见 [MemoryFreeformApp.onCreate]）——
     * 重装 / 重启后文件可能缺失，缺失时 hook 按"开"处理。
     */
    fun pushRememberSizeFlag(enabled: Boolean) {
        runCatching {
            RootManager.get().execAsync(
                "echo rememberSize=${if (enabled) 1 else 0} | tee ${HookContract.FLAGS_PATH}"
            )
        }
    }

    /**
     * ★ fix50：清空小窗位置记忆（主界面长按应用图标 / 长按"桌面·前台"格子弹窗确认后调用）。
     *
     * 位置记忆有**两份**，必须同时清掉，否则会出现「清了但下次还落在老位置」：
     *  ① App 侧 SharedPreferences —— 开小窗时 [xiaojw.memoryFreeform.service.CornerWindowService]
     *     读它决定初始几何；
     *  ② `/data/system/memoryfreeform_window_memory` —— system_server 里的出生钩子读它，
     *     管「小窗挂起 / 被系统关闭后点恢复」那条链路（那条路 App 侧够不着）。
     * ② 需要 root；文件不存在或 grep 无匹配都不算失败。
     *
     * `pkg == null` 表示清空全部。
     */
    /**
     * ★ fix63：把 **system_server 那份**位置记忆也清掉。
     *
     * 位置记忆有**两份**（同域，见 [WindowMemory] 类注释）：
     *  ① App 侧 SharedPreferences —— 我们**启动**小窗时读它；
     *  ② `/data/system/memoryfreeform_window_memory` —— 出生钩子在**恢复 / 复用**链路里读它。
     *
     * 以前改设置（尺寸 / 缩放 / 角落 / 离底边间距）只清了 ①（`WindowMemory.clearAll()`），
     * ② 原样留着旧几何 ⇒ 用户改完尺寸后，只要窗口走一次"恢复"链路（点窗外再点小窗、
     * 挂起很久再打开），钩子就拿**旧尺寸**把窗口顶回去 —— 用户看到的是"小窗大小会突然变化"。
     *
     * 真机实测（fix63）：把 mark.via 记成 `300,900,700,1300`，再
     * `am start-activity --windowingMode 5`，出来的窗口**精确**就是 `[300,900][700,1300]`
     * —— 证明这条路确实是它在定音。
     *
     * 需要 root（fork su），所以走协程；失败只记日志（文件不在也算成功）。
     */
    fun clearWindowMemory(pkg: String?) {
        scope.launch {
            if (pkg.isNullOrBlank()) {
                WindowMemory.clearAll()
                runCatching {
                    val f = HookContract.WINDOW_MEMORY_PATH
                    RootManager.get().executeFast("rm -f $f $f.tmp", 3000)
                }.onFailure { Log.w(TAG, "clearWindowMemory(all) failed: ${it.message}") }
                Log.i(TAG, "windowMemory: 已清空全部位置记忆")
            } else {
                WindowMemory.clear(pkg)
                runCatching {
                    val f = HookContract.WINDOW_MEMORY_PATH
                    // ★ fix64：包里**两个方向**的行都要删（`pkg=` 与 `pkg@L=`）。
                    //   包名只可能含 `[A-Za-z0-9._]`，把 `.` 转义成 `[.]` 就够了；
                    //   **不要**用 `Regex.escape`（它给的是 `\Q…\E`，Android 的 toybox grep 不认）。
                    val pat = pkg.replace(".", "[.]")
                    RootManager.get().executeFast(
                        "grep -vE '^$pat(@L)?=' $f > $f.tmp 2>/dev/null; " +
                            "mv $f.tmp $f; chmod 666 $f 2>/dev/null",
                        3000
                    )
                }.onFailure { Log.w(TAG, "clearWindowMemory($pkg) failed: ${it.message}") }
                Log.i(TAG, "windowMemory: 已清空 $pkg 的位置记忆")
            }
        }
    }

    companion object {
        private const val TAG = "SingleHandManager"
    }

    /** RootManager 持有者（应用级单例） */
    object RootManagerRef {
        @Volatile private var instance: RootManager? = null
        fun get(context: Context): RootManager =
            instance ?: synchronized(this) {
                instance ?: RootManager.get().also {
                    it.init(context.applicationContext)
                    instance = it
                }
            }
    }
}