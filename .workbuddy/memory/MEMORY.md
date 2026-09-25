# memory-freeform 长期笔记（<3000 字符）

> 源码包名与 applicationId 一致：`xiaojw.memoryFreeform`（hook 子包 `xiaojw.memoryFreeform.hook`）。
> 细则见同目录 `ARCH-DETAIL.md`（悬浮球·菜单/主线程铁律/出生钩子恢复链路/记忆链路/
> 手势条判定链/取证速查/★清记忆断根/杂项）。**动悬浮球/出生钩子/清记忆之前先读 ARCH-DETAIL。**
> 架构已于 fix36 整体改轨：自造窗口全部删除，现行方案只走 MIUI Freeform。

## 仓库 / 构建 / 环境
- 双远端 + 本地单一 master：`origin`=gitee(xiaojinwen/memory-freeform, 默认分支 master)、
  `github`=github(xiaojinwen/memoryFreeform, 默认分支 main)；推送映射
  `git config remote.github.push refs/heads/master:refs/heads/main`。
  即 `git push origin`→gitee master，`git push github`→github main。旧 .git 备份在
  `E:\project\single-hand-mode-git-backup-20260925`（可删）。
  ⚠ GitHub 首次连不上 ⇒ `ssh-keyscan -H github.com >> ~/.ssh/known_hosts`。
