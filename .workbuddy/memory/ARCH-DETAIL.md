# single-hand-mode 细则（ARCH-DETAIL）

> `MEMORY.md` 的配套文件。改菜单面板 / onDestroy 生命周期 / 悬浮球之前先读这里。
> 通用铁律（图层层级、overlay 非模态、架构、尺寸、位置记忆）在 `MEMORY.md`。

## 悬浮球 / 菜单 / 面板
- 球**单击展开菜单**（不开关小窗）；菜单恒定 = **最近任务 / 应用 / 隐藏悬浮球**，无会话时多「打开小窗」
  （已有别人开的小窗则换「接管当前小窗」）。「切换到左/右侧」「打开主界面」「关闭小窗」**已删，别再加回**。
  菜单要有 `MenuRoot` 收 `ACTION_OUTSIDE` 才关得掉；「再点球收起」靠球自己 + `MENU_REOPEN_GUARD_MS(350ms)`
  （ACTION_OUTSIDE 与球 DOWN/UP 有竞争，会"关了又开"）。
- 无会话走**面板-only 会话**（不 setupControls ⇒ 不拉窗、主界面不退后台）。`dismissPanel()` 在 `exitLayer == null`
  时 `stopSelf()`；`hidePanel()` 只管摘窗口，**两者不可混用**（openPanel 换面板时调 hidePanel）。
- 面板窗口**必须可聚焦**（去 `FLAG_NOT_FOCUSABLE`，配 `NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH`）：返回键只发给聚焦
  窗口；接键靠 `PanelRoot.dispatchKeyEvent`；收起动作一律 `post`。浮层里弹输入法：点框 `requestFocus()` +
  `showSoftInput(SHOW_IMPLICIT)`，窗口加 `SOFT_INPUT_ADJUST_RESIZE`；⚠ 键盘弹起会发 `ACTION_OUTSIDE` ⇒ 判
  `isImeVisible()` 后**忽略**。搜索框 `IME_ACTION_SEARCH` 直接开第一条。
- 「应用」面板 100+ 行**必须 `ListView + BaseAdapter`**（`ScrollView` 打字整棵重建 ⇒ 卡）；行拆 `panelRowShell()` +
  `bindPanelRow()` 复用 `convertView`。图标**后台**预解码，主线程只 `setImageBitmap`。
- ★ 面板位置**只按设置算**（`settingsRect()` = 角落 + 尺寸 + `navAvoidPx()`，底边 = 屏高 − `bottomGapPx`）。
  **别用小窗实测几何**（会被拖动、也会被澎湃在失焦时重摆到 `[35,127]`）。
- fix56 **三键条已整条删除**（`setupNavBar`/`sendKey`/`syncNavBar`/`AppState.navBarEnabled`/设置开关全删净）
  —— 压在小窗上的浮层越少越好，返回交给系统。**别再加回来**。

## ★ fix54 主线程铁律（修"点球卡一下、画面卡住"）
根因都是**主线程 fork su / 解图标**：
1. `onDestroy` 不许有 root 命令（旧版同步 `captureWindowMemoryOnExit` 最坏 6.25s 而 `removeView` 在其后 ⇒ ≥5s ANR）。
   主线程只 `markViewsDetached()`+`updateEnabled(false)`+摘视图；慢活全挪后台，顺序「抓记忆 → 清 session →
   `am stack remove` → logcat → 最后 `destroyShell`」。
2. `syncWindowRect` 不许读 hook 状态（未命中缓存就 fork su 30~200ms）⇒ 移到 watcher 线程；`HookBridge` 加 TTL 缓存 +
   直读快路径（hook 写完 `setReadable(true,false)`）；直读拿不到**必须**回退 root 判定。面板列表不许主线程取图标。
3. 切角球**不重启服务**（`EXTRA_HOT_REAPPLY` 热重摆）；只有**改尺寸/缩放**仍停/起。返回键改 `execAsync`。

## 出生钩子恢复链路（`MiuiFreeFormBirthHook`）
- ★ 出生几何的**真入口**是 `TaskLaunchParamsModifier.onCalculate(9参)`（真机 `calls=16 applied=3`）；
  `updateBoundsAndScaleByOptions` **已证伪**（只走"最近任务"那条路）。fix87 精简后 BirthHook 只留两个
  真干活的挂点：`onCalculate` 与 `addLaunchFreeformActivityOptionIfNeed`（其余 6 个死点已删）。
