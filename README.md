# 记忆小窗 / Memory Freeform

`xiaojw.memoryFreeform` · 当前 1.0.122

把任意应用**开成系统小窗**，并记住每个应用的小窗位置与大小：拖过一次之后，下次打开就落在老地方。

它不自建虚拟屏 —— 复用系统自带的 MIUI / HyperOS 小窗（Freeform，`windowingMode=5`），
只在旁边做「记忆」与「尺寸纠正」，所以小窗内的触摸、导航、输入法都是系统原生的。

## 实现原理

```
 am start-activity --windowingMode 5 <组件>
        │
        ├─ LSPosed hook（system_server）
        │     · 出生即目标几何：小窗建出来就是记忆里的位置与大小
        │     · 中和澎湃给 freeform 套的 0.70 图层渲染缩放
        │     · 位置/尺寸记忆落盘于 /data/system/memoryfreeform_window_memory
        │
        └─ App 侧（浮层 + shell 命令）
              · 悬浮球与菜单面板（应用浮层， APPLICATION_OVERLAY）
              · 记忆读写、清记忆、日志导出（全部走 su）
```

- **开窗**：`CornerWindowService.launchApp()` 只发一条 `am start-activity --windowingMode 5`。
  屏幕上已有小窗时不再另开（会抢同一套装饰），改为 `adoptExisting()` 直接接管。
- **几何**：`am` 没参数能指定 bounds，所以窗口出生后再 `am task resize` 到记忆里的宽高
  （仅 `reapplyHot()` 切角热重摆与 `adoptExisting` 接管两条路径）。
- **记忆**：`WindowMemory` 按应用分键、按横竖屏分键（`pkg=` / `pkg@L=`），
  只认"用户触摸拖动过"的来源（`onMovedByResize` / `Task.resize`），系统自己重摆的帧不写入。
- **浮层**：悬浮球与面板是 `APPLICATION_OVERLAY` 类型的窗口，恒在小窗（21000）之上，
  带 `FLAG_NOT_TOUCH_MODAL`，不会抢走小窗内部的触摸。

## 环境要求

- Android 8.0+（minSdk 26，targetSdk 34，compileSdk 36）
- 小米 / 澎湃 MIUI · HyperOS 系 ROM，且**系统自带小窗功能可用**
- 已 **root**：清记忆、日志导出、窗口管理都依赖 `su`
- **LSPosed**：模块作用域设为「系统框架」，`xposedminversion` 93
- 「显示在其他应用上层」权限（悬浮窗）

> 装好模块后**必须重启手机**（hook 在 `system_server` 里，重启后才生效）；
> 纯 App 侧改动 force-stop 即可生效。

## 使用

**主界面（应用列表）**

- 搜索框筛选应用
- **长按应用图标** → 清空这个应用记住的小窗位置与大小
- 长按「全部位置记忆」格子 → 清空全部记忆
- 右上角：刷新列表 / 进设置

**悬浮球**

- 单击 → 展开菜单；再单击 → 收起
- 长按 380ms（有震动反馈）后可拖动，贴到屏幕左 / 右边缘
- 开启「悬浮球滑动选择」后，菜单开着时按住球滑动到目标项，松手即触发

**菜单面板**（贴悬浮球所在一侧展开）

- **最近任务**：`am stack list` 读出最近用过的应用，点一下切过去；没有标题栏与关闭按钮，
  按一次「最近」、点面板外空白或列表上方留白即可收起
- **应用**：全部可启动应用 + 顶部搜索框。因为搜索框要收输入法，面板打开期间会把窗口
  临时切成可聚焦，关闭时立刻还原

**设置页**

| 开关 / 条目 | 说明 |
| --- | --- |
| 显示悬浮球 | 关掉后主界面改由悬浮球开关控制启停 |
| 悬浮球滑动选择菜单 | 开：按住球滑到菜单项松手触发；关：点一下展开菜单再点选 |
| 记住小窗位置和大小 | 开：开窗几何只认记忆（默认开）；关：每次都让系统摆默认位置和大小 |
| Root 授权状态 | 点一下检测 `su` |
| LSPosed 模块状态 | 读 hook 自检文件，判断是否已激活（读不到 = 没勾选 / 没重启） |
| 诊断日志 | 导出 / 分享 / 清空运行日志 |

导出的日志在 `/sdcard/Download/memoryfreeform_log.txt`，含 app 日志、hook 自检文件、
当前小窗几何与最近的 logcat 片段。

## 已知限制

- **hook 生效范围在 system_server**：装完模块不重启，系统侧小窗出生几何 / 记忆落盘可能不生效
- **状态栏与通知面板不在小窗内**：SystemUI 只在主屏渲染
- 小窗行为受 ROM 小窗实现限制，部分应用（浮层类、游戏）可能不进小窗或行为异常

## 模块

| 模块 | 职责 |
| --- | --- |
| `MainActivity` | Compose UI（miuix）：应用列表、长按清记忆、设置页 |
| `core/SingleHandManager` | 协调器：enable / disable / 清记忆 / 开关窗 |
| `core/StateManager` | 全局状态 + SharedPreferences 持久化 |
| `core/WindowMemory` | 小窗位置 / 大小记忆（按应用、按横竖屏分键，与系统文件同一份键） |
| `core/WindowSizing` | 目标几何与下发矩形 |
| `core/WindowWatcher` | 监听 freeform task 的出生与消失 |
| `core/HookBridge` | 与 LSPosed hook 侧通信（layer scale、模块是否激活） |
| `core/SHLog` | 带写入路径切换的日志（外置 → 私有目录 → `/data/local/tmp`） |
| `hook/HookEntry` | LSPosed 入口 |
| `hook/MiuiFreeFormBirthHook` | 小窗出生即目标几何 |
| `hook/MiuiFreeformRecordHook` | 小窗几何记录与记忆落盘 |
| `hook/HookContract` | hook 与 app 之间的文件路径 / 键名契约 |
| `service/CornerWindowService` | 会话总协调：开窗、接管、面板、菜单、记忆写入、关闭 |
| `service/FloatingBallService` | 悬浮球（单击开菜单、长按拖动、滑动选择） |
| `root/RootManager` | `su` 命令执行（普通 / 常驻 shell / 异步） |

## 构建

```bash
./gradlew.bat :app:assembleDebug      # Windows
./gradlew :app:assembleDebug          # Linux / macOS
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 需要 JDK 17、**Android SDK**（`local.properties` 指定 `sdk.dir`），构建前导出
  `JAVA_HOME`、`ANDROID_HOME`
- 依赖：Compose BOM 2026.03.01 + miuix 0.8.8，`de.robv.android.xposed:api` 是
  `compileOnly`（运行时由 LSPosed 提供，不会打进 APK）

## 声明

本项目仅供 Android 学习与研究使用。请确保在使用过程中遵守所在地区法律法规与软件使用条款。
