# Memory Freeform

(记忆小窗) — launch any app as a small window and **remember where it was**.

It reuses the system MIUI / HyperOS freeform window (`windowingMode=5`) instead of creating
its own virtual display. The app only adds *window memory* and *geometry correction* around it,
so touch, navigation and IME inside the small window stay native.

## How it works

```
 am start-activity --windowingMode 5 <component>
        │
        ├─ LSPosed hook (system_server)
        │     · birth at the remembered geometry (size + position on create)
        │     · neutralize the 0.70 layer scale HyperOS applies to freeform windows
        │     · persist window memory to /data/system/memoryfreeform_window_memory
        │
        └─ App side (overlay + shell)
              · floating ball and menu panels (APPLICATION_OVERLAY)
              · memory read/write, clear, log export — all through su
```

- **Launching**: `CornerWindowService.launchApp()` issues a single
  `am start-activity --windowingMode 5`. If a small window is already on screen it does **not**
  start another one (they would fight over the same decorations); it calls `adoptExisting()`
  and takes over instead.
- **Geometry**: `am` has no bounds flag, so the window is resized with `am task resize` right
  after birth. That path survives only in `adoptExisting()` and `reapplyHot()`.
- **Memory**: `WindowMemory` keys by package **and orientation** (`pkg=` / `pkg@L=`) and only
  accepts writes that came from a real user drag (`onMovedByResize` / `Task.resize`) —
  windows the system re-arranged by itself are ignored.
- **Overlays**: the ball and panels are `APPLICATION_OVERLAY` windows, always above the small
  window (21000) and carrying `FLAG_NOT_TOUCH_MODAL`, so they never steal touches inside it.

## Requirements

- Android 8.0+ (minSdk 26, targetSdk 34, compileSdk 36)
- Xiaomi MIUI / HyperOS based ROM with a working system small-window feature
- **Rooted** — clearing memory, log export and window management all need `su`
- **LSPosed** with *System Framework* as scope (`xposedminversion` 93)
- `SYSTEM_ALERT_WINDOW` permission (floating ball)

> A reboot is required after installing the module (the hook runs in `system_server`).
> Pure app-side changes take effect on force-stop.

## Usage

**Main screen (app list)**

- Search box to filter installed apps
- **Long-press an app icon** to clear its remembered window position and size
- Long-press the *All position memory* tile to clear everything
- Top-right corner: refresh list / open settings

**Floating ball**

- Tap → open the menu, tap again → close
- Long press (with vibration, ~380 ms) to drag it to the left / right screen edge
- With *Slide to pick* enabled, hold the ball and slide onto a menu item, then release

**Menu panels** (anchored to the side of the ball)

- **Recents**: recent apps read via `am stack list`; tapping switches to one. There is no title
  bar or close button — press *Recents* again, tap outside, or tap the whitespace above the list
- **Apps**: all launchable apps with a search box. Because clearing the search box needs an IME,
  the window is temporarily made focusable while the panel is open, and restored right after

**Settings**

| Toggle / entry | Description |
| --- | --- |
| Show floating ball | Stop/start everything from the ball |
| Slide to pick in ball menu | On: hold the ball and slide onto an item to trigger it; Off: tap to open, then tap an item |
| Remember window position & size | On (default): geometry comes only from memory; Off: let the system place each window |
| Root status | Tap to check `su` |
| LSPosed module state | Reads the hook self-check file; "not active" means it is not enabled yet or the phone was not rebooted |
| Diagnostic log | Export / share / clear the runtime log |

The exported log is `/sdcard/Download/memoryfreeform_log.txt` and contains app logs, hook
self-check files, current small-window geometry and the recent logcat lines.

## Known limitations

- The hook lives in `system_server`: without a reboot, memory persistence and other hook side
  effects may be missing
- **Status bar and notification shade are not inside the small window** — SystemUI renders on
  the main screen only
- Behavior depends on the ROM's small-window implementation; some apps (overlay-based ones,
  games) may not launch or may misbehave

## Modules

| Module | Responsibility |
| --- | --- |
| `MainActivity` | Compose UI (miuix): app list, long-press to clear memory, settings |
| `core/SingleHandManager` | Coordinator: enable / disable / clear memory / open & close window |
| `core/StateManager` | Global state + SharedPreferences persistence |
| `core/WindowMemory` | Window position/size memory (per package, per orientation, same key as the system file) |
| `core/WindowSizing` | Target geometry and delivered rect |
| `core/WindowWatcher` | Watch for freeform task birth and removal |
| `core/HookBridge` | Talk to the LSPosed hook (layer scale, module activation) |
| `core/SHLog` | Logger with path fallback (external → private dir → `/data/local/tmp`) |
| `hook/HookEntry` | LSPosed entry point |
| `hook/MiuiFreeFormBirthHook` | Small window is born at the remembered geometry |
| `hook/MiuiFreeformRecordHook` | Record window geometry and persist memory |
| `hook/HookContract` | File path / key contract between hook and app |
| `service/CornerWindowService` | Session coordinator: launch, adopt, panels, menu, memory write, close |
| `service/FloatingBallService` | Floating ball (tap to open menu, long-press to drag, slide to pick) |
| `root/RootManager` | `su` execution (one-shot / persistent shell / async) |

## Build

```bash
./gradlew.bat :app:assembleDebug      # Windows
./gradlew :app:assembleDebug          # Linux / macOS
```

- Output: `app/build/outputs/apk/debug/app-debug.apk`
- Needs JDK 17, the Android SDK (via `sdk.dir` in `local.properties`) and `JAVA_HOME` /
  `ANDROID_HOME` exported before the build
- Dependencies: Compose BOM 2026.03.01 + miuix 0.8.8; `de.robv.android.xposed:api` is
  `compileOnly` and provided at runtime by LSPosed

## Disclaimer

This project is for Android study and research only. Make sure to comply with the laws and
regulations of your country as well as the terms of the software you use.