- ★ fix63：`before()` 判 `isResumingExisting(args)`（任一 `Task`/`ActivityRecord.getTask()` 已是 freeform）就
  `skip("已存在小窗(恢复)不压几何")` —— **已存在的小窗只许 `am task resize` 定尺寸**，钩子不许再压记忆几何
  （实测 resize 到 `200,600,600,1000` 后再 start 仍是它）。
- ★ fix64：`targetFor(pkg, args)` 按 `HookContract.memoryKey(pkg, landscape)` 取**当前方向**那条（`parseMemory(raw,key)`
  只认该 key，**绝不跨方向回退**）；`landscape` 用 `displaySize(args)`（Task→DisplayContent→DisplayInfo.
  appWidth/Height，2s 缓存；兜底 `ActivityThread.systemContext`）判"放得下"，放不下返回 null。自检行 `memskip=<key>:…`。
  优先级：① birth_target（60s TTL）② 记忆文件 ③ 无 → 不干预。

## 记忆链路（`MiuiFreeformRecordHook` 系统侧 + `WindowWatcher` App 侧）
- ★ 系统侧记录钩子只认**四个可信写点**：`Task.onMovedByResize`（触摸拖动落定）/ `Task.resize`（我们 `am task resize`）/
  `setBounds`（系统重摆）/ `removeIfPossible`（关窗 final flush，立即写）。
  ① `SITES` 按**方法名**挂所有参数量（写死参数个数会全漏）；② `onResize`/`onMovedByResize` 必须
  `declaredOnly=true` —— 追父类会把 `WindowContainer`/`Task` 同名方法也钩进来（inst=2，一半 `not-task` 噪音）。
- ★ **fix79 起侧边栏双文件（`_sb` / 键 `@SB`）已废弃**：所有小窗统一写 `singlehand_window_memory`，
  键**只分横竖屏**（`pkg=` / `pkg@L=`）。迁移时 `@SB@L` → `@L`（首版漏保 `@L` 的 bug 已修）。
  沿用 fix68~70 的路由机制：`routeOf<pkg,(path,key)>` = 本次开窗生命周期内首次 `onResize` 实时算
  （`isOursSession` 读 `singlehand_session`），关窗 flush **复用该路由不复算**（App 关窗流程先 `rm -f`
  会话再 `am stack remove`，此刻重算必判成系统窗 → 写错文件 → 主文件读不回 = "记不上"）。
  窗口关掉即清 `routeOf`。★ App 开窗脚本必须**先写 SESSION 再 `am task resize`**，否则路由错。
- ★ **重摆带 / 退出动画帧一律拒写**（`doWrite` 三闸，fix77 定稿）：① 荒谬几何；②「退出动画全屏帧」
  （内容宽高都逼近 `屏幕/scale`）；③「失焦重摆带」（内容宽==屏宽 sw 且 left 悬在中间 >40 且 right < maxRight−40）。
  ★ fix69 为放行"微信大窗"把上限放宽到 `屏幕/0.70` 是**错误方向**——那条大窗值本身也是重摆带脏值。
  离屏上限必须 scale-aware（`屏幕/scale`），否则微信那种内容 1080/右边缘 1242 的大窗会被误杀不记。
- `onResize` 回调**只刷路由、不 scheduleWrite**；写盘交给 resize / onMovedByResize / setBounds / remove。
  clamp 正解（真机三帧，我们 clamp 出来的值与系统随后 settle 的几何**逐一相等**）：
  `l = clamp(left, 0, maxRight - w)`，`maxRight = 屏逻辑宽 / layerScale`（scale=1.0 时就是屏宽）。
  `onRemove` 优先 flush `onResize` 攒的最后一帧 `latest`（用户停手位置——关窗那一刻的 bounds 常是退出动画帧）。
- debounce 键必须稳定 = `pkg#O` / `pkg#S`（含方向来源）；**绝不能**用 `Task.hashCode()`（拖后立刻关窗时
  resize 的定时任务会拿旧键覆盖掉关窗那帧的正确位置）。防抖 200ms，关窗立即落最终帧。
- `pkgOfTask` 要串四道：`getPackageName()` 会返回 null（补 `realActivity`/`origActivity`/`intent.getPackage`）
  → `mTopActivity`/`topActivity` → `mChildren` 遍历。
