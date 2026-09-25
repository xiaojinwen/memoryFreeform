# single-hand-mode 长期笔记（精简版，<3000 字符）

> 源码包名 1.0.107 起与 applicationId 一致：`xiaojw.memoryFreeform`（hook 子包 `xiaojw.memoryFreeform.hook`）；
> 旧 `com.singlehand.*` 引用已清零。

> 细则见同目录 `ARCH-DETAIL.md`（悬浮球·菜单/主线程铁律/出生钩子恢复链路/记忆链路/
> 手势条判定链/取证速查/★清记忆断根/杂项）。**动悬浮球/出生钩子/清记忆之前先读 ARCH-DETAIL。**
> ⚠ 日期型日志（`2026-09-19.md` / `2026-09-25.md`）已全部蒸馏进本文件 + ARCH-DETAIL 后删除；
> 已删功能的旧实现一律追溯 git 提交历史，不再留在记忆里。
> ⚠ 架构已于 fix36 整体改轨：自造窗口那一套全部删除（git 历史可查），现行方案见「核心机制」一节。

★ 2026-09-25 仓库迁移：git 历史已清空重建（旧 205 提交作废）。**双远端 + master 主分支**（main 已废弃删除）：
  - `origin` = `git@gitee.com:xiaojinwen/memory-freeform.git`（旧 `single-hand-mode` 弃用，默认分支 master）
  - `github` = `git@github.com:xiaojinwen/memoryFreeform.git`（默认分支**仍需用户在网页端改成 master**，
    main 因是默认分支删不掉；本机没装 gh CLI，改不了）
  ★★ **推送映射（本地单一 master）**：`git config remote.github.push refs/heads/master:refs/heads/main`。
  于是 **`git push origin` → gitee 的 master，`git push github` → github 的 main**，本地只维护一个
  master 分支，不用维护两份分支名。github 默认分支保持 main（正好自洽），gitee 默认分支是 master。
  旧 .git 备份在 `E:\project\single-hand-mode-git-backup-20260925`（可删）。
  提交照旧：`git add <文件>` + `git commit -m "fixNN: 中文摘要"` + `git push`（一次推两远端）。
  ⚠ GitHub 首次连不上报 `Host key verification failed` ⇒ `ssh-keyscan -H github.com >> ~/.ssh/known_hosts`。

## 构建 / 提交 / 环境
- `./gradlew.bat :app:assembleDebug`（JAVA_HOME=C:\Users\23123\.jdks\jbr-21.0.11、ANDROID_HOME=…\Android\Sdk）
  → 拷 `E:\搬家文件夹\singlehand-fixNN.apk` → 提交信息写 `.workbuddy/.commit-msg-NN.txt` →
  `git add <新文件>` + `git commit -a -F`（`fixNN: 中文摘要`）→ present_files → 说明看哪几行日志判定。
- ★ 1.0.102 工具链：Gradle 8.14.3（腾讯源）/ AGP 8.13.2 / Kotlin 2.3.20+compose 插件 / compileSdk 36 /
  BOM 2026.03.01 / miuix 0.8.8+miuix-icons（icons 独立构件）。lifecycle 2.11 别用（要 SDK37+AGP9.1）。
  miuix 0.8 断点：TextField 收 TextFieldValue、Slider material 签名、SuperArrow 用 endActions、
  图标 MiuixIcons.Back（包 icon.extended）。
- ★★ 每次 fix 必改 `app/build.gradle.kts`：versionCode=fix 序号、versionName=1.0.<fix号>。
- ★ 换包名 / 改 hook 入口类名后：LSPosed 里要**手动启用新包 + 勾选作用域 + 重启**，确认生效再卸旧包。
- ★ 仓库**没有任何 native 代码**（无 cpp/jni/CMakeLists，`build.gradle.kts` 无 externalNativeBuild）；
  唯一的 shell 构建通道 `build.sh` / `build-nas.sh` 已删。别再为找 `.so` 翻目录。
