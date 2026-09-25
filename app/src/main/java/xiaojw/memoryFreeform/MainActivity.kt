package xiaojw.memoryFreeform

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import xiaojw.memoryFreeform.core.AppState
import xiaojw.memoryFreeform.core.HookBridge
import xiaojw.memoryFreeform.core.SHLog
import xiaojw.memoryFreeform.core.SingleHandManager
import xiaojw.memoryFreeform.core.StateManager
import xiaojw.memoryFreeform.core.WindowMemory
import xiaojw.memoryFreeform.root.RootManager
import xiaojw.memoryFreeform.service.FloatingBallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable
)

class MainActivity : ComponentActivity() {
    private lateinit var manager: SingleHandManager
    private lateinit var root: RootManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = MemoryFreeformApp.instance.singleHandManager
        root = SingleHandManager.RootManagerRef.get(this)
        // ★ fix133：沉浸式状态栏 —— 内容绘制到状态栏之下，状态栏图标浮在 TopAppBar 上；
        //   状态栏/导航栏透明、图标深浅随系统深浅色自动处理（HyperOS 风格本就为边到边设计）。
        enableEdgeToEdge()
        // 界面用 miuix（HyperOS 风格开源组件库）渲染；窗口底色交给主题自身，
        // 页面过渡时露出的永远是页面底色，不会闪黑。
        setContent { AppTheme { SingleHandUi(manager, root) } }
    }

    override fun onResume() {
        super.onResume()
        syncFloatBall()
    }

    private fun syncFloatBall() {
        if (StateManager.current.floatBallEnabled) FloatingBallService.start(this)
        else FloatingBallService.stop(this)
    }

    private fun installedApps(): List<LaunchableApp> {
        val pm = packageManager
        // 本应用不出现在列表里：它的界面就是用来在主屏上操作小窗的，进小窗没有意义。
        return pm.getInstalledApplications(PackageManager.MATCH_ALL)
            .filterNot { it.packageName == packageName }
            .mapNotNull { info ->
                val launch = pm.getLaunchIntentForPackage(info.packageName) ?: return@mapNotNull null
                val component = launch.component ?: return@mapNotNull null
                LaunchableApp(
                    component.packageName,
                    component.className,
                    pm.getApplicationLabel(info).toString(),
                    info.loadIcon(pm)
                )
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    private fun SingleHandUi(manager: SingleHandManager, root: RootManager) {
        var settings by remember { mutableStateOf(false) }
        // 长按应用图标 → 弹窗清空它的小窗位置记忆。
        val showClear = remember { mutableStateOf(false) }
        var clearAppTarget by remember { mutableStateOf<LaunchableApp?>(null) }
        val state by StateManager.stateFlow.collectAsState()
        val scope = rememberCoroutineScope()
        var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        // ★ 1.0.102：miuix 0.8 的 TextField 只收 TextFieldValue（String 重载已删）
        var query by remember { mutableStateOf(TextFieldValue("")) }
        val context = LocalContext.current

        // 应用列表较重（图标解码），必须在 IO 线程加载
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { apps = installedApps() }
            loading = false
        }

        val filtered = remember(apps, query) {
            val q = query.text
            if (q.isBlank()) apps
            else apps.filter {
                it.label.contains(q, true) || it.packageName.contains(q, true)
            }
        }

        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            AnimatedContent(
                targetState = settings,
                transitionSpec = {
                    val dir = if (targetState) 1 else -1
                    // ★ fix133：去掉淡入淡出 —— 进场/出场同时半透明叠加，过渡中会蒙一层暗影；
                    //   现在纯水平滑动，干净不挡视线。
                    slideInHorizontally(tween(260)) { it * dir }.togetherWith(
                        slideOutHorizontally(tween(260)) { -it * dir }
                    )
                },
                label = "homeSettings"
            ) { showSettings ->
                if (showSettings) {
                    SettingsHost(state, manager, root) {
                        settings = false
                        syncFloatBall()
                    }
                } else {
                    HomePage(
                        apps = filtered,
                        loading = loading,
                        query = query,
                        onQuery = { query = it },
                        onLongPress = { app ->
                            clearAppTarget = app
                            showClear.value = true
                        },
                        onSettings = { settings = true },
                        onRefresh = {
                            scope.launch {
                                loading = true
                                withContext(Dispatchers.IO) { apps = installedApps() }
                                loading = false
                            }
                        }
                    )
                }
            }

            // 长按清空记忆的确认弹窗（★ 1.0.102：WindowDialog = miuix 0.8 的居中窗口弹窗，
            // 取代 0.3 的 SuperDialog 底部弹层；show 收布尔值）。
            val target = clearAppTarget
            WindowDialog(
                show = showClear.value,
                title = if (target == null) "清空全部位置记忆" else "清空位置记忆",
                onDismissRequest = { showClear.value = false }
            ) {
                Text(
                    if (target == null) {
                        "所有应用记住的小窗位置和大小都会被删除（当前已记住 ${WindowMemory.size()} 个）。" +
                            "下次打开回到默认位置，需要重新拖动摆放。"
                    } else {
                        "「${target.label}」记住的小窗位置和大小将被删除。" +
                            "下次打开回到默认位置。只影响这一个应用。"
                    },
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                // ★ fix136：两个按钮用 weight(1f) 均分（减去间距），不再各自 fillMaxWidth
                //   导致 Row 里两个满宽子项冲突、按钮撑不开。
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { showClear.value = false },
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (target == null) {
                                manager.clearWindowMemory(null)
                                Toast.makeText(context, "已清空全部位置记忆", Toast.LENGTH_SHORT).show()
                            } else {
                                manager.clearWindowMemory(target.packageName)
                                Toast.makeText(context, "已清空「${target.label}」的位置记忆", Toast.LENGTH_SHORT).show()
                            }
                            showClear.value = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("清空") }
                }
            }
        }
    }
}

