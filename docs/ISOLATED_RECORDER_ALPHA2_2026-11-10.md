# v0.9 Alpha 2 — isolated gameplay recorder: November 10 Portal evidence and test plan

## Observed issue (not yet proven root cause)

Owner reports O2P recording and Quick Settings session status are interrupted while starting a game within Eden. The 2026-11-10 uploads include three Gameplay CSVs plus separate passive Android diagnostics and authenticated PServer read-only host results.

| CSV | UTC time | Duration | Samples | Attribution | Interpretation |
|---|---|---:|---:|---|---|
| Gameplay_1_20261110_1547 | 14:35:40–14:36:55 | 75.6 s | 75 | ES-DE→Eden, plus package installer | Ended while Eden was foreground; cannot infer cause without lifecycle events |
| Gameplay_2_20261110_1547 | 14:37:20–14:37:30 | 10.1 s | 6 | Unidentified/ES-DE/DocumentsUI | Unusually short; no causal evidence |
| Gameplay_3_20261110_1547 | 14:37:54–14:46:45 | 530.3 s | 258 | Eden 51/54 windows | Recorder successfully captured almost nine minutes of Eden; one interwindow sampling gap of about 12.4 s |

No presented FPS values in any CSV. CPU and GPU listed clocks are *current operating frequencies*, not evidence of O2P underclocking, and the last selected Quick Settings performance profile remains **preview-only**. The active v0.9 branch has **no CPU/GPU writer**, as both PServer and original Stock candidate reports independently confirm. Verified PServer UID0, 21/21 sysfs reads success: read-only capability is working.

The earlier recording journal implementation uses an in-memory `GameplayRuntime` StateFlow, which can reset after UI/process death. Because the Android UI and the recording service previously ran in the same process, a process termination could end the service while starting a memory-hungry game; however **this mechanism has not been proven on the Portal**. Existing `START_STICKY` and a bounded persisted lease support a system-driven restart where permitted, but cannot prevent device/vendor or memory-pressure termination.

## Changes in this dedicated fix branch

1. Move the user-started `GameplayMonitorService` into the private `:monitor` process with `android:stopWithTask=false`. Android can reclaim the UI process independently; the recorder still may be killed under severe memory pressure or vendor restrictions.
2. Add `RecordingStatusProvider` in the *same* private `:monitor` process, not exported. It returns an actual runtime snapshot plus recent lifecycle events, PID, current samples and recovery-lease state to authorized same-app callers.
3. Bridge that status into the main UI and Quick Settings panel only while visible. Quick Settings tiles query the provider instead of relying on reset in-process state. Service remains opt-in and no remote sensor polling is created.
4. Show CPU/GPU current temperature display strings from the privileged-to-UI status bridge without inventing an unavailable `MonitorSample`.
5. Add a **Share recorder lifecycle report** action in Telemetry/Sessions and the Quick Settings Session panel. Include session ID, service PID, latest sample timestamp, lifecycle events, recovery lease, and HUD status.
6. Journal service start/action/PID and use a synchronous checkpoint for its small crash-recovery lease record. A runtime with no samples for >20 seconds reports sampling delay instead of silently pretending everything is healthy.
7. Bump debug version to `0.9.0-alpha2-lifecycle` (`versionCode 17`).

### Safety and unsupported behavior

- No kernel, CPU/GPU, fan, governor, voltage, minimum-frequency, permissions, SurfaceFlinger or storage writes.
- Privileged PServer validation remains read-only. Quick Settings **profile** tile remains explicitly preview-only (does not apply any underclock).
- This is a proposed process-isolation reliability improvement, not a proven repair for OEM game manager killing an active FGS. On-device acceptance is mandatory.
- The provider intentionally does not expose full telemetry data over Binder and does not support cross-app calls.
- A service restart can still miss samples; never reconstruct or fabricate missing sample windows.

## Targeted physical-device test

1. Export old gameplay recordings before uninstalling any differently signed debug APK. Debug APK signing may change between CI environments, so Android can reject replacement; uninstall can destroy app-private recordings.
2. Install the branch's successful debug artifact (if CI passes). Confirm normal O2P UI and settings still work. Start recording. In Telemetry, note service PID in **Service lifecycle**.
3. Use Home / ES-DE to launch Eden and a game. Keep playing >10 min. Quick Settings **Session** tile must remain `Recording` (not merely O2P profile preview). The O2P persistent Stop notification must remain available.
4. Return to O2P, check the same session ID/PID and whether history continues. In the service lifecycle report, look for `SERVICE_CREATED`, `START_REQUESTED`, `STICKY_RESTART`, `SERVICE_DESTROYED`, `RECORDING_EXCEPTION`, `SESSION_RESUMED`, and `SERVICE_COMMAND`. A new PID indicates process restart.
5. Repeat with a game that previously interrupted the session. If interrupted, tap **Share recorder lifecycle report** before beginning another session. This report is more diagnostic than a bare CSV.
6. If the UI process is reclaimed but `:monitor` remains alive, Quick Settings should show live status and the report must contain the original PID/session ID. If `:monitor` itself dies, report should show missing/restarted PID, pending recovery/last heartbeat rather than a false successful recording.
7. Observe RAM, notification/overlay permission, AYN performance manager, and emulator mode if the service still terminates. An Android logcat `am_kill`, `FATAL EXCEPTION`, or foreground-service policy record would be needed to determine exact kill cause.
8. Test normal Stop, start another session and export history to rule out duplicate/split recordings.

## Acceptance before enabling real tuning

1. No incorrectly reported applied underclock in Quick Settings: selection is still **not applied**.
2. No last-known hardware profile relies on the UI process or on a session recorder surviving; future writes require a *separate* authenticated host-owned watchdog and verified rollback.
3. No UI session status based solely on a local singleton after process death. 
4. Session restoration must be accompanied by explicit gap labeling; no hidden daemon or boot auto-start.
