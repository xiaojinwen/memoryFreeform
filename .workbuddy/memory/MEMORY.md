# memory-freeform 长期笔记（蒸馏版）

> 包名 = applicationId = `xiaojw.memoryFreeform`（hook 子包 `...hook`）。架构自 fix36 定轨：自造窗口全删，**只走 MIUI Freeform**。
> 细则在 `ARCH-DETAIL.md`（动悬浮球 / 出生钩子前必读）；历史调查过程在 `2026-09-2{5,6}.md`（append-only，结论以本文为准）。

## 仓库 / 构建 / 环境
- 双远端：`origin`=gitee(master)、`github`=github(main，映射 `master:main`)；**tag 不在映射里** ⇒ 推 GitHub 的 tag 按名单推：`git push github v1.0.195`。
- 构建：`./gradlew.bat :app:assembleDebug|assembleRelease`（JAVA_HOME=C:\Users\23123\.jdks\jbr-21.0.11、ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk）
  → 拷 `E:\搬家文件夹\` → 提交 `fixNN: 中文摘要` → 打 `git tag -a v1.0.<NN>` → present_files。
  ★ 每次 fix 必改 `app/build.gradle.kts`：`versionCode=fix 序号`、`versionName=1.0.<fix号>`。APK 名 `memory-freeform_<versionName>[-debug].apk`。
- 签名：根 `keystore.properties` → `E:\project\apk-keys\app.jks`(alias xjwkey)，debug/release 同签名（已 gitignore）。
- 工具链：Gradle 8.14.3 / AGP 8.13.2 / Kotlin 2.3.20+compose / compileSdk 36 / miuix 0.8.8。build-tools **35.0.0（没 36）**。lifecycle 别升 2.11（要 SDK37+AGP9.1）。
- 排障：daemon 卡住 ⇒ `--stop`；`dexBuilder* AccessDeniedException` ⇒ 删 `app/build/intermediates/project_dex_archive`。
- ★ 本机坑（Windows/Git Bash）：`git rm` 会删工作树 ⇒ 用 `rm -f` + `git commit -a`；Edit 可能报成功未落盘 ⇒ grep 复验；
  Bash 前导 `export PATH="/c/Windows/System32:/c/Windows:/usr/bin:/bin:$PATH"`；Windows 版 `find` 不可用（用 Glob）；
  `adb push` 目标路径加 `MSYS_NO_PATHCONV=1`；adb 偶发掉线（`devices` 空时日志是上一次的）。
- 真机 Xiaomi 2304FPN6DC / Android 16 / HyperOS 3.0 / 1080x2400 / density 2.625。hook 在 system_server ⇒ **装后必重启**。
  总闸 `/data/system/memoryfreeform_hook.off`；排查先看 `..._hook.active` 是否存在（不存在 = 钩子没进作用域）。
- ⚠ 绝不用 `echo >` 覆盖 `memoryfreeform_window_memory`；注入 motionevent 的 DOWN 必须配 UP。
- ⚠ `RootManager.execAsync` 尾部追加 `>/dev/null` ⇒ 命令里的 `> 文件` 会被顶掉，**写文件用 `| tee 文件`**。
- ★ 删方法铁律：先 grep 全仓确认零调用方再删；`tools/` 不参与编译。

## ★ 核心机制（真机定论，勿推翻）
- ★★★ **小窗「大小」的真身是 `freeformScale`（渲染缩放），不是 Task bounds**。拖边角 6.4 秒 Task bounds 一像素没变，
  而 SF `scale x=0.4731`（默认档 0.70）⇒ 只记 bounds 的链路永远恢复不了大小（fix169~171 全栽在这）。
  - 读活值优先级：① `Task` 上 float 字段（`mMiuiFreeformScale`…）② `MiuiFreeFormManagerServiceStub.getFreeformScale(taskId) F`
    （**小写 f，在 manager 上**）③ ActivityStack 上的 `getFreeformScale`/`getFreeFormScale`。
    ⚠ `stack.getFreeFormScale()`（大写 F、无参）本 ROM 没有，恒抛 NoSuchMethod。
  - **事件源（现役）**：hook `Task.setMiuiFreeformScale(F)V` after —— 系统改缩放必经它。
  - 写/恢复：`ActivityOptions.getActivityOptionsInjector().setFreeformScale(scale)`（配 `setLaunchBounds`）。
  - 默认档 **0.70 = `WindowSizing.MIUI_LAYER_SCALE`**（下发尺寸÷0.70）。探针 `getScale()` 返回 1.0 是**假信号**（fix105 翻车、fix109 平反）。
  - 记忆文件 `/data/system/memoryfreeform_window_scale`（`pkg=0.45` / `pkg@L=`，按方向分键）。
  - ⚠ 别照抄上游：HyperCeiler 靠 `ATMS.resizeTask` 监听缩放、用 `modifyLaunchActivityOptionIfNeed` 恢复 —— **本 ROM 都不走这两条**。
- ★★★ `Task.getBounds()` = 逻辑屏坐标。
- ★★★ **关小窗 ≠ remove Task**（澎湃是 mode→fullscreen + 隐藏）；唯一存活判据 `getWindowingMode()`==5。`isVisible`/`isFreeformTask` 都不能当判据。
- ★★★ 清记忆断根三步：删记忆文件 → `am stack remove` 清残留 task → 再删文件 + 重启。不清则钩子把坏值写回。
- ★★ 关窗动效会把 `freeformScale` 还原成 **1.0**（退出 = 缩放回 1）⇒ 缩放记录必须有上界闸 `SCALE_MAX_SANE=0.95`（合法用户值实测 0.56~0.79）。
- 记忆按方向分键（`HookContract.memoryKey`：竖 `pkg=` / 横 `pkg@L=`），SP 与系统文件共用；出生钩子只取当前方向那条，**绝不跨方向回退**。
- 记忆存**下发值**（fix52），不是视觉意图值（否则乘法螺旋，一次比一次小）。
- ★ 切角 = `WindowMemory.mirrorAllHorizontally`（D = 屏幕边 ÷ scale）；**切角绝不清记忆**，须等镜像落盘回调后才准 `reapplyHot`。
- fix86 横屏只在**顶部**让位（`topAvoidPx`），App 侧与 hook 侧**都要改**。
- ★ BirthHook 的 clamp 与 RecordHook 的 `doWrite` 必须**同口径**（`maxR = size/0.70` + 横屏 `topSafe` 地板）。
- ★ 记忆必须**事件驱动**，不能计时器采样（定时抓到的多是动画中间帧）。校验不过就什么都不写。
- 图层：浮层 `APPLICATION_OVERLAY(111000)` 恒在小窗(21000) 之上；overlay 必带 `FLAG_NOT_TOUCH_MODAL`；z 序 = add 顺序。
- 架构：只 `am start-activity --windowingMode 5`（**`am` 没有 bounds 参数**）；屏上已有小窗绝不能再 start → adoptExisting。
- 默认尺寸学习（1.0.124）：`memoryfreeform_default_rect`，仅"自启动 + 无记忆"才应用（self_launch 15s TTL）。
- 「记住小窗大小」开关（1.0.128，1.0.142 起**默认关**）：root 写 `memoryfreeform_flags` 的 `rememberSize`。改尺寸类设置需 `WindowMemory.clearAll()` + `wipeSystemWindowMemory()` 两份都清。
- 悬浮球（1.0.141 起**默认关**）：overlay 布局 y 原点在状态栏下方，触摸 rawX/rawY 是绝对坐标，球径 60dp。`SingleHandManager` 不能删。
- 「获取应用列表」权限（1.0.142）：判定 = checkSelfPermission **且** 可见包数 ≥10（澎湃做成开关，checkSelfPermission 恒 GRANTED 不可信）；只能送 `miui.intent.action.APP_PERM_EDITOR` 兜底。
- ★ `WindowWatcher` 监听**必须走独立通道 `executeWatch()`**（挤占共享 `mainQuery` 会让 `am stack remove` 被跳过 ⇒ 退化 force-stop 杀掉用户刚关的 App，fix51）。
  **记录钩子已装时 App 不许再写系统记忆文件**（双写抢文件）。

## ★ fix195（2026-10-11，现役）：记录钩子架构重写
- `MiuiFreeformRecordHook` 全量重写（2352→约 1050 行）；**fix183~194 的写-回滚 + 多闸门状态机全部作废**（downScale 快照 / dragScaled 锁存 /
  closingPkgs+TTL / 手势证据链 / ATMS.resizeTask 挂点均删）。新状态容器 6 个（旧 15 个）。
- 链路 S（大小）= 缩放事件流 debounce：唯一写盘门票是"拖动期间的事件流"（barFlags 命中，或 UP 后 1.2s 余波）；
  **无事件永不写** ⇒ 纯移动 / 冷开动画 / 关窗动效天然免疫。落盘 = 最后事件 +400ms 终值复验（<0.95 且 mode==5）。拖动中另有 150ms 实时写。
- 链路 P（位置）= UP 后单一写入时机 + **稳定确认**（当下 bounds vs UP 快照差 ≤24px 才写，否则推迟 ≤3 次）；resize 类回调只登记不写。
- 关闭 = 清 pending，**永不写**（不回滚快照）。resize 回调 not-freeform 帧不清 pending（mode 抖动由判定复验消化）。
- ★ 真机验证（2026-10-12，adb 注入触摸）：移动 `65,309→98,358` ✅；短快上划关闭**记忆不变** ✅（探针 `判定#0 mode=1 → 关闭，不写`）；
  拖角 scale `0.526→0.725` ✅ 且随后关窗的 1.0 未污染记忆（`SCALE-EXIT v=1.0 → 清缩放待写`）。
