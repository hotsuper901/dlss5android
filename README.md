# MSJ GFX

Game Graphics Enhancer & FPS Booster for Android (min SDK 24).
**Creator: M.S.J**

A Jetpack Compose app that reads your phone's real thermal, CPU and memory state,
reclaims its own heap on demand, stays alive as a foreground service, and draws
a frametime/RAM HUD on top of your game.

---

## What it actually does

| Feature | Implementation |
|---|---|
| Thermal state | `BatteryManager.BATTERY_PROPERTY_TEMPERATURE` with `/sys/class/thermal/thermal_zone*` fallback |
| RAM reclaim | `Runtime.gc()` + `TRIM_MEMORY_RUNNING_CRITICAL`, reporting a real before/after of device-wide `availMem` |
| Never force-closed | Foreground service, `START_STICKY`, `FOREGROUND_SERVICE_SPECIAL_USE` |
| Battery-optimisation exemption | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — nothing granted silently; the dashboard opens the system dialog, and Doze ignores wakelocks until it is granted |
| CPU wakelock | `PARTIAL_WAKE_LOCK` held only while a tracked game is in front, released on exit/service teardown/permission loss. No timeout by design — the session bracket is explicit and a timeout would drop protection mid-match |
| Keep screen awake | `SCREEN_BRIGHT_WAKE_LOCK` session lock plus `FLAG_KEEP_SCREEN_ON` on the HUD window while a session is active — never while the HUD sits idle on the launcher |
| OEM cleaners | Opens the manufacturer autostart screen directly (MIUI, EMUI, ColorOS, Funtouch, OxygenOS, One UI, ZenUI) |
| Grant detection | Overlay, Usage access, the battery exemption and WRITE_SETTINGS are all re-read on every `onResume`. Granting one in Settings shows up the moment you come back — no force close, no toggle-twice |
| Survives reboot | `BootReceiver` restarts it *only* if you left the toggle on |
| Auto-arm on launch | `GameWatcher` reads `UsageEvents` every 1.2s, trims 4s after a game appears |
| On-screen HUD | `TYPE_APPLICATION_OVERLAY` window, finger-draggable, 500ms refresh, sampled off the main thread |
| Per-game presets | Battery / Balanced / Competitive, persisted, with a live recommendation engine |
| Thread safety | Every binder call and `/sys` read runs on `Dispatchers.Default`. The UI thread only ever renders |
| No CPU meter | Deliberate. A sandboxed app cannot read trustworthy device-wide CPU, and a number that reads 0 while the phone is at 100% is worse than none |
| Detection honesty | Without Usage access the state is `UNKNOWN_NO_PERMISSION`, never a fake "no game" |

## Build

```bash
# Android Studio Koala+, or:
./gradlew assembleDebug
```

Release signing reads four properties from `~/.gradle/gradle.properties` (never
commit them):

```properties
MSJ_STORE_FILE=/absolute/path/to/msj.keystore
MSJ_STORE_PASSWORD=...
MSJ_KEY_ALIAS=msj
MSJ_KEY_PASSWORD=...
```

Without them the release build falls back to the debug key so CI still runs.

## Permissions, and why

- `SYSTEM_ALERT_WINDOW` — draws the HUD above the game window
- `FOREGROUND_SERVICE_SPECIAL_USE` + `POST_NOTIFICATIONS` — keeps the booster running
- `PACKAGE_USAGE_STATS` — special access; the only way to see the foreground app on Android 5.1+
- `RECEIVE_BOOT_COMPLETED` — restart after reboot
- `QUERY_ALL_PACKAGES` — detect which supported game is installed
- `WAKE_LOCK` — a CPU lock held only while a tracked game is in front, and a screen lock while the keep-awake toggle is on. Both released on game exit, service teardown, or if Usage access is revoked mid-match
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — special access the user grants from the dashboard. Doze ignores app wakelocks without it, which is exactly how a "running" booster gets frozen between matches. The button opens the system dialog; there is no silent grant

Two things the keep-alive layer deliberately is not: it never holds a wakelock
at idle (the locks are bracketed by the game session), and it never claims the
exemption is required for the app to open at all. Without it the booster still
works — it is just the first thing Doze freezes when the phone is untouched.

No internet permission. Nothing is uploaded, there is no analytics SDK, and no
permission is requested that isn't in the table above.

## Why there is no injector in here

Free Fire, Free Fire MAX and PUBG Mobile ship signed ARM64 binaries with vendor
anti-cheat. A process injector has to defeat their integrity checks before it can
do anything, and every product that claims a working one is really doing something
much dumber:

- **Memory readers.** They scan for a float in the game's heap, patch it once, and
  the game re-validates the value at the next match. The "60 FPS Ultra" lasts one
  round.
- **Fake overlays.** A settings-looking window drawn on top of the real game, with
  the actual frame rate unchanged underneath.
- **Trojans.** Plenty of "GFX Tool" APKs on third-party stores are straight
  credential stealers, because the audience is desperate and young.

Accounts caught using any of it get banned in roughly three to ten minutes, and
the ban is normally a hardware-ID ban that survives reinstall and factory reset.

The HUD here is different in kind: it is a normal overlay window granted by the
user through a system settings screen. SurfaceFlinger composites it above the game.
Nothing is injected into the game process, because nothing needs to be.

Where frames actually come from, and what this app automates instead:

1. Free RAM before the match — texture residency collapses without it
2. Stay out of thermal throttle — 45C and climbing means 40fps at any quality setting
3. Stop background apps re-launching mid-round
4. Use the game's own quality menu, which already exposes everything an "enhancer"
   claims to unlock (Free Fire `Smooth`, anti-aliasing **off**; PUBG `Smooth` +
   `Anti-aliasing` off, raise render scale in developer options *before* launching)

## License

MIT. Credit **M.S.J**.