/** HyperOS 风格主题：跟随系统深浅色。 */
@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) { content() }
}

@Composable
private fun HomePage(
    apps: List<LaunchableApp>,
    loading: Boolean,
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onLongPress: (LaunchableApp?) -> Unit
) {
    // ★ fix135：TopAppBar 的 largeTitle「记忆小窗」随应用列表上滑收起（collapsing toolbar）。
    //   MiuixScrollBehavior + nestedScroll 把列表滚动接到 TopAppBar：上滑即收、下拉回弹。
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "记忆小窗",
                largeTitle = "记忆小窗",
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "刷新") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载中…", color = MiuixTheme.colorScheme.onBackgroundVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(12.dp),
                // 把列表滚动接到 TopAppBar：上滑收起 largeTitle，下拉回弹展开
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                // 搜索框 + 提示随列表一起上滑（避免 largeTitle 收起后顶部留空洞）
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        TextField(
                            value = query,
                            onValueChange = onQuery,
                            insideMargin = DpSize(16.dp, 12.dp),
                            leadingIcon = { Icon(MiuixIcons.Search, null, Modifier.padding(start = 10.dp)) },
                            trailingIcon = {
                                if (query.text.isNotEmpty()) {
                                    IconButton(onClick = { onQuery(TextFieldValue("")) }) { Icon(Icons.Default.Close, "清空") }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        SmallTitle("长按应用图标可清除它记住的小窗位置和大小")
                    }
                }
                // 长按它 = 清空全部位置记忆（target=null 走"清空全部"弹窗）
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AppTile(null, "全部位置记忆", onLongPress)
                }
                items(apps, key = { it.packageName }) { app ->
                    AppTile(app, app.label, onLongPress)
                }
            }
        }
    }
}

/**
 * 应用格子。★ 1.0.113：单击不再开小窗（该功能已删）；**长按 = 清位置记忆**。
 *
 * 用 `pointerInput` + `detectTapGestures` 只监听长按 —— 单击无任何响应。
 */