- ★ 已知观察点：多窗并排时一次触摸会给所有活窗置标记，互相"让位"靠最后一轮 UP 兜住 —— 如异常先看 `record.state.moved` 探针。

## ★ 小窗圆角（fix163 后的现状）
- 圆角 = SF 图层 `roundedCorner`；小窗稳定值 **67.1429 = 47/0.70**（47px=18dp），补间由 SystemUI folme 每帧写 `setCornerRadius(leash, r/scale)`。
- ★ fix144：**直角主要是我们自己的 BirthHook 造成的**（出生即最终尺寸 ⇒ 尺寸不动、只有圆角 0→67 补间，直角就露出来了）。
- ★★ fix163 现状：圆角三件套全删（`FreeformCornerHook`/`Keeper`/`CornerTrace`），LSPosed 作用域只剩 `android`。
  ⚠ A/B 证明"收尾闪 2 帧直角"是原版澎湃行为（钩子全关也有），改半径治不了。**待办：重新取证定段（入口首帧 vs 收尾 reset 帧）。**
  取证脚本在 `tools/`（`dev_corner_probe.sh` / `dev_repro_cycle.sh` / `dev_open_video.sh`）。
- ★ 感知规律：**暗色内容才看得出方角**；原生澎湃自己也有瞬间直角 ⇒ 只能钉住圆角，不能靠减小幅度。
- ★ LSPosed 作用域**必须用 resource 数组形式**（`@array/xposed_scope`）才预勾。⚠ 手工 INSERT db 会让**整机 LSPosed 失效**（fix145 血泪），UI 里勾才安全。
  排查：`cat /data/adb/lspd/log/modules_*.log | grep "installed in"`。