- 写盘：`/data/system/singlehand_window_memory` 走**单线程异步**（fork su 200~300ms，别堵轮询线程）；
  SP 仍**同步**写（App 侧靠它恢复位置）。★ 记录钩子已装（`RECORD_STATE_PATH` 里 `installed>0`）时，
  App 侧 `WindowWatcher` **不许再写**该系统文件，否则双写抢文件、陈旧覆盖。
- App 侧兜底：`acceptable()` 闸门 + `lastSane` 兜底（关窗挑不出历史帧时退最近一帧 sane）；
  轮询 1000→**2000ms**；`record()` 额外拒绝左上角重摆角 `[35,127]` 族。
- ★ 清记忆 = 两份清：`WindowMemory.clearAll()`（SP）+ root `rm -f /data/system/singlehand_window_memory[_sb]`；
  改尺寸类设置（`updateDisplaySize/updateScale/updateBottomGap/switchTo`）也要 `wipeSystemWindowMemory()`。
  ★ 清两条方向行用 `grep -vE '^<pat>(@L)?='`，`pat` = `pkg.replace(".", "[.]")` —— **不能用 `Regex.escape`**
  （toybox grep 不认 `\Q\E`）。
- 横竖屏：`HookContract.memoryKey(pkg, landscape)`（竖=包名，横=`包名@L`），SP 与系统文件共用；
  出生钩子 `targetFor` 只取**当前方向**那条，放不下就返回 null（`memskip=`），**绝不跨方向回退**。
  方向判据 `displaySize(args)`（Task→DisplayContent→DisplayInfo.appWidth/Height，2s 缓存）。
  ★ 真机测方向前先 `settings put system accelerometer_rotation 0` + `user_rotation 0/1`
  —— 设备被物理横放会静默变横屏。
- ★ fix87 精简后 RecordHook 只挂 4 个点：`onMovedByResize` / `onResize` / `setBounds` / `removeIfPossible`
  （旧挂点 `Task.resize` 之外的 `Task.remove`、`installGestureClassProbe`、`FreeformScaleProbeHook` 已删；
  BirthHook 出生挂点 8→2）。⚠ `setBounds` **不能删**（calls=186，是首次拖动 DOWN 的注册机会）。
- ★ fix52 起记忆改存**下发值**（不是视觉意图值）：存"视觉意图再反推下调"会形成乘法螺旋
  （清记忆后一次比一次小），猜错一次就缩一圈。
- ★ fix51 踩坑：监听轮询挤占 RootManager 共享 `mainQuery` 通道 ⇒ `am stack remove` 被跳过 ⇒
  退化成 force-stop ⇒ **把用户刚关掉小窗的 App 进程一起杀掉**。故监听必须有独立通道 `executeWatch()`。
- ★ fix88 圆角闪现根因：圆角长在 **Activity 图层**（`dumpsys SurfaceFlinger` 实测 `viewCornerRadii=67.14`，
  Task 图层是 0）。真机写 `birth_target` 后 `am start-activity --windowingMode 5` 得到的 bounds
  **精确等于目标** ⇒ 出生钩子已把几何设对，App 侧紧接着那次 resize 是**重复的**，系统为它再走一遍
  relayout、重排那帧圆角归零 = 四角直角闪一下 ⇒ 修法是「几何一致就跳过 resize」。

## 手势条拖动 = 移动 / 关闭的判定链（`bar` 系列）
拖底部手势条**关窗**、拖顶部拖动条**移动**——两种都走 `onMovedByResize`，只能靠下面的链区分：
- **监听注册**（真机排障三次才定下来）：Android 16 **删了 `android.view.PointerEventListener`**，
  接口**搬家**到 `android.view.WindowManagerPolicyConstants$PointerEventListener`；
  `WindowManagerInternal`（LocalService）上也**没有**注册 API，只有 **WMS 本体**有
  `registerPointerEventListener(PointerEventListener, int)`。目标对象顺序 = LocalService →
  `this$0`(WMS) → `Task.mAtmService.mWindowManager`，逐个搜「首参 `simpleName=="PointerEventListener"` 的
  方法」按**真实参数类型** Proxy 后注册（兜底 `Task` 回调里重试，最多 300 次）。
  ★ **Proxy handler 必须正确应答 `equals`/`hashCode`/`toString`**：`PointerEventDispatcher` 会
  `mListeners.contains(listener)`，ArrayList 走 proxy 的 `equals` 返回 null ⇒ boolean 拆箱 NPE。
  `plisten=0` = 没注册上（首要怀疑点）；`down` 不涨同理。