- 构建：`./gradlew.bat :app:assembleDebug`（JAVA_HOME=C:\Users\23123\.jdks\jbr-21.0.11、
  ANDROID_HOME=…\Android\Sdk）→ 拷 `E:\搬家文件夹\` → 提交 `fixNN: 中文摘要` → present_files。
  ★★ 每次 fix 必改 build.gradle.kts：versionCode=fix 序号、versionName=1.0.<fix号>。
  APK 输出名已改为 `memory-freeform_<versionName>.apk`（debug 带 `-debug` 后缀）。
- 签名：项目根 `keystore.properties`（已 gitignore）指向 `E:\project\apk-keys\app.jks`（alias xjwkey），
  debug/release 统一此签名（build.gradle.kts 读 properties 建 signingConfigs）。
  SDK 实际在 `%LOCALAPPDATA%\Android\Sdk`（build-tools 34/35，无 36；apksigner 在 35.0.0）。
- 工具链（1.0.102 起）：Gradle 8.14.3（腾讯源）/ AGP 8.13.2 / Kotlin 2.3.20+compose 插件 /
  compileSdk 36 / BOM 2026.03.01 / miuix 0.8.8+icons。lifecycle 2.11 别用（要 SDK37+AGP9.1）。
  miuix 0.8 断点：TextField 收 TextFieldValue、Slider material 签名、SuperArrow 用 endActions、
  图标 MiuixIcons.Back（icon.extended）。
- Gradle daemon 卡住 ⇒ `./gradlew.bat --stop`；dexBuilderDebug AccessDeniedException ⇒
  `rm -rf app/build/intermediates/project_dex_archive`。
- ⚠ `git rm` 会删整个工作树 → 用 `rm -f`+`git commit -a`；Edit 可能报成功未落盘 → grep 复验；
  Bash PATH 破损 → 前导 `export PATH="/c/Windows/System32:/c/Windows:/usr/bin:/bin:$PATH"`。
- 仓库无 native 代码；真机 Xiaomi 2304FPN6DC / Android 16 / HyperOS 3.0 / 逻辑屏 1080x2400。
  hook 在 system_server ⇒ **装后必重启**（纯 App UI 改动 force-stop 即生效）。总闸
  `/data/system/memoryfreeform_hook.off`。重启后先 WAKEUP+上滑解锁；测方向先锁 rotation。
- ⚠ 绝不用 `echo >` 覆盖 `memoryfreeform_window_memory`；注入 motionevent 的 DOWN 必须 UP。
- ⚠ RootManager.execAsync 尾部追加 `>/dev/null`，命令里自己的 `> 文件` 被顶掉 ⇒
  **写文件必须用 `| tee 文件`**（真机验证过）。
- ★ 删方法铁律：先 grep 全仓确认零调用方再删；`tools/` 不参与编译。

## 核心机制（真机定论，勿推翻）
- ★★★ `Task.getBounds()`=逻辑屏坐标；图层缩放补偿 `WindowSizing.MIUI_LAYER_SCALE`=**0.70**
  （下发尺寸÷0.70）。fix105 曾改 1.0、fix109 已平反 —— 探针 getScale() 不可信，别再推翻。
- ★★★ 清记忆断根三步：删记忆文件 → `am stack remove` 清残留 task → 再删文件+重启
  （不清则钩子把坏值写回；fullscreen 残留会让 `--windowingMode 5` 复用不进 freeform）。
- ★★★ 关小窗 ≠ remove Task；唯一存活判据 `getWindowingMode()`(5=freeform)；"用户拖 vs
  系统重摆"只能靠来源+几何特征（全屏帧/失焦角 [35,127] 族）区分。
- ★★★ 写入来源白名单：只 `onMovedByResize`（触摸拖）+ `Task.resize`（我们的 am resize）可信；
  onResize/setBounds skip-write 只刷路由。resize 站点只登记活窗，**绝不能删**。
- fix80：RESIZE 帧不改记忆 left/top（>8px 判无效只采宽高）；有触摸坐标照单全收。
- fix91 尺寸写回四闸：非失焦角+有 lastSize 基准+差>8px+isTouched(pkg,2s)。开关默认开。
- fix77 doWrite 三闸：①荒谬几何 ②退出动效全屏帧(w>=sw-40&&h>=sh-40)拒 ③超屏 clamp。
- fix78i 手势条：DOWN 快照；UP 后 +900ms/+2.5s 采样判移动/关闭/重开；BAR_PENDING_TIMEOUT(6s)。
  PointerEventListener 注册在 WMS 本体，Proxy 必须答 equals/hashCode。
- fix89/90：退出 freeform 无条件 cancelPending；无触摸帧 IDLE_DEBOUNCE 500ms；
  onRemove 无手势+5s 无触摸不写。fix92 出生安静期。
- fix93 切角=`WindowMemory.mirrorAllHorizontally`（D=屏幕边÷scale），**绝不清记忆**；
  镜像落盘回调后才准 reapplyHot。fix86 仅横屏顶部让位（topAvoidPx，App+hook 都要）。
- 记忆按方向分键（`pkg=`/`pkg@L=`），SP 与系统文件共用 HookContract.memoryKey，不跨方向回退；
  重开优先读钩子实时写的 WINDOW_MEMORY_PATH，读不到退 SP/设置。记忆优先于设置；
  改尺寸类设置两份都清 = `WindowMemory.clearAll()`+`wipeSystemWindowMemory()`。
- 图层：浮层 APPLICATION_OVERLAY(111000) 恒在小窗(21000)之上；overlay 必带 FLAG_NOT_TOUCH_MODAL；
  无 bringToFront，z 序=add 顺序；WRAP_CONTENT 先 measure 再摆。
- 架构：只 `am start-activity --windowingMode 5`；屏上已有小窗绝不能再 start →
  adoptExisting（判据 `WindowWatcher.lastTasks()`）。
- 1.0.124：默认尺寸学习链路真机通过（RecordHook 出生帧四闸 → `/data/system/memoryfreeform_default_rect`）；
  仅自启动+无记忆才应用（self_launch 标记 15s TTL）。⚠ 横幅窗出生帧走 Task.resize 没学。
- 1.0.122 起无「单击图标开小窗」；1.0.112 launchApp 是纯系统调用（app 零参与窗口尺寸）。
- 1.0.128：「记住小窗大小」开关经 root 写 `/data/system/memoryfreeform_flags`(rememberSize=1/0)，
  位置始终按记忆恢复、开关只管宽高（关=DEFAULT_RECT）。钩子改动装后重启。
  ★ fix142 起该开关**默认关**：AppState/prefs 默认 false，且 hook 兜底（文件缺失）也由"开"翻成"关"。
- 调试：`memoryfreeform_record.state(.moved)` 探针、jadx 反编译资产在 `.workbuddy/tmp/rom/`。

## ★ 小窗圆角（fix140，勿推翻）
- 圆角=SurfaceFlinger 图层属性 `roundedCorner`，稳定值 **67.1429 = 47/0.70**（47px=18dp）。
- ★★ **fix144 推翻上面第一条：真因是我们自己的 BirthHook**。关掉 hook 圆角**照样**
  补间（14.9→67），但不闪 —— 因为窗口同时在缩放，圆角同步长出来是自然变形。
  BirthHook 让窗口"出生即最终尺寸"后，尺寸不动、只有圆角 0→67，直角才暴露。
  所以要么把圆角钉住，要么别让尺寸一步到位。
- 修法（fix144，`hook/FreeformCornerKeeperHook.kt`，**装 system_server**）：
  leash 是 system_server 建的（`WindowContainer.makeAnimationLeash()` 返回
  `SurfaceControl$Builder`，再 `.build()` 才是 leash；老 ROM 走
  `SurfaceAnimator.createAnimationLeash`）。拿到 leash 后 **5ms 间隔写终值 67.14、
  持续 900ms**，盖掉 SystemUI 每 16ms 的补间帧；缩放/位移/透明度不受影响。
  ⚠ `Resources.getSystem().displayMetrics.density` 在 system_server 里是 **3.5**
  （真机 2.625），算出来会是 90 —— 用反射 `getMiuiFreeformCornerRadius/getFreeformScale`
  或实测值 **67.1429**。
- ★★ **fix145 起 SystemUI 作用域可用了**（用户在 LSPosed UI 里手动勾上了，db 里可见
  `('xiaojw.memoryFreeform','com.android.systemui',0)`，日志有
  `corner hook installed in com.android.systemui`）。**只有"手工 INSERT db"那条路会整机
  失效**，UI 勾选是安全的 —— fix143 那条"本机拿不到作用域"的定论作废。
- 修法（正解，fix147）：`hook/FreeformCornerHook.kt` 装 **com.android.systemui**，两条腿：
  ① 拦 `MultiTaskingFolmeState.addProperty` 令起点=终点（实测 `snap` 在涨，命中）；
  ② 兜底拦 `SurfaceControl$Transaction.setCornerRadius`，只抬**递增**段 + 栈含
  miuifreeform/multitasking；递减段（关闭/缩迷你窗）放行。
  ★★ 兜底的自适应 `stable` **必须设上限 = defaultTarget()*1.02**：folme 是弹簧动画会
  **过冲到 70.88**，无条件 `max()` 会把过冲值当稳定值 ⇒ 圆角被永久钉成 70.88（"圆角奇怪"）；
  同理只认 **[30,120]** 区间的值，否则全屏动画的 235 会被学走（SF 显示 335.7）。
  实测正解：stable=67.14286、clamped 持续增长、采样无个位数帧。
- 兜底 `FreeformCornerKeeperHook`（system_server 抢 leash 高频写）**默认关**，需
  `touch /data/system/memoryfreeform_corner_keeper.on`；实测它只能写 1~2 帧就
  `mNativeObject ... is null`（leash 早被 release），收益不划算。自检另存
  `/data/system/memoryfreeform_corner_keeper.state`。
- ★★ **LSPosed 作用域必须用 resource 数组形式才会预勾**：`xposedscope` =
  `@array/xposed_scope`（`res/values/arrays.xml`：android + com.android.systemui）。
  字符串 value LSPosed 不解析 = 没声明（fix140 就是这么翻车的）。
  排查：`cat /data/adb/lspd/log/modules_*.log | grep "installed in"`。
- 开关 `memoryfreeform_corner.off`；自检 `/data/system/memoryfreeform_corner.state`
  （installed/clampSites/snap/fromTo/clamped/zero/learned/stable）。
  ⚠ **SystemUI 写不进 /data/system（EACCES）**，该文件的真实内容可能是 system_server 留下的旧值；
  计数要看 **LSPosed 日志** `grep "SingleHand/Corner installed"`（每 20s 刷一次）。
  ⚠ 只有**冷开**（先 `am stack remove` 掉已有 freeform task）才走入场动画，否则计数不动。
  取证：`dumpsys SurfaceFlinger | awk '/Layer \[/{n=$0} /roundedCorner/{print n" => "$0}'`。

## 近期版本（更早的看 git 历史）
- ★1.0.141：①**悬浮球默认关**（AppState.floatBallEnabled 默认 false + prefs 默认 false，老用户已存值不受影响）。
  ②**「获取应用列表」权限引导**：新增 `core/AppListPermission.kt`——判定 =
  `checkSelfPermission(QUERY_ALL_PACKAGES)` **且** 可见包数 ≥10（澎湃把该权限做成用户开关，
  checkSelfPermission 恒 GRANTED 不可信，只能靠包数被过滤这件事反推）；MainActivity.onResume
  refresh+shouldPrompt（**首次无条件弹一次**，点过即记 prompted，之后只在真没权限时弹）；
  该权限无标准运行时申请框，只能送授权页：`miui.intent.action.APP_PERM_EDITOR`
  → `com.miui.securitycenter` 的 PermissionsEditorActivity / AppPermissionsEditorActivity
  → 系统应用详情页三级兜底。设置页新增「权限」卡片显示状态并可跳转。
- ★★1.0.131（463cdec）：关闭小窗竞态修复（onDestroy 现查 am stack list 补收未登记 task）；
  菜单删「切换角落」；最近任务面板高度 -50dp + 行横滑 ≥72dp 关小窗；应用面板打开即聚焦搜索框。
- ★★1.0.130（c53a46c）：**悬浮球坐标域定论**——overlay 布局 y 原点在状态栏下方（frame=attrs+108px），
  触摸 rawX/rawY 是绝对坐标；touchOnBall/showMenu/钳制全改绝对坐标；弹出 300ms 内 OUTSIDE 免疫。
  球径 60dp。⚠ MenuRoot 调试日志仍保留，下版可清。
- ★1.0.129：fix126 MenuRoot 回归——DOWN 不落球上一律 super.dispatchTouchEvent；删「隐藏悬浮球」。
- ★1.0.126（cd552f4）：菜单三修（先取 target 再收菜单；MenuRoot 接管球区域手势；扇形半径 47dp）。
- ★1.0.125：BirthHook clamp 与 RecordHook doWrite 同口径（maxR=size/0.70+横屏 topSafe 地板）；
  FloatingBallService.onConfigurationChanged。fix125 曾漏提交，随 fix126 入库。
- ★1.0.123：`SingleHandManager` **不能删**，活跃入口见 FloatingBallService/MainActivity 多处。

## 待查
- shared_prefs 全空（设置从不落盘）疑点，未终判。