- 已删代码的历史坑（仅复活时再看，细节在 `2026-09-26.md`）：folme 弹簧**过冲**（峰值 70.88）⇒ 锁死 67.1429、只认 [30,120]、别用 `stable=max()`；`setCornerRadii` 四个半径参数必须全钳。
- ⚠ SystemUI 写不进 `/data/system`（EACCES）；只有**冷开**才走入场动画；侧边栏开小窗会先走 Splash/Snapshot 快照层，SystemUI 钳不到。

## 工作流纪律
- ★★★ **改完功能绝不立刻提交**：先构建 + 真机验证通过（含预期行为回归）才 commit/tag。例外：纯日志/注释清理可随同批提交。
- 提交摘要 `fixNN: 中文摘要`；release 另打 `git tag -a v1.0.<fix号>`（fix163 起有 tag）。tag 指向发布点，不跟后续 chore 跑。

## 待办
- **（高）小窗直角复检**：先跑 `tools/dev_corner_probe.sh` 定段。
- 调试日志清理：`FloatingBallService.kt:882` 的 `Throwable().stackTrace` 打印、`CornerWindowService.kt` 多行逐帧日志。
- shared_prefs 全空（设置从不落盘）疑点，未终判。
- 上游调研已定论（2026-09-27）：HyperCeiler / MiFreeformEnhance / HyperGeoMem / FanFreeform **全生态没人处理过"小窗入场圆角补间露直角"** —— 别重复调研。