- Gradle daemon 卡住导致构建莫名失败（不是代码问题）⇒ 先 `./gradlew.bat --stop` 再编。
- `dexBuilderDebug` 报 `AccessDeniedException` ⇒ `rm -rf app/build/intermediates/project_dex_archive` 即过。
- ⚠ `git rm` 会删整个工作树 → 用 `rm -f` + `git commit -a`；⚠ Edit 可能报成功未落盘 → grep 复验；
  Bash PATH 破损 → 前导 `export PATH="/c/Windows/System32:/c/Windows:/usr/bin:/bin:$PATH"`。
- 真机 Xiaomi 2304FPN6DC / Android 16 / HyperOS 3.0 / 逻辑屏 1080x2400。hook 在 system_server ⇒
  **装完必重启手机**（App 侧纯 UI 改动除外：force-stop 即生效）。总闸 `/data/system/memoryfreeform_hook.off`。
- ⚠ 重启后先 `KEYCODE_WAKEUP` + 上滑解锁（Dozing 下 am start 全报 -92）；测方向先锁
  `accelerometer_rotation`；`adb install -r` 走 ASCII 路径 `app/build/outputs/apk/debug/app-debug.apk`。
- ⚠ 绝不用 `echo >` 覆盖 `memoryfreeform_window_memory`；注入 `input motionevent` 的 DOWN 必须 UP。

## 核心机制（真机定论，勿推翻）
- ★★★ `Task.getBounds()` = **逻辑屏坐标**。
- ★★★ 图层缩放补偿 = `WindowSizing.MIUI_LAYER_SCALE`，**现行值 0.70**（下发尺寸 ÷ 0.70）。
  ★ fix105 曾改成 1.0、fix109 已平反改回 —— 探针 `getScale()` 读的不是渲染缩放，别再推翻。
- ★★★ 清记忆要**断根三步**：删记忆文件 → `am stack remove` 清残留 task → 再删文件 + 重启；
  不清残留 task 的话钩子会把持久化 Task 边界写回来（坏值复活）。fullscreen 残留会让
  `--windowingMode 5` 复用它而不进 freeform。详见 ARCH-DETAIL「清记忆断根」。
- ★★★ 关小窗 ≠ remove Task（关后 Task 仍在 mode=fullscreen）。唯一存活判据
  `getWindowingMode()`（5=freeform）；isVisible/isFreeformTask/isOnTop 及 vis/anim/top 三状态位
  全都区分不出"用户拖 vs 系统重摆"——只能靠来源 + 几何特征（全屏帧/失焦角 [35,127] 族）。
- ★★★ 写入来源白名单：只 `onMovedByResize`（触摸拖）+ `Task.resize`（我们的 am resize）可信；
  `onResize`/`setBounds` skip-write 只刷路由。resize 站点只登记活窗，**绝不能删**
  （calls=0≠死点，fix87 踩坑）。
- fix80：RESIZE 帧不得改记忆 left/top（>8px 判无效只采新宽高）；有触摸时坐标照单全收。
- fix91 尺寸写回四条：非失焦角 + 有 lastSize 基准 + 差>8px + `isTouched(pkg,2s)`。开关默认开。
- fix77 doWrite 三闸按序：①荒谬几何 ②退出动效全屏帧(w>=sw-40&&h>=sh-40)拒 ③超屏 clamp。
- fix78i 手势条：DOWN 即快照；UP 后两次采样(+900ms/+2.5s)看 getWindowingMode 判移动/关闭/重开；
  BAR_PENDING_TIMEOUT(6s) > 采样和。PointerEventListener 注册在 WMS 本体，Proxy 必须答 equals/hashCode。
- fix89/90：退出 freeform 无条件 cancelPending；无触摸帧 IDLE_DEBOUNCE 500ms；onRemove 无手势+5s 无触摸不写。
- fix92 出生安静期；fix93 切角 = `WindowMemory.mirrorAllHorizontally`（D=屏幕边÷scale），**绝不清记忆**；
  镜像落盘回调后才准 reapplyHot。fix86 仅横屏顶部让位（topAvoidPx，App 四处+hook t 下限都要）。
- 记忆按方向分键（`pkg=`/`pkg@L=`），SP 与系统文件共用 HookContract.memoryKey，绝不跨方向回退；
  重开优先读钩子实时写的 WINDOW_MEMORY_PATH（200ms 生效），读不到才退 SP/设置。
