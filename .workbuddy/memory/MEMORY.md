# memory-freeform 长期笔记（蒸馏版）

> 包名 = applicationId = `xiaojw.memoryFreeform`（hook 子包 `xiaojw.memoryFreeform.hook`）。
> 架构自 fix36 定轨：自造窗口全删，**只走 MIUI Freeform**。
> 细则在 `ARCH-DETAIL.md`；动悬浮球 / 出生钩子前先读它。
> 历史调查过程在 `2026-09-2{5,6}.md`（append-only，结论以本文为准）。

## 仓库 / 构建 / 环境
- 双远端：`origin`=gitee(memory-freeform, 默认 master)、`github`=github(memoryFreeform, 默认 main)；
  映射 `remote.github.push refs/heads/master:refs/heads/main` ⇒ `git push origin`→gitee master，
  `git push github`→github main。**tag 不在映射里**，推 GitHub 的 tag 要按名单推：`git push github v1.0.163`。
- 构建：`./gradlew.bat :app:assembleDebug`（JAVA_HOME=C:\Users\23123\.jdks\jbr-21.0.11、ANDROID_HOME=…\Android\Sdk）
  → 拷 `E:\搬家文件夹\` → 提交 `fixNN: 中文摘要` → present_files。
  ★ 每次 fix 必改 `app/build.gradle.kts`：`versionCode=fix 序号`、`versionName=1.0.<fix号>`。
  APK 名 `memory-freeform_<versionName>.apk`（debug 带 `-debug`）。
- 签名：根 `keystore.properties` → `E:\project\apk-keys\app.jks`(alias xjwkey)，debug/release 同签名，文件已 gitignore。
- 工具链：Gradle 8.14.3 / AGP 8.13.2 / Kotlin 2.3.20+compose / compileSdk 36 / BOM 2026.03.01 / miuix 0.8.8+icons。
  lifecycle 2.11 别用（要 SDK37+AGP9.1）。miuix 0.8 断点：TextField 收 TextFieldValue、Slider material 签名、
  SuperArrow 用 endActions、图标 `MiuixIcons.Back(icon.extended)`。SDK 在 `%LOCALAPPDATA%\Android\Sdk`（build-tools **35.0.0，没有 36**）。
- 排障：Gradle daemon 卡住 ⇒ `./gradlew.bat --stop`；`dexBuilderDebug AccessDeniedException` ⇒
  删 `app/build/intermediates/project_dex_archive`。
- ★ 本机环境坑（Windows / Git Bash）：
  `git rm` 会删整个工作树 ⇒ 用 `rm -f` + `git commit -a`；Edit 可能报成功未落盘 ⇒ 改完 grep 复验；
  Bash PATH 破损 ⇒ 前导 `export PATH="/c/Windows/System32:/c/Windows:/usr/bin:/bin:$PATH"`；
  本仓库 Windows 版 `find` 不可用，用 Glob 工具代替；`adb push` 目标路径加 `MSYS_NO_PATHCONV=1`；
  adb wrapper 偶发掉线，`adb devices` 空时构建出的 APK 没装机 ⇒ 日志全是上一次的。
- 真机 Xiaomi 2304FPN6DC / Android 16 / HyperOS 3.0 / 逻辑屏 1080x2400 / density 2.625。
  hook 在 system_server ⇒ **装后必重启**（纯 App UI 改动 force-stop 即可）。总闸 `/data/system/memoryfreeform_hook.off`。
  排查前先看 `/data/system/memoryfreeform_hook.active` 是否存在（不存在 = 钩子没进作用域，优先怀疑 LSPosed 勾掉了"系统框架"）。
- ⚠ 绝不用 `echo >` 覆盖 `memoryfreeform_window_memory`；注入 motionevent 的 DOWN 必须配 UP。
- ⚠ `RootManager.execAsync` 尾部追加 `>/dev/null`，命令里自己的 `> 文件` 会被顶掉 ⇒ **写文件用 `| tee 文件`**。
- ★ 删方法铁律：先 grep 全仓确认零调用方再删；`tools/` 不参与编译。

## ★ 核心机制（真机定论，勿推翻）
- ★★★ **小窗「大小」的真身是 `freeformScale`（渲染缩放），不是 Task bounds**（2026-09-28 定论）。
  真机实证：用户按住拖小窗边角 **6.4 秒**，Task bounds 一像素没变，而 SurfaceFlinger
  `toDisplayTransform={ scale x=0.4731 }`（默认档 0.70）——拖边角改的是 task surface 的缩放。
  ⇒ **只记 bounds 的链路永远恢复不了"大小"**（fix169~fix171 全栽在这）。
  - 读活值：`Task.mAtmService` → `mMiuiFreeFormManagerService` → `getMiuiFreeFormActivityStack(mTaskId)` → `getFreeFormScale()`
  - 写/恢复：`ActivityOptions.getActivityOptionsInjector().setFreeformScale(scale)`（配 `setLaunchBounds`）
  - 默认档 **0.70 = `WindowSizing.MIUI_LAYER_SCALE`**（同一个东西的两面）；dumpsys 快照里 `mFreeformScale=0.7` 是同源铁证
  - 记忆文件 `/data/system/memoryfreeform_window_scale`（`pkg=0.45` / `pkg@L=`，按方向分键，与 bounds 同口径）
  - ⚠ 两个「别照抄上游」的点（本 ROM 已实测）：
    ① HyperCeiler 在 `ATMS.resizeTask` 里记缩放 —— **本 ROM 拖边角不走它**（只有 `am task resize` 走），
       必须在 `onMovedByResize` / `bar-drag` 帧里读（fix174）；
    ② HyperCeiler 用 `ActivityStarterInjector.modifyLaunchActivityOptionIfNeed` 恢复 —— 本 ROM 不经过，
       改在我们已验证生效的 `BirthHook.applyToOptions()` 里注入（fix172b）。
  - 上游参考代码已下载到 `.workbuddy/ref/upstream/`（HyperCeiler `StickyFloatingWindows.java`、
    MiFreeFormX `SizeAndPositionHooker.kt` / `ActivityOptionsInjector.kt` / `MiuiFreeFormActivityStack.kt`）。
    ⚠ 这推翻了 2026-09-27「上游没人处理过小窗记忆」的旧结论 —— 那次只查了**圆角**，没查**大小**。
- ★★★ `Task.getBounds()`=逻辑屏坐标；图层缩放补偿 `WindowSizing.MIUI_LAYER_SCALE`=`0.70`（下发尺寸÷0.70）。
  探针 `getScale()` 返回 1.0 是**假信号**（fix105 信它改 1.0、fix109 已平反）——别再推翻。
- ★★★ 清记忆断根三步（缺一步就白清）：删记忆文件 → `am stack remove` 清残留 task → 再删文件 + 重启。
  不清则钩子把坏值写回；**fullscreen 残留**会让 `--windowingMode 5` 复用那个 task 而不进 freeform。
- ★★★ 关小窗 ≠ remove Task（澎湃是 mode→fullscreen + 隐藏）；唯一存活判据 `getWindowingMode()`(5=freeform)。
  `isVisible` / `isFreeformTask` 都**不能**当存活判据。
- ★★★ 写入来源白名单：只 `onMovedByResize`（触摸拖）+ `Task.resize`（我们 am resize）可信；
  `setBounds` 不能删（首次拖动 DOWN 的注册机会），但只刷路由不写盘。
- 记忆按方向分键（`HookContract.memoryKey`：竖 `pkg=` / 横 `pkg@L=`），SP 与系统文件共用同一函数；
  出生钩子只取**当前方向**那条，放不下返回 null，**绝不跨方向回退**。
- 记忆存**下发值**（fix52），不是视觉意图值 —— 否则清记忆后一次比一次小（乘法螺旋）。
- ★ 切角 = `WindowMemory.mirrorAllHorizontally`（D = 屏幕边 ÷ scale，逻辑屏坐标），
  **切角绝不清记忆**；必须等镜像落盘的回调之后才准 `reapplyHot`。
- fix86 横屏只在**顶部**让位（`topAvoidPx`，App 侧和 hook 侧**都要**改，否则一边让位一边压住）。
- ★ BirthHook 的 clamp 与 RecordHook 的 `doWrite` 必须**同口径**
  （`maxR = size/0.70` + 横屏 `topSafe` 地板），改一处不改另一处就会出现"出生压对、记录拒收"。
- ★ 记忆必须**事件驱动**，不能计时器采样：定时抓到的多是动画中间帧（全屏放大 / 负坐标 / 失焦重摆 `[35,127]`）。
  校验不过就什么都不写 —— 用垃圾值覆盖比不更新更糟。
- 尺寸写回四道闸（fix77/80/91）：荒谬几何 / 退出动效全屏帧拒 / 超屏 clamp(scale-aware) /
  非失焦角 + 有基准 + 差 >8px + 2s 内有触摸。
- **手势条判定链（移动 vs 关闭 vs 重开）完整版在 `ARCH-DETAIL.md`**，本处只记结论：
  DOWN 快照 → UP 后 +900ms/+2.5s 采样 `getWindowingMode()` → 移动（flush 终帧）/ 关闭（写 DOWN 快照）/ 重开（不写）。
- 图层：浮层 `APPLICATION_OVERLAY(111000)` 恒在小窗(21000) 之上；overlay 必带 `FLAG_NOT_TOUCH_MODAL`；
  无 bringToFront，z 序 = add 顺序。
- 架构：只 `am start-activity --windowingMode 5`（**`am` 没有 bounds 参数**）；屏上已有小窗绝不能再 start → adoptExisting。
- 默认尺寸学习（1.0.124）：`memoryfreeform_default_rect`，仅"自启动 + 无记忆"才应用（self_launch 15s TTL）。
- 「记住小窗大小」开关（1.0.128）：root 写 `memoryfreeform_flags` 的 `rememberSize`；★ 1.0.142 起**默认关**。
  改尺寸类设置需 `WindowMemory.clearAll()` + `wipeSystemWindowMemory()` 两份都清。
- 悬浮球（1.0.141 起**默认关**）：坐标域 —— overlay 布局 y 原点在状态栏下方，触摸 rawX/rawY 是**绝对坐标**，球径 60dp。
  `SingleHandManager` 不能删（入口在 FloatingBallService / CornerWindowService / MainActivity）。
- 「获取应用列表」权限（1.0.142）：判定 = checkSelfPermission **且** 可见包数 ≥10（澎湃做成用户开关，checkSelfPermission 恒 GRANTED 不可信）；
  无标准运行时弹框，只能送 `miui.intent.action.APP_PERM_EDITOR` → 安全中心 → 系统应用详情页兜底。
- ★ `WindowWatcher` 监听**必须走独立通道 `executeWatch()`**：挤占共享 `mainQuery` 会让 `am stack remove` 被跳过
  ⇒ 退化成 force-stop ⇒ 把用户刚关掉小窗的 App 进程一起杀掉（fix51）。
  **记录钩子已装时 App 不许再写系统记忆文件**（双写抢文件、陈旧覆盖）。

## ★ 小窗圆角（fix163 后的现状）
- 圆角 = SurfaceFlinger 图层属性 `roundedCorner`；小窗稳定值 **67.1429 = 47/0.70**（47px = 18dp），
  补间由 SystemUI folme 每帧写 `Transaction.setCornerRadius(leash, r/scale)`。
- ★ fix144 定论：**直角主要是我们自己的 BirthHook 造成的** —— 它让窗口"出生即最终尺寸"，
  尺寸不动、只有圆角 0→67 补间，直角就露出来了；不 hook 时窗口同步缩放，圆角长出是自然变形看不出来。
- ★★ **fix163 现行状态：圆角三件套全删**（`FreeformCornerHook` / `FreeformCornerKeeperHook` / `CornerTraceHook`），
  LSPosed 作用域退回只剩 `android`（`res/values/arrays.xml` 的 `@array/xposed_scope`）。
  ⚠ 但用户反馈 1.0.163 **仍会出直角**；fix163 的 A/B 证明"收尾闪 2 帧直角"是原版澎湃行为（钩子全关也有），
  改半径值治不了。**待办：重新取证定段（入口首帧 vs 收尾 reset 帧），再决定钉圆角还是改 BirthHook 出生时序。**
  取证脚本在 `tools/`（`dev_corner_probe.sh` 逐帧采样 / `dev_repro_cycle.sh` 无人值守多轮 / `dev_open_video.sh` 录屏打点）。
- ★ 感知规律：**暗色内容才看得出方角**（四角与背后内容对比度决定）；原生澎湃自己也有一瞬间直角（关 hook 最小 14.9），
  所以只能钉住圆角，不能靠减小幅度。
- ★ LSPosed 作用域**必须用 resource 数组形式**才会预勾（`@array/xposed_scope`；字符串 value LSPosed 不解析 = 没声明，fix140 就翻在这）。
  ⚠ 手工 INSERT db 那条路会让**整机 LSPosed 失效**（fix145 血泪）；用户在 UI 里勾是安全的。
  排查：`cat /data/adb/lspd/log/modules_*.log | grep "installed in"`（每 20s 刷一次）。
  自 fix145 起 SystemUI 作用域可用；**只有"手工 INSERT db"那条路仍会导致整机失效**。
- 已删代码的历史坑（仅"决定复活这套钩子"时再看，细节在 `2026-09-26.md`）：folme 弹簧会**过冲**（峰值 70.88）
  ⇒ 锁死设计值 67.1429、只认 [30,120]、别用 `stable=max()`；`setCornerRadii` 的 **4 个半径参数必须全钳**。
- ⚠ SystemUI 写不进 `/data/system`（EACCES），自检文件常是 system_server 留下的旧值 ⇒ 计数看 LSPosed 日志。
  只有**冷开**（先 `am stack remove` 掉已有 freeform task）才走入场动画。
  ⚠ 侧边栏开小窗会先走 system_server 的 `Splash Screen` / `SnapshotStartingWindow` 快照层，SystemUI 钳制覆盖不到。

## 工作流纪律
- ★★★ **改完功能绝不立刻提交**：先构建 + 真机验证通过（含预期行为回归）后才可 commit/push。
  验证未过 → 反复改到过为止，期间不产生任何 git 提交。
  例外：**纯日志/注释类清理**无行为变化，可随同批提交。
- 提交摘要 `fixNN: 中文摘要`；release 另打 `git tag -a v1.0.<fix号>`（fix163 起有 tag，此前只有分支）。
  tag 指向发布点本身，不跟着后续 chore 提交跑。

## 待办
- **（高）小窗直角复检**：需先跑 `tools/dev_corner_probe.sh` 定段，区分入口首帧与收尾 reset 帧。
- 调试日志清理待办：`FloatingBallService.kt:882` 把 `Throwable().stackTrace` 拼进日志的调用栈打印、
  `CornerWindowService.kt` 几处多行逐帧日志。
- shared_prefs 全空（设置从不落盘）疑点，未终判。
- 上游调研已定论（2026-09-27）：HyperCeiler / MiFreeformEnhance / HyperGeoMem / FanFreeform **全生态没人处理过
  "小窗入场圆角补间露直角"** —— 别重复调研、别指望抄作业。