- **坐标系坑**：pointer 事件坐标 ≠ `Task.getBounds()` 空间（`TaskSnapshot.mFreeformScale=0.7`、锚点不明）⇒
  **别拿条带/几何去判定 DOWN**（已废弃），改成「任何 `ACTION_DOWN` + 存在活 freeform 小窗（freeformSeen）」
  ⇒ 快照 = `boundsOf(task)`（putIfAbsent 防双指），正确性靠"拖动必以自己的 DOWN 开头，快照必先于拖动帧"。
- **判定与落盘**：拖动帧在标记期内只进 `latest` 缓冲不落盘；UP 后两段采样
  （+900ms/+2.5s，fix80 改 +700ms 且关闭当场写）看 **`Task.getWindowingMode()`**（5=freeform）：
  #0 活 → 移动，flush 终帧；#0/#1 都不活 → 关闭，写 DOWN 快照覆盖（跟指帧全弃）；#1 又活 = 关闭后重开 → **不写**。
  ★ `isVisible` / `isFreeformTask` **都不能当存活判据**（淡出/切走后置 false 但窗活着，fix78h 因此零写入）
  —— 唯一可靠的是 remove 事件本身 / getWindowingMode。
- ★ **澎湃关小窗不 remove Task**：是 `mode→fullscreen + 隐藏`（dumpsys 实锤 `visible=false mode=fullscreen`），
  所以"靠 remove 触发自愈"永不执行 ⇒ 必须靠 `getWindowingMode()` 采样。
- 事件顺序：`Task.onMovedByResize(0)` / `onResize(0)` 是拖动落定回调；`setBounds` 剩下的唯一作用是
  「系统摆默认几何」的垃圾来源 ⇒ 只刷路由、不写盘。

## 监听线程被冻结（"监听没日志了"先查这个）
- App 被 stop 后 `oom_score_adj=701`、线程 `wchan=do_freezer_trap` ⇒ `WindowWatcher` 一起停 ⇒ `/data/system/…` 不再
  更新 ⇒ 钩子一直压旧几何。**不是代码 bug**，重开主界面即恢复。

## 取证速查（真机 / adb，从旧日志沉淀下来的）
- ★ 读小窗几何的三条命令（`am stack list` 里 fullscreen 一堆，**别只看 head -1**）：
  `am stack list | grep -B1 mWindowingMode=freeform | grep RootTask`；
  `dumpsys window windows | grep -A22 "Window #.*<pkg>" | grep -E "Frames:"`；
  最终合成矩形看 SurfaceFlinger `+ Layer …(<component>#N)` 的 `displayFrame` vs `sourceCrop`
  （`displayFrame` 给"屏内可见区"，`sourceCrop` 才是实际裁剪；resize 后至少等 6s，
  `grep -c "transition snapshot" > 0` = 动画未结束）。
- ★ `am` 全文只有 `--windowingMode / --activityType / --display`，**没有 bounds 参数** ⇒ 只能"先启动默认几何、再 resize"。
- ★ 识别"系统关闭小窗"：`am stack list` 里 **`mWindowingMode=freeform` 的 RootTask** 中还有没有我们的包。
  关闭 / 转全屏 / 回收都会消失，挂起时仍在（不会误判）。
- ★ MIUI 的整套出生几何都挂在 `isDesktopActive`（"桌面模式"）开关后面
  （`MiuiFreeformServiceImpl.addLaunchFreeformActivityOptionIfNeed`、`MiuiDesktopModeLaunchParamsModifier.calculate`
  都被它挡）。本机桌面模式未激活 ⇒ 出生几何落到 AOSP `LaunchParamsController` 默认结果 ——
  这就是"hook 装上却 calls=0"的根因。
- 反编译：`/system_ext/framework/miui-services.jar` 里的 dex 是**压缩的**，直接 `grep -a` 搜不到，
  先解压再搜；命中 `MiuiFreeFormManagerService / MiuiFreeformServiceImpl / MiuiFreeformGestureController` 等
  （jadx 在 `.workbuddy/tmp/rom/`，.workbuddy/tmp 不进提交）。