- 记忆优先于设置；改尺寸类设置两份都清 = `WindowMemory.clearAll()` + `wipeSystemWindowMemory()`。
- 图层：浮层 APPLICATION_OVERLAY(111000) 恒在小窗(21000)之上、抢不过手势(251k/281k)；mBaseLayer final。
  overlay 必带 FLAG_NOT_TOUCH_MODAL；无 bringToFront，z 序=add 顺序；WRAP_CONTENT 先 measure 再摆。
- 架构只剩 MIUI Freeform：`am start-activity --windowingMode 5`；屏上已有小窗绝不能再 start →
  adoptExisting（判据 `WindowWatcher.lastTasks()`）。
- 调试利器：`memoryfreeform_record.state(.moved)` 探针、反编译 services.jar（jadx 在 .workbuddy/tmp/rom/）。

## ★ 小窗圆角（fix140，真机定论，勿推翻）
- 圆角 = **SurfaceFlinger 图层属性** `roundedCorner`（+ 父层 `drawFreeformEffect`），打在 task/leash 图层，
  父层向下传给子层；App 侧**没有任何** drawable/overlay 负责它。
- 稳定值 **67.1429 = 47 / 0.70**：47px = 18dp（`MiuiFreeFormManagerService.getMiuiFreeformCornerRadius()`
  的 `applyDip2Px(18.0f)`，mini 小窗 12dp），0.70 = 图层缩放（同 `MiuiPipImpl` / `TransitionImpl` 的 `/scale`）。
- **开窗时它被从 ~0 补间到 67.14（约 500ms ease-out）** ⇒ 用户看到的"四角闪一下直角"。
  补间在 **SystemUI（pid 7950）WMShell**：`MultiTaskingTransitionHandler → MiuiFreeformModeAnimation`
  的 folme 动画（冷开 = `startMoveToFrontAnimation` / type 13；另有 type 16 的
  `startFullScreenToFreeformAnimation` 显式写 0→R）。**这是澎湃设计的入场动画，非本项目引入。**
- 修法：`hook/FreeformCornerHook.kt` 装在 **com.android.systemui**，拦 `MultiTaskingFolmeState.addProperty`，
  让 `FOLME_RADIUS` 起点=终点（三/四参改 from；两参先 `mFolmeControl.setFolmeRadius(to)`）。
  ⚠ **必须用户在 LSPosed 里手动勾 `com.android.systemui` 作用域并重启**才生效（manifest 只声明 `android`）。
  单独开关 `memoryfreeform_corner.off`；自检 `/data/system/memoryfreeform_corner.state`。
- 取证：`dumpsys SurfaceFlinger | grep -E "Layer \[|roundedCorner"`；
  `logcat | grep -E "MiuiFreeformModeAnimation: |mMultiTaskingAnimationType"`。
  反编译资产在 `.workbuddy/tmp/rom/`（jadx `bin/`，已解 `msjar-src/` + `single/`）。

## 现状（1.0.131，2026-09-25）
- ★★ 仓库历史已由用户清空重建（463cdec "迁移至 memory-freeform 远端"），fix 编号照旧。
- ★★1.0.131（并入 463cdec）：①**关闭小窗竞态修复**——miuiTasks 靠 watcher ~2s 轮询登记，
  开窗后几秒内点「关闭小窗」task 未登记 ⇒ onDestroy 收窗循环拿空列表跑 ⇒ 小窗孤儿
  （真机日志实锤：选中应用→立刻关闭无 task removed 行）。修：onDestroy 按 exitPkg 现查
  am stack list 补收未登记 task（Regex `taskId=(\d+): pkg/`）。②菜单删「切换角落」
  （IC_SWITCH/⇄ 图标退役，EXTRA_SWITCH_CORNER 通道保留）。③最近任务面板高度 -50dp
  （应用面板不变）+ 行横滑 ≥72dp 关闭该应用小窗（closeRecentTask 现查 stack list，
  不依赖会话；bindPanelRow 必须复位 translationX 防复用串位）。④应用面板打开即
  requestIme 聚焦搜索框。真机实测：1.4s 竞态关闭成功、mInputShown=true。
