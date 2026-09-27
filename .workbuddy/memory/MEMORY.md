# memory-freeform 长期笔记

> 包名 = applicationId = `xiaojw.memoryFreeform`（hook 子包 `xiaojw.memoryFreeform.hook`）。
> **动悬浮球 / 出生钩子 / 清记忆之前先读同目录 `ARCH-DETAIL.md`。**
> 架构自 fix36 起定轨：自造窗口全部删除，只走 MIUI Freeform。

## 仓库 / 构建 / 环境
- 双远端：`origin`=gitee(memory-freeform, 默认分支 master)、`github`=github(memoryFreeform, 默认分支 main)；
  映射 `git config remote.github.push refs/heads/master:refs/heads/main`。`git push origin`→gitee master，`git push github`→github main。
- 构建：`./gradlew.bat :app:assembleDebug`（JAVA_HOME=C:\Users\23123\.jdks\jbr-21.0.11、ANDROID_HOME=…\Android\Sdk）
  → 拷 `E:\搬家文件夹\` → 提交 `fixNN: 中文摘要` → present_files。
  ★ 每次 fix 必改 `app/build.gradle.kts`：versionCode=fix 序号、versionName=1.0.<fix号>。
  APK 名 `memory-freeform_<versionName>.apk`（debug 带 `-debug`）。
- 签名：根 `keystore.properties` → `E:\project\apk-keys\app.jks`(alias xjwkey)，debug/release 同签名；文件已 gitignore。
- SDK 实际在 `%LOCALAPPDATA%\Android\Sdk`（build-tools 35.0.0，无 36）。
- 工具链：Gradle 8.14.3 / AGP 8.13.2 / Kotlin 2.3.20+compose / compileSdk 36 / compose BOM 2026.03.01 /
  miuix 0.8.8+icons。lifecycle 2.11 别用（要 SDK37+AGP9.1）。miuix 0.8 断点：TextField 收 TextFieldValue、
  Slider material 签名、SuperArrow 用 endActions、图标 MiuixIcons.Back(icon.extended)。
- Gradle daemon 卡住 ⇒ `./gradlew.bat --stop`；dexBuilderDebug AccessDeniedException ⇒
  删 `app/build/intermediates/project_dex_archive`。
- ⚠ `git rm` 会删整个工作树 ⇒ 用 `rm -f` + `git commit -a`；Edit 可能报成功未落盘 ⇒ grep 复验；
  Bash PATH 破损 ⇒ 前导 `export PATH="/c/Windows/System32:/c/Windows:/usr/bin:/bin:$PATH"`；
  本仓库 Windows 版 find 不可用（报"找不到文件"），用 Glob 工具代替。
- 真机 Xiaomi 2304FPN6DC / Android 16 / HyperOS 3.0 / 逻辑屏 1080x2400 / density 2.625。
  hook 在 system_server ⇒ **装后必重启**（纯 App UI 改动 force-stop 即可）。总闸 `/data/system/memoryfreeform_hook.off`。
  排查前先看 `/data/system/memoryfreeform_hook.active` 是否存在（不存在 = system_server 钩子没进作用域，优先怀疑 LSPosed 勾掉了"系统框架"）。
- ⚠ 绝不用 `echo >` 覆盖 `memoryfreeform_window_memory`；注入 motionevent 的 DOWN 必须配 UP。
- ⚠ RootManager.execAsync 尾部会追加 `>/dev/null`，命令里自己的 `> 文件` 会被顶掉 ⇒ **写文件用 `| tee 文件`**。
- ★ 删方法铁律：先 grep 全仓确认零调用方再删；`tools/` 不参与编译。

## 核心机制（真机定论，勿推翻）
- ★★★ `Task.getBounds()`=逻辑屏坐标；图层缩放补偿 `WindowSizing.MIUI_LAYER_SCALE`=**0.70**（下发尺寸÷0.70）。
  fix105 曾改 1.0、fix109 已平反 —— 探针 getScale() 不可信，别再推翻。
- ★★★ 清记忆断根三步：删记忆文件 → `am stack remove` 清残留 task → 再删文件+重启。
  不清则钩子把坏值写回；fullscreen 残留会让 `--windowingMode 5` 复用不进 freeform。
- ★★★ 关小窗 ≠ remove Task；唯一存活判据 `getWindowingMode()`(5=freeform)。
- ★★★ 写入来源白名单：只 `onMovedByResize`（触摸拖）+ `Task.resize`（我们 am resize）可信。
  onResize/setBounds 只刷路由；resize 站点只登记活窗，**不能删**。
- fix80：RESIZE 帧不改记忆 left/top（>8px 判无效只采宽高）。fix91 尺寸写回四闸（非失焦角+有基准+差>8px+isTouched 2s）。
  fix77 doWrite 三闸：荒谬几何 / 退出动效全屏帧拒 / 超屏 clamp。
- fix78i 手势条：DOWN 快照；UP 后 +900ms/+2.5s 采样；BAR_PENDING_TIMEOUT 6s。Proxy 必须答 equals/hashCode。
- fix89/90/92：退出 freeform 无条件 cancelPending；IDLE_DEBOUNCE 500ms；onRemove 无手势+5s 无触摸不写；出生安静期。
- fix93 切角=`WindowMemory.mirrorAllHorizontally`（D=屏幕边÷scale），**绝不清记忆**；镜像落盘后才准 reapplyHot。
  fix86 仅横屏顶部让位（topAvoidPx，App+hook 都要）。
- 记忆按方向分键（`pkg=`/`pkg@L=`），SP 与系统文件共用 HookContract.memoryKey；记忆优先于设置；
  改尺寸类设置两份都清 = `WindowMemory.clearAll()`+`wipeSystemWindowMemory()`。
- 图层：浮层 APPLICATION_OVERLAY(111000) 恒在小窗(21000) 之上；overlay 必带 FLAG_NOT_TOUCH_MODAL；无 bringToFront，z 序=add 顺序。
- 架构：只 `am start-activity --windowingMode 5`；屏上已有小窗绝不能再 start → adoptExisting。
- 1.0.124 默认尺寸学习链路真机通过（`/data/system/memoryfreeform_default_rect`）。
  1.0.122 起无「单击图标开小窗」；1.0.112 launchApp 是纯系统调用。
- 1.0.128「记住小窗大小」开关经 root 写 `/data/system/memoryfreeform_flags`(rememberSize)；
  ★ 1.0.142 起**默认关**（AppState/prefs 默认 false，hook 文件缺失时兜底也翻成"关"）。
- 1.0.130 悬浮球坐标域：overlay 布局 y 原点在状态栏下方，触摸 rawX/rawY 是绝对坐标；球径 60dp。
- 1.0.123：`SingleHandManager` **不能删**，活跃入口在 FloatingBallService / CornerWindowService / MainActivity。

## ★ 小窗圆角（fix140~fix163，结论见 fix163）
- 圆角 = SurfaceFlinger 图层属性 `roundedCorner`；小窗稳定值 **67.1429 = 47/0.70**（47px=18dp）。
  `Miui Caption` 标题栏是 47（scale 1.0 层的真值）。补间由 SystemUI folme 每帧写 `Transaction.setCornerRadius(leash, r/scale)`。
- ★ fix144 真因：直角是**我们自己的 BirthHook**造成的——它让窗口"出生即最终尺寸"，尺寸不动、只有圆角 0→67 才露直角；
  不 hook 时窗口同时在缩放，圆角同步长出 = 自然变形，看不出来。
- ★★ fix163（现行）：`hook/FreeformCornerHook`(SystemUI)、`FreeformCornerKeeperHook`(system_server)、
  `CornerTraceHook` **全部删除**。A/B 实测"开窗收尾闪 2 帧直角"在**钩子全关时同样存在** = 原版澎湃行为，改半径值治不了。
  作用域随之退回 `android`（HookEntry 注释已写明；`res/values/arrays.xml` 的 xposed_scope 只剩 android）。
  ⚠ 但用户反馈当前 1.0.163 **仍然会出直角** —— 直角路径分两段：入口首帧（BirthHook 暴露）、收尾末尾 reset 帧（原版行为）。
  **待办：重新取证定段，再决定是钉圆角还是改 BirthHook 的出生时序。**
- 历史坑（回归时别重犯）：folme 弹簧**会过冲**（峰值 70.88 / 68.83），`stable=max()` 会把过冲值当真值 ⇒ 圆角偏大；
  必须锁设计值 67.1429 且只认 [30,120]；`setCornerRadii` 有 **4 个半径参数必须全钳**（fix149，否则 splash 第二对角露方角）；
  `raw<=0` 帧要全钳；`stable` 需连续 3 帧同值才转正（fix150）。
  兜底 `FreeformCornerKeeperHook` 只能写 1~2 帧就 `mNativeObject is null`（leash 已 release），收益不划算。
- ★ 感知规律：**暗色内容才看得出方角**（四角与背后内容对比度决定）。
- ★ LSPosed 作用域必须用 resource 数组形式才会预勾：`@array/xposed_scope`（同类问题 fix143 翻过车）。
  手工 INSERT db 那条路会让**整机 LSPosed 失效**（fix145 血泪）；用户自己在 UI 里勾是安全的。
  排查：`cat /data/adb/lspd/log/modules_*.log | grep "installed in"`；模块日志每 20s 刷一次。
- ⚠ SystemUI 写不进 `/data/system`（EACCES），自检文件常是 system_server 留下的旧值；只有**冷开**
  （先 `am stack remove` 掉已有 freeform task）才走入场动画。
- 取证：`adb shell dumpsys SurfaceFlinger | awk '/Layer \[/{n=$0} /roundedCorner/{print n" => "$0}'`；
  脚本在 `tools/`（dev_corner_probe.sh 逐帧采样、dev_repro_cycle.sh 无人值守多轮复现、dev_open_video.sh 录屏打点）。
  ⚠ 侧边栏开小窗会先走 system_server 的 `Splash Screen`/`SnapshotStartingWindow` 快照层，SystemUI 钳制覆盖不到它的 reset 帧。

## 近期版本要点（更早看 git 历史）
- 1.0.163：删除圆角三件套（见上）；`tools/` 加三个取证脚本。
- 1.0.142：悬浮球默认关 +「获取应用列表」权限引导（`core/AppListPermission.kt`，判定=checkSelfPermission **且** 可见包数≥10；
  无标准运行时弹框，只能送 `miui.intent.action.APP_PERM_EDITOR` 授权页）+「记住小窗大小」默认关。
- 1.0.141：悬浮球默认关（AppState+prefs）。
- 1.0.131：关闭小窗竞态修复；菜单删「切换角落」；最近任务面板 -50dp、行横滑 ≥72dp 关窗。
- 1.0.129/126：MenuRoot 手势回归（DOWN 不落球上一律 super.dispatchTouchEvent；扇形半径 47dp）。
- 1.0.125：BirthHook clamp 与 RecordHook doWrite 同口径。

## 工作流纪律
- ★★★ **改完功能绝不立刻提交**：先构建 + 真机验证通过（含预期行为回归）后才可 commit/push。
  验证未过 → 反复改到过为止，期间不产生任何 git 提交。
  但**纯日志/注释类清理**不受此限（无行为变化，可随同批提交）。
- 提交摘要走 `fixNN: 中文摘要`；release 另打 `git tag v1.0.<fix号>`（仓库此前一直只有分支、没有 tag，fix163 起补上）。

## 待查
- shared_prefs 全空（设置从不落盘）疑点，未终判。
- 上游调研（HyperCeiler / MiFreeformEnhance / HyperGeoMem / FanFreeform）结论：全生态**没人处理过小窗入场圆角补间露直角**，
  这块是空白地带，别指望抄作业； HyperCeiler 几何路线与本项目的差别只是它走 ActivityOptions 通道（详见 git 历史）。