@Composable
private fun AppTile(
    app: LaunchableApp?,
    label: String,
    onLongPress: (LaunchableApp?) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp)
            .pointerInput(app) {
                detectTapGestures(onLongPress = { onLongPress(app) })
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (app != null) {
            Image(
                app.icon.toBitmap(96, 96).asImageBitmap(),
                label,
                Modifier.size(56.dp)
            )
        } else {
            Icon(
                Icons.Default.Android, label, Modifier.size(56.dp),
                tint = MiuixTheme.colorScheme.primary
            )
        }
        // 图标与名称的间距固定、名称限宽居中 —— 之前名称不限宽，
        // 长名字会把格子撑歪，整个网格基线参差。
        Spacer(Modifier.height(6.dp))
        Text(
            label, maxLines = 1, textAlign = TextAlign.Center,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onBackground
        )
    }
}

/** 设置页内部的页面。设置页是一个**页面栈**：点条目=入栈，返回键/返回箭头=出栈。 */
private enum class SettingsDest(val title: String) {
    ROOT("设置"),
    LOG("诊断日志")
}

private class SettingsNav {
    private val back = mutableStateListOf(SettingsDest.ROOT)

    /** true = 入栈（新页从右滑入）；false = 出栈（新页从左滑入）。 */
    var advancing by mutableStateOf(true)

    val current: SettingsDest get() = back.last()

    fun push(dest: SettingsDest) {
        advancing = true
        back.add(dest)
    }

