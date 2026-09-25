package xiaojw.memoryFreeform.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ★ fix141：「获取应用列表」权限（manifest 里的 `QUERY_ALL_PACKAGES`）。
 *
 * 主页要列出所有可开小窗的应用，靠的就是这个权限。澎湃 / MIUI 把它做成了
 * **用户可关的开关**（设置 → 应用设置 → 应用权限 → 获取应用列表），关掉之后：
 *  - `checkSelfPermission` 在部分 ROM 上**仍然返回 GRANTED**（manifest 声明即授予），
 *    所以不能只信它；
 *  - 但包列表会被系统过滤成很小的一撮（正常机器几十上百个），这是**可观测**的。
 *
 * 于是判定走两条：`checkSelfPermission` 为 DENIED 直接判无；否则再数一次包数量，
 * 少于 [MIN_VISIBLE_APPS] 也判无（ROM 不配合过滤时这条恒真，最坏结果是弹一次引导，
 * 不会误判成"有权限却什么都不给看"）。
 *
 * 该权限**没有标准的运行时申请接口**（`requestPermissions` 弹不出它的框），
 * 只能把用户送到授权页 —— 优先澎湃的权限编辑页，找不到就退系统应用详情页。
 */
object AppListPermission {

    /** 判定"已授权"所需的最小可见应用数（真机通常 100+，被过滤后只剩个位数）。 */
    private const val MIN_VISIBLE_APPS = 10

    private const val PREFS = "memory_freeform_perm"
    private const val KEY_PROMPTED = "app_list_prompted"

    private val _granted = MutableStateFlow(false)
    val grantedFlow: StateFlow<Boolean> = _granted.asStateFlow()
    val granted: Boolean get() = _granted.value

    /** 在 IO 线程里查一次真实状态并推给 UI（[grantedFlow]）。 */
    fun refresh(context: Context) {
        val (ok, n) = probe(context)
        _granted.value = ok
        SHLog.i("AppListPerm", "granted=$ok visibleApps=$n")
    }

    /** 是否已拿到应用列表权限。 */
    fun isGranted(context: Context): Boolean = probe(context).first

    /**
     * 是否需要弹「去授权」引导。
     *
     * 除"确实没权限"外，首次安装后**无条件弹一次**：有些 ROM 的过滤行为查不出来
     * （[probe] 恒真），只有这一次兜底能保证用户知道要去哪儿开。
     * 用户点过任意按钮后就记 [KEY_PROMPTED]，之后只在真的没权限时才再弹。
     */
    fun shouldPrompt(context: Context): Boolean {
        if (!_granted.value) return true
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPTED, false)
    }

    /** 引导已弹过（无论用户选了"去开启"还是"暂不"），之后不再无条件打扰。 */
    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /** @return (是否已授权, 可见应用数) */
    private fun probe(context: Context): Pair<Boolean, Int> {
        if (context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES)
            != PackageManager.PERMISSION_GRANTED
        ) return false to 0
        val n = runCatching {
            context.packageManager.getInstalledApplications(PackageManager.MATCH_ALL).size
        }.getOrDefault(0)
        return (n >= MIN_VISIBLE_APPS) to n
    }

    /**
     * 打开「获取应用列表」授权页。
     *
     * 三级兜底：澎湃权限编辑页（两个历史类名）→ 系统应用详情页。
     * 每个都用 `runCatching` 包住 —— 目标 Activity 不存在会抛
     * `ActivityNotFoundException`，不能让一次跳转把整个页面带崩。
     */
    fun openSettings(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                .putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
                .putExtra("extra_pkgname", pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
        for (it in candidates) {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(it); true }.getOrDefault(false)) return
        }
    }
}