- ★★1.0.130（c53a46c）：**悬浮球坐标域定论**——本机 overlay 布局 y 原点在状态栏下方，
  球/菜单容器 frame=attrs+108px（状态栏高），触摸 rawX/rawY 是绝对坐标。旧代码
  touchOnBall/命中圆用布局坐标 ⇒ 整体偏 108px：点球下半关不掉、滑动命中错位。
  修：touchOnBall/showMenu/容器钳制全改绝对坐标（getLocationOnScreen 现查），
  摆容器窗口时减回实测 offX/offY；弹出 300ms 内 OUTSIDE 免疫（addView 瞬间系统补发
  坐标不可信的 OUTSIDE 会瞬间收掉菜单，实测 58ms 即收）。球径 52→60dp（BALL_SIZE_DP）。
  ⚠ MenuRoot 调试日志（down/up/hideMenu 调用栈）仍保留，下版可清。
- ★1.0.129：fix126 MenuRoot 回归——DOWN 不落球上时直接 return 没走 super，菜单图标
  收不到 DOWN 点按全废；改为不落球一律 super.dispatchTouchEvent。同时删「隐藏悬浮球」项。
- ★1.0.129：fix126 MenuRoot 回归修复——DOWN 不落球上时直接 `return startedOnBall`(false)
  没走 super，菜单图标收不到 DOWN 点按全废；改为不落球一律 super.dispatchTouchEvent。
  同时删「隐藏悬浮球」菜单项。纯 App 侧，force-stop 即生效。
- ★1.0.128：「记住小窗位置和大小」改为「记住小窗大小」+ 让开关真正生效。旧真相：开关在
  App SP 里，system_server 出生钩子读不到 ⇒ 主开窗链路开关啥也不管（用户报"描述有问题，
  位置本来就会记"）。新语义：**位置始终按记忆恢复，开关只管宽高**（关=用学得的系统默认
  尺寸 DEFAULT_RECT，没学到退记忆宽高）。通道：App 经 root 写
  `/data/system/memoryfreeform_flags`（`rememberSize=1/0`，HookContract.FLAGS_PATH +
  rememberSizeEnabled() 300ms 缓存，缺失按开兜底）；App 启动自愈补写
  （pushRememberSizeFlag）；adopt 复核（miuiDeliveryRect）同口径对齐。钩子改动，装后重启。
- ★1.0.126（cd552f4）：悬浮球菜单三修。①滑动选择从未触发过——UP 里先 hideMenu()（清空
  menuTargets）再查表，恒 null；**先取 target 再收菜单**。②点击模式关不掉菜单——菜单容器
  窗口后 add、z 序在球之上且恒盖住球，点球事件落 MenuRoot、球触摸流收不到（旧注释"触摸流
  归球窗口"是错的，1.0.101 起就断的）；MenuRoot 现接管球区域手势（点一下=收，按住球滑=
  滑动选择，startedOnBall 防首帧滑出被丢）。③扇形半径 94→47dp；bottomMargin()=56dp 拖动/
  吸附/旋转三处统一钳底。仅 App 侧改动 force-stop 即生效。
- ★1.0.125：BirthHook 两处 clamp 与 RecordHook doWrite 同口径（膨胀包络 maxR=size/0.70 +
  横屏 topSafe 地板）——竖屏默认位置 162,261 不再被压成 x=0、横屏记忆 top=108 不再被压成 0；
  FloatingBallService 加 onConfigurationChanged（旋转时球吸左右缘 + y 收进新屏）。
  ⚠ fix125 当时漏提交，已随 fix126 一起入库（cd552f4）。
- ★1.0.124 验证定论：学习链路真机通过（拼多多系统入口开窗 → default=162,261,1242,1989，
  横屏 default@L=1709,108,2789,1728）；悬浮球开无记忆包尺寸对齐成功。
  ⚠ RootManager.execAsync 会在命令尾追加 `>/dev/null`，命令里自己的 `> 文件` 会被顶掉
  ⇒ 文件截空（fix124 自启动标记失效根因），**写文件必须用 `| tee 文件`**（真机验证过）。