    /** 出栈。已在根页时返回 false —— 由调用方翻译成「退出设置页回到应用列表」。 */
    fun pop(): Boolean {
        if (back.size <= 1) return false
        advancing = false
        back.removeAt(back.lastIndex)
        return true
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun SettingsHost(
    state: AppState,
    manager: SingleHandManager,
    root: RootManager,
    onExit: () -> Unit
) {
    val nav = remember { SettingsNav() }
    BackHandler { if (!nav.pop()) onExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = nav.current.title,
                largeTitle = nav.current.title,
                navigationIcon = {
                    IconButton(onClick = { if (!nav.pop()) onExit() }) {
                        // ★ 1.0.106：48dp + 距左 20dp（用户指定）
                        Icon(MiuixIcons.Back, "返回", Modifier.padding(start = 26.dp).size(30.dp))
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = nav.current,
            transitionSpec = {
                val dir = if (nav.advancing) 1 else -1
                // ★ fix133：同上，去掉淡入淡出，纯滑动，避免过渡暗影。
                slideInHorizontally(tween(260)) { it * dir }.togetherWith(
                    slideOutHorizontally(tween(260)) { -it * dir }
                ).using(SizeTransform(clip = true))
            },
            label = "settingsPage"
        ) { dest ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                when (dest) {
                    SettingsDest.ROOT -> SettingsRoot(state, manager, root, nav)
                    SettingsDest.LOG -> LogSection(root)
                }
            }
        }
    }
}

@Composable
private fun SettingsRoot(
    state: AppState,
    manager: SingleHandManager,
    root: RootManager,
    nav: SettingsNav
) {
    val context = LocalContext.current
    // Root 探测首次会 fork su（最多 5s），只能记在状态里而不是每次重组都调。
    var rootReady by remember { mutableStateOf(root.isReady()) }

    SmallTitle("Root 授权")
    Card(Modifier.padding(horizontal = 12.dp)) {
        SuperArrow(
            title = "Root 授权状态",
            summary = "清记忆、开关窗、日志导出都依赖 su",
            // ★ 1.0.102：miuix 0.8 的 SuperArrow 删了 rightText，改用 endActions
            endActions = {
                Text(
                    if (rootReady) "已授权" else "未授权",
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
            },
            onClick = {
                rootReady = root.checkRoot()
                Toast.makeText(context, if (rootReady) "Root 可用" else "Root 不可用", Toast.LENGTH_SHORT).show()
            }
        )
    }

    SmallTitle("快捷入口")
    Card(Modifier.padding(horizontal = 12.dp)) {
        SuperSwitch(
            title = "显示悬浮球",
            summary = "点一下展开菜单（最近任务、应用等），可拖拽贴边",
            checked = state.floatBallEnabled,
            onCheckedChange = { enable ->
                StateManager.updateFloatBall(enable)
                if (enable) {
                    if (!Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    } else {
                        FloatingBallService.start(context)
                    }
                } else {
                    FloatingBallService.stop(context)
                }
            }
        )
        SuperSwitch(
            title = "悬浮球滑动选择菜单",
            summary = "开：按住悬浮球直接滑到菜单项上松手触发；关：点一下展开菜单再点选",
            checked = state.floatBallSlideMode,
            onCheckedChange = { StateManager.updateFloatBallSlide(it) }
        )
        SuperSwitch(
            title = "悬浮球空闲自动贴边",
            summary = "开：5 秒不操作自动收起贴边（只留一条小缝）；点一下收起的球即可恢复",
            checked = state.autoDock,
            onCheckedChange = { StateManager.updateAutoDock(it) }
        )
    }

    SmallTitle("LSPosed 模块状态")
    Card(Modifier.padding(horizontal = 12.dp)) {
        var hookActive by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) {
            hookActive = withContext(Dispatchers.IO) {
                runCatching { HookBridge.isActive() }.getOrDefault(false)
            }
        }
        BasicComponent(
            title = if (hookActive == true) "已激活" else "未激活",
            summary = when (hookActive) {
                true -> "小窗出生即目标几何，0.70 图层缩放已中和"
                false -> "在 LSPosed 中勾选本模块并把作用域设为「系统框架」，重启后生效"
                null -> "检测中…"
            }
        )
    }

    SmallTitle("小窗行为")
    Card(Modifier.padding(horizontal = 12.dp)) {
        SuperSwitch(
            title = "记住小窗大小",
            // ★ fix128：语义修正 —— 位置始终按各应用记忆恢复（记录钩子照旧写、出生钩子照旧
            //   恢复位置），本开关只决定「记忆里的宽高」用不用：关 = 大小用学得的系统默认。
            summary = "开：每个应用记住上次的小窗大小；关：一律用系统默认大小（位置始终按记忆恢复）",
            checked = state.rememberWindowSize,
            onCheckedChange = { manager.updateRememberWindowSize(it) }
        )
    }
    SmallTitle("更多设置")
    Card(Modifier.padding(horizontal = 12.dp)) {
        SuperArrow(
            title = "诊断日志",
            summary = "导出 / 分享 / 清空运行日志",
            onClick = { nav.push(SettingsDest.LOG) }
        )
    }
}

/**
 * 导出脚本：把统一日志、hook 的自检文件、当前小窗几何、以及一份按 tag 过滤的
 * logcat 快照合成一份到 Download。hook 跑在 system_server 里，只有 /data/system
 * 下的自检文件能证明它装上没有，这些必须走 su 才读得到。
 */
private const val EXPORT_LOG_CMD =
    "D=/sdcard/Android/data/xiaojw.memoryFreeform/files/logs; " +
        "O=/sdcard/Download/memoryfreeform_log.txt; " +
        "P=/data/data/xiaojw.memoryFreeform/files/logs; " +
        "S=/data/system; " +
        "{ echo \"==== 1. app 日志（外置 + 私有 + 全部归档）====\"; " +
        "cat \$D/memoryfreeform.log.1 2>/dev/null; " +
        "cat \$D/memoryfreeform-*.log 2>/dev/null; " +
        "cat \$D/memoryfreeform.log 2>/dev/null; " +
        "cat \$P/memoryfreeform-*.log 2>/dev/null; " +
        "cat \$P/memoryfreeform.log 2>/dev/null; " +
        "echo; echo \"==== 2. root 兜底日志 /data/local/tmp/memoryfreeform.log ====\"; " +
        "cat /data/local/tmp/memoryfreeform.log 2>/dev/null; " +
        "echo; echo \"==== 3. hook 自检文件（\$S）====\"; " +
        "for f in memoryfreeform_hook.active memoryfreeform_miui_scale.state memoryfreeform_birth.state " +
        "memoryfreeform_birth_target memoryfreeform_session memoryfreeform_window_memory memoryfreeform_hook.off; do " +
        "echo \"--- \$f ---\"; cat \$S/\$f 2>/dev/null; echo; done; " +
        "echo; echo \"==== 4. 小窗几何（freeform RootTask）====\"; " +
        "am stack list 2>/dev/null | grep -B1 mWindowingMode=freeform | grep -E 'RootTask|taskId='; " +
        "echo; echo \"==== 5. logcat (memoryfreeform tags) ====\"; " +
        "logcat -d -v threadtime -s CornerWindow WindowWatcher App SHLog FloatBall RootManager " +
        "2>/dev/null | tail -n 1500; " +
        "} > \$O; chmod 666 \$O; wc -c \$O; echo EXPORT_OK"

@Composable
private fun LogSection(root: RootManager) {
    val context = LocalContext.current
    var summary by remember { mutableStateOf(logSummary()) }
    var busy by remember { mutableStateOf(false) }

    Card(Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("小窗会话、hook 自检文件、freeform task 几何、crash 缓冲全部写进同一个文件，可直接复制或分享")
            Text(
                "排查「小窗尺寸/位置不对」：先看 app 日志里的 windowMemory / miuiRect / resizeTask 三行；" +
                    "再看导出的 memoryfreeform_miui_scale.state 是否 neutralized=1 installed>0",
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Text(
                "注：LSPosed 重定向日志不在这里，需到 LSPosed 管理器 → 日志 里看",
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Text(
                summary,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                Thread {
                    val r = root.execute(EXPORT_LOG_CMD)
                    val ok = r.output.contains("EXPORT_OK")
                    (context as? android.app.Activity)?.runOnUiThread {
                        busy = false
                        summary = logSummary()
                        Toast.makeText(
                            context,
                            if (ok) "已导出：/sdcard/Download/memoryfreeform_log.txt"
                            else "导出失败：${r.output.take(120)}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            },
            modifier = Modifier.weight(1f)
        ) { Text(if (busy) "导出中…" else "导出到 Download") }

        Spacer(Modifier.width(8.dp))
        Button(onClick = { shareLog(context) }, modifier = Modifier.weight(1f)) { Text("分享") }
    }

    Row(Modifier.padding(horizontal = 12.dp)) {
        TextButton(
            text = "清空",
            onClick = {
                SHLog.clear()
                summary = logSummary()
                Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            text = "复制路径",
            onClick = {
                val p = SHLog.path() ?: "（日志文件不可写）"
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("memoryfreeform log path", p))
                Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.weight(1f)
        )
    }
}

private fun logSummary(): String {
    val p = SHLog.path() ?: return "日志文件不可写（检查存储权限）"
    return "$p\n当前大小：${SHLog.sizeBytes() / 1024} KB"
}

private fun shareLog(context: android.content.Context) {
    try {
        val src = java.io.File(SHLog.path() ?: return)
        if (!src.exists()) {
            Toast.makeText(context, "还没有日志", Toast.LENGTH_SHORT).show()
            return
        }
        // FileProvider 只能授权 app 自己的 external-files 目录，先复制过去
        val dir = context.getExternalFilesDir("logs") ?: return
        if (!dir.exists()) dir.mkdirs()
        val dst = java.io.File(dir, "memoryfreeform.log")
        if (src.absolutePath != dst.absolutePath) src.copyTo(dst, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", dst
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "memoryfreeform 日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享日志"))
    } catch (t: Throwable) {
        Toast.makeText(context, "分享失败：${t.message}", Toast.LENGTH_LONG).show()
    }
}