- ★ **证据不足时先装探针，别反复改代码重启**（`singlehand_record.state(.moved)`，环形 48 行）：
  每帧记 `ts bounds corner vis anim top | Task.toString()`，remove/落盘时打 `=== REMOVE/WRITE ===`。
  一次就够给出三条结论，比"改一次重启三次"便宜得多。
- ★ 记忆**必须由事件触发，不能由计时器触发**：5s 定时采样抓到的多是动画中间帧
  （全屏放大帧 / 负坐标帧 / 失焦后澎湃重摆的 `[35,127]`）。校验不过就**什么都不写**
  —— 用垃圾值覆盖比不更新更糟。
- ★ **注入触摸验证手势链必须 `DOWN → UP` 成对**：单独 DOWN 会让 `barFlags` 悬挂 ⇒ 关窗走
  `REMOVE bar-close` 时用**按下时刻的旧大小**覆盖新大小（会把拉伸结果误判成"没记住"）。
  干净顺序：`DOWN → UP → am task resize → 等 5s 让 barRecent 过期 → am stack remove`。
  注入能被 WMS 的 `PointerEventListener` 收到（`bar(down=)` 计数会涨）；但**注入能关窗、
  探针却常无 `BAR-DOWN`** ⇒ 真判手势链路必须让用户手动操作，不能只信注入。
- ★ `uiautomator dump` 出来的 UI 坐标 **== `getBounds()` 逻辑坐标**（0.70 只是图层渲染缩放，
  触摸/布局坐标与 bounds 一致）⇒ 定位手势条别乘 0.7。
- ★ 复现"手势关窗把位置记错"的模板：备份记忆 → 删该包记忆行 → 开窗（**不动**）→ 手动手势关闭
  → 对比记忆是否被写。
- ★ 构建完先查 `adb devices`：设备掉线（`adb devices` 空、无线调试没开）时 APK 只构建**没装机**，
  日志全是上一次的 ⇒ 白改半天。装机走 ASCII 路径 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- adb over Git Bash：`adb push` 目标路径要带 **`MSYS_NO_PATHCONV=1`**；`/tmp` 映射不稳，
  脚本一律先写 `$TEMP` 再 `cygpath -w` 推送；`su` 复杂命令 push 脚本执行最稳。
  清记忆时 `rm` 报 SELinux deny（cp+rm 组合命令）⇒ 单独 `su -c "rm -f"`。
- 环境：bash 的 coreutils 可能整批消失（`grep/ls/sed` 全 command not found）⇒
  前导 `export PATH="/c/Windows/System32:/c/Windows:/usr/bin:/bin:$PATH"`，或者改用 PowerShell
  （传 adb 命令时**别在内层用双引号**，会被吃掉）。
- **日志落盘（`core/SHLog.kt`）**：app / root `app_process` / daemon 三进程同写一份
  `/sdcard/Android/data/xiaojw.memoryFreeform/files/logs/singlehand.log`（`getExternalFilesDir("logs")`），
  root 兜底 `/data/local/tmp/singlehand.log`，超 2MB 轮转 `.1`。行格式 `MM-dd HH:mm:ss.SSS I [pid] TAG: msg`
  —— **pid 必须有**（多进程混写靠它区分来源）；★ root 建的文件属主 uid=0，app 就写不进去 ⇒
  init 时若 `myUid()==0` 要 `chmod 666`；所有写异常必须吞掉，绝不影响触摸主流程。
- ★ 编辑铁律：同一文件同一条消息里并发两个 Edit 会**静默丢一个**（两边都报 success）⇒ 同文件的编辑串行；
  改完 grep 复验字段是否真在文件里。

## ★ 清记忆断根（清完还会长回来的"坏值循环"）
- ★ 系统 task **跨重启持久化且极顽固**：清掉记忆文件后一重启，钩子就把持久化的 freeform
  RootTask 边界经出生恢复链路写回（实测酷安 `162,261,1242,1989` 复活）。语义正确时这是**自愈**，
  语义错时就是坏值循环。
- ★ **清记忆标准流程三步**（缺一步就白清）：
  ① `rm -f /data/system/memoryfreeform_window_memory`（记得先 `.bak`）
  ② **`am stack remove` 清掉目标包残留的 freeform / fullscreen task**（旧 task 还在就一直复写）
  ③ 再来一次 ①，重启手机，观察 ≥15s 未被复写。