- ★1.0.122：app 内「单击应用图标开小窗」已删（launch()/onLaunch 链路移除）；AppTile 仅长按清位置记忆，
  「当前前台应用」格子改名「全部位置记忆」（长按=清全部）。
- ★1.0.123：`SingleHandManager` **不能删**（fix123 已删 6 个孤儿方法后仍活着）。活跃入口清单：
  `FloatingBallService:417 enableLast()`、`MainActivity:538 disable()`、`MainActivity:525
  updateRememberWindowSize()`、`MainActivity:230/233 clearWindowMemory()`、
  `MainActivity:97 RootManagerRef.get(this)`；`MemoryFreeformApp.singleHandManager` 是共用单例。
- ★1.0.124（bc2304b）：悬浮球首开无记忆小窗尺寸对不齐系统默认。根因 = 出生钩子 targetFor 三级链
  （birth_target/记忆/sidebox）全空 ⇒ 落 AOSP 级联默认 `[286,714][794,1794]` 族。修法（方案A 学习式）：
  RecordHook 在 freeform 出生帧（首次见到的 onResize/setBounds）上、四闸（无记忆/非自启动/非全屏帧/
  非失焦角族）全过才学 → `/data/system/memoryfreeform_default_rect`（`default=`/`default@L=`，一次采样定音）；
  BirthHook 删 sidebox 步径换 DEFAULT 来源，**仅自启动+无记忆才应用**；launchApp 在 am start 前写
  `memoryfreeform_self_launch`（pkg+ts，15s TTL，判定在 HookContract.selfLaunchFresh）。
  ⚠ 横幅窗出生帧走 Task.resize（fix90 实证），那条路没学——若默认学不到先查这条。
  ⚠ 未真机验证：装后重启 → 系统入口开 fresh 包学默认 → 悬浮球开对齐；被污染包要清记忆断根。
- ★ 删方法铁律：**先 grep 全仓确认零调用方再删**，别只看 IDE 里的方法体；`tools/` 不参与编译，可放心。
- 1.0.102 升级工具链：Gradle 8.14.3（腾讯源）/ AGP 8.13.2 / Kotlin 2.3.20（compose 插件）/
  compileSdk 36 / BOM 2026.03.01 / activity 1.12.4 / lifecycle 2.10.0（2.11 要 SDK37 别用）/
  miuix 0.8.8 + miuix-icons（icons 独立构件须显式声明）。断点：TextField 只收 TextFieldValue、
  Slider=material 签名、SuperArrow 无 rightText（用 endActions）、图标 Back/Search（icon.extended）。
- 1.0.101/102 悬浮球：单击开/关菜单；长按 380ms 震动后才能拖；菜单开着按住球=滑动选择
  （menuTargets 命中圆+updateHotItem）；面板跟随球侧（EXTRA_PANEL_LEFT→panelAnchorLeft 贴边 8dp）。
- 1.0.104 删图层缩放设置 UI；1.0.111 开窗几何只认记忆；★1.0.112 launchApp 改**纯系统调用**：
  只 `am start --windowingMode 5`，无 birth_target/SESSION/resize/复核（buildMiuiInlineStart 删除），
  app 零参与窗口尺寸；task 由 onFreeformRound「看到就登记」（否则系统手势关窗后 stopSelf 不触发）；
  resizeTaskToWindow/miuiDeliveryRect 仅存于 adoptExisting 接管 + reapplyHot 切角热重摆。
  manifest 已对齐命名：MemoryFreeformApp / Theme.MemoryFreeform / memory-freeform FGS subtype。
- ★待查：shared_prefs 全空（设置从不落盘）；writeSideboxRect 已随 fix111 删除。

## 待修
- 已删但可查：三键条（fix56）、侧边栏（fix79/111）的实现细节都在 git 历史里。
- 设备 `/data/user/0/xiaojw.memoryFreeform/shared_prefs/` 曾查无（设置落盘疑点，未终判；注意包名已于 1.0.107 改版）。
