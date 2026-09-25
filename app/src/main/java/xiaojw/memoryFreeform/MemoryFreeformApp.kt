package xiaojw.memoryFreeform

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import xiaojw.memoryFreeform.core.SHLog
import xiaojw.memoryFreeform.core.SingleHandManager
import xiaojw.memoryFreeform.core.StateManager
import xiaojw.memoryFreeform.root.RootManager

class MemoryFreeformApp : Application() {

    val singleHandManager: SingleHandManager by lazy {
        SingleHandManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        // 日志文件要最先就绪：越早 init，崩溃前的日志越全。
        // 传 context 是为了让 app 进程优先落私有目录（外置存储那条在某些 ROM 上写不进）
        SHLog.init(this)
        // 记一次环境指纹：日志脱离设备后仍能还原现场
        SHLog.i(
            "App",
            "启动 android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "device=${android.os.Build.MODEL} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()} " +
                "log=${SHLog.path()}"
        )
        fixLogPerm()
        StateManager.init(this)
        // ★ fix48：每个 App 各自的小窗位置记忆（SharedPreferences，同进程即时可见）
        xiaojw.memoryFreeform.core.WindowMemory.init(this)
        // ★ fix51：**全局**小窗位置监听。只盯 `am stack list` 里的 freeform RootTask，
        //   所以不管小窗是澎湃自己开的还是我们开的，关闭 / 挂起时都能记住位置。
        //   放在 Application 里起：进程一起来就开始跟踪（悬浮球常驻时进程一直在）。
        xiaojw.memoryFreeform.core.WindowWatcher.ensureStarted(this)
        // ★ fix128：把「记住小窗大小」开关下发给 hook（/data/system/memoryfreeform_flags）。
        //   重装 / 重启后文件可能缺失（缺失时 hook 按"开"兜底），进程起来就补写自愈。
        singleHandManager.pushRememberSizeFlag(StateManager.current.rememberWindowSize)
        instance = this
    }

    /**
     * 日志目录可能先被别的进程创建（属主 root、目录 700），app 进程写不进 →
     * **整个 app 侧诊断日志全军覆没**，只剩 logcat，而导出过滤一漏 tag 就彻底抓瞎
     * （真机实测）。本进程反正要拿 root：后台一次性把目录/文件权限修成 app 可写（幂等）。
     * SHLog 写失败后每 30s 会从头重试候选路径，权限修好的瞬间自动恢复落盘。
     */
    private fun fixLogPerm() {
        Thread({
            runCatching {
                RootManager.get().executeFast(
                    "mkdir -p ${SHLog.DIR_SHARED}; chmod -R 777 ${SHLog.DIR_SHARED}; " +
                        "touch ${SHLog.FILE_FALLBACK}; chmod 666 ${SHLog.FILE_FALLBACK} ${SHLog.FILE_SHARED}",
                    5000
                )
                SHLog.i("App", "log perm fixed, path=${SHLog.path()}")
            }
        }, "log-perm").apply { isDaemon = true; start() }
    }

    companion object {
        lateinit var instance: MemoryFreeformApp
            private set
    }
}