- ★ **必须先清残留 task 再开窗**：残留 **fullscreen** task 存在时，`am start-activity --windowingMode 5`
  会**复用那个 fullscreen task 而不进 freeform** ⇒ resize 拉锯"系统把几何改回 [0,0]"。
  诊断"怪异尺寸/比例不对"时，第一步就是清目标包的 fullscreen 残留。
- 典型循环链路：坏记忆 → 开窗被 clamp 收边成全屏宽贴顶 → 关窗"存的就是下发值"把坏值**固化**
  ⇒ 每次都从坏值起算。断根三步实测有效（高德 `freeform bounds=[178,801][826,2241]` 恢复）。
- ★ `MIUI_LAYER_SCALE` **现在就是 0.70**（代码常量 `WindowSizing.MIUI_LAYER_SCALE`）。
  曾踩过的反复：fix105 听探针 `mgr.getScale()=1.0000` 改成 1.0 ⇒ **fix109 已平反改回 0.70**
  —— 探针读的不是渲染缩放（渲染在 SurfaceFlinger 图层），真机实测 bounds 1080x1728 视觉 756x1210
  才是对的。★ 别再照着"1.0.105 起不补偿"改代码或清记忆（旧记忆里可能有 1.0 语义的值）。

## 杂项铁律
- 已证伪别回头：Display 重定向、hook ActivityStarter 注入 ActivityOptions、hook setBounds / 改 mFrame（任何
  performLayout 都覆盖回去）。要"出生即小窗"只能让 AMS 按 Freeform mode 启动。
- `am` CLI **没有 bounds 参数**；"任务在哪块屏"绝不能信 `getTasks().displayId`（本机恒 0）。
- `RootManager`：`execute`(fork su) / `executeFast(cmd,timeout)`(常驻查询，串行、忙则跳过) / `execAsync`(只写不等) /
  `executeWatch`(监听专用) / `destroyShell`。
- 读 app 图层用 `+ Layer …(<component>#N)` 的 `displayFrame` vs `sourceCrop`；resize 后至少等 6s；
  `grep -c "transition snapshot"` > 0 = 动画未结束。别随手 `input keyevent HOME`。
- runCatching 别把「读旧值」和「写新值」包一块。Kotlin 不嵌裸双引号 → 用「」或 `\"`；拼 shell 时 `$` 写 `${'$'}`，
  sed `\1` 写 `\\1`。`ic_float_ball` **不能删**（launcher 的 foreground）。
- 三颗球统一 `ui/BallView` Canvas 自绘（圆盘半径 `0.395×边长`，外面 0.105 留给投影）；亮/灰由
  `StateManager.windowAliveFlow` 驱动（daemon 线程 `runBlocking{collect}` + `mainHandler.post`），**别写"动作后延迟
  400ms 刷状态"那种猜时机的补丁**。
- `windowBackground` **绝不要刷黑**（首页↔设置页 `AnimatedContent` 淡出比淡入早 60ms，露出的就是窗底）。
- MainActivity **不再 `moveTaskToBack`**（小窗被澎湃抬成顶层 task，本页自然落到后面，留着当"选应用"载体）。
- fix57 设置页：`SwitchItem` 已删（「能连续取值的东西不要做成开关」）→ `PxInputItem`（数字输入 + 保存）；
  顶栏只有左箭头＝返回，**不要"前进"**。
- fix64 分工：`record()` 只**拒绝**屏外几何（不夹），`clampRect` 只在下发侧用 ⇒ 记忆里永远是"屏幕内"的几何。
  改这两处要**一起**改（拒绝太严会丢记忆、夹太松会记下屏外值）。
- 临时诊断文件写 `.workbuddy/tmp/`（`sh.log` / `win.txt` / `shot*.png`），**不进提交**。
- ★ 判断"仓库里有没有 native 代码"**不能只看源文件后缀和 gradle**：曾因 `.cpp/CMakeLists` 搜不到 +
  无 `externalNativeBuild` 就下结论，漏了根目录的 shell 构建脚本（`build-nas.sh` 才是唯一载体）。
  **必须一并查根目录的 `*.sh` 构建脚本。**（两个 NAS 通道已删，现结论：确实无任何 native 代码。）
- 占体积大户（都未入库）：`tools/` ~356MB（jadx.zip + services.jar + 反编译产物）、`.workbuddy/tmp/` ~352MB。
