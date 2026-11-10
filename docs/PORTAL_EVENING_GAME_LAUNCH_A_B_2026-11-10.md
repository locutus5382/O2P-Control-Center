# Portal 2026-11-10 evening: gameplay session instability and game launch A/B isolation

## Evidence from owner-provided recordings

Firmware: Odin2 Portal Base, Android 13. All O2P CPU/GPU writes remain disabled. A separate read-only PServer host authenticated with UID 0; a passive UI diagnostic incorrectly says privileged host not implemented because it only uses the direct-read probe.

Four new CSVs, UTC 2026-11-10:
- Session 1: 19:14:38–19:16:32, 30 persisted windows, 111 samples. Ends just after Eden is first identified; no CSV evidence for why.
- Session 2: 19:16:57–19:17:14, 2 windows, 9 samples. Eden foreground when recording ends.
- Session 3: 19:17:34–19:17:46, 2 windows, 7 samples. Eden foreground when recording ends. RAM estimate is only ~2.2 GiB near the end; cannot assume all terminations are triggered by 6 GiB usage.
- Session 4: 19:18:58–19:25:26, 41 windows, 182 samples. Last sample before restart 19:19:45.367 (ES-DE, estimated used RAM ~2.72 GiB). Next sample 19:20:09.367 (Eden, used RAM ~5.95 GiB), **24.0-second telemetry gap**. The lifecycle report independently confirms service process PID **15255** started 19:18:58, followed by new PID **17080** at 19:20:09 with `STICKY_NULL`/`STICKY_RESTART`/`SESSION_RESUMED`, continuing the **same** session 4. The user ended it normally at 19:25:27.
- Two exported lifecycle reports do not include an `ANDROID PROCESS EXIT HISTORY (SYSTEM-REPORTED)` section; they were likely generated from an earlier Alpha 2 APK before the process-exit-history change. Re-test with APK generated from the current branch.
- A process restart is proven; whether LMKD, a vendor game optimizer, a signal, native/Java crash or other cause is **not proven**. An estimated "system used RAM" sample is not memory pressure telemetry. No file establishes why Eden sometimes fails to launch a game, which title failed, the driver or shader cache involved, or causal involvement of O2P.
- Data shows the recorder can survive multiple minutes of Eden gameplay after restart, and the Android system is actively managing CPU clock speeds. No underclock writer exists; Quick Settings profile is only a preview.

## Potential avoidable app overhead discovered in review

`MonitorViewModel.start()` launched an Activity ViewModel coroutine that continued sampling direct Android/kernel sensors until explicitly stopped; switching from O2P to Eden does not clear this ViewModel if the UI process survives. In addition, `MainActivity` and `QuickControlActivity` launched `LaunchedEffect` 1.5s IPC polls without explicit UI visibility scoping. The **dedicated** :monitor foreground service separately collects sensors for recording.

This branch:
1. Stops **UI-only live monitor** sampling at `MainActivity` `ON_STOP`; manual live UI recording is closed, without affecting the persistent dedicated gameplay session. User must restart UI live monitor if desired on return. 
2. Binds MainActivity and QuickControlActivity status-bridge loops with `lifecycle.repeatOnLifecycle(STARTED)`; loops cancel as soon as panels leave the screen.
3. Extends Android process-exit-history reporting with OS reason description and its reported PSS/RSS; **no tuning writes, process priority manipulation, system-property changes, or shell commands**.
4. Bumps versionCode 18, `0.9.0-alpha2.1-lifecycle-guard` to make old/new APK testing distinguishable.

Whether this reduces interruptions remains to be verified on the actual device. It should not be credited as the root-cause fix absent A/B evidence.

## Minimal controlled A/B test plan

Keep same emulator build, same game, same graphics driver, same power mode and roughly same temperature/battery state.

1. **Baseline: O2P session STOPPED**. End recording using O2P Session tile, verify monitoring notification is gone, optionally remove O2P from recents, but do not grant new privileged settings. Launch the **same** game from Eden three times. Record success/failure and any crash.
2. **Recording only: notification HUD mode**. In O2P, set HUD mode to **Notification only** and disable optional auto-recover-on-game-launch; set recording sample interval to **5000 ms** temporarily. Start a separate gameplay session, then repeat the same Eden game launches. Check O2P Session tile and record export.
3. **Recording + floating HUD**. Only if step 2 is stable, restore floating HUD and retest. Compare whether HUD presence or one-shot reattach correlates with game launches failing.
4. After a failure, do not start another O2P recording yet. Export **Telemetry → Share recorder lifecycle report**. The updated report must contain `ANDROID PROCESS EXIT HISTORY (SYSTEM-REPORTED)`; if it does not, it is an older APK. Include screenshots or notes about Eden's failure message, if any.
5. If failures occur with O2P stopped, investigate emulator-specific issue or system/vendor behavior; don't attribute to our monitor. If failures arise only while O2P recording or overlay is active, inspect process exit reasons / OS logs and reduce overhead or disable overlay by default.
6. If possible, capture `adb logcat` shortly after a failed launch (e.g., `ActivityManager`, `lmkd`, `crash_dump64`, `FATAL EXCEPTION`, `tombstone`, `dev.eden.eden_emulator.nightly`) since O2P's own `ApplicationExitInfo` reports only O2P's process exits, not Eden's.

Do not assume FGS or separate process is immune to memory reclamation. No `oom_score_adj` changes, `drop_caches`, minimum-frequency writes or change to the existing no-root constraint.

**Gate:** Same-game A/B test results plus Android process exit reason. Only then decide whether to optimize the overlay, detector frequency/telemetry overhead, or app lifecycle deeper.
