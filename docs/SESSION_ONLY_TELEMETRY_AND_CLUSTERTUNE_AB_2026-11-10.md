# v0.9 Alpha 2.2 — session-only telemetry, single Quick Settings toggle, on-demand RAM footprint

Owner decision 2026-11-10: Remove the independent in-app live telemetry collector; collect telemetry only after the user starts a session. Remove redundant profile and mini-panel Quick Settings controls: one tile, Start Session / Stop Session. Configuration, HUD, history and diagnostics remain in the full main app. User reports that Eden occasionally fails to start games while O2P monitoring is active, whereas ClusterTune has generally been stable. That observation suggests a useful A/B comparison, not proof of causality.

## Code changes

- **One collector**: Retain `GameplayMonitorService` (already an isolated user-started foreground service) and its `DirectMonitor`. Completely remove `MonitorPanel.kt` and the independent `MonitorViewModel` sampling/short-test class. Keep the read-only `DirectMonitor` implementation used by the session service.
- **One Telemetry screen**: Directly display `GameplayPanel` (start/stop, settings, live readings only during an active session, HUD controls, history, diagnostic share). Persist recording sample interval (1/2/5 sec), aggregation interval (5/10/30/120 sec) and optional session label to existing `monitor` preferences.
- **One Quick Settings tile**: `SessionQuickTileService` handles an explicit tap by starting the same foreground service using saved intervals (if no session), or sending the explicit Stop action (if active). Tile labels are Start Session / Stop Session. No Quick Settings mini-panel, no profile preview tile, no kernel writes. The main app grants Android notifications/permissions and offers the installer.
- **On-demand RAM**: `ProcessMemoryMetrics` collects each process's PSS, private dirty memory, swap PSS, RSS and peak RSS when the user explicitly exports a lifecycle report—not on every telemetry sample or UI 1.5-second status poll. `RecordingStatusProvider` offers a private same-application one-shot memory query of `:monitor`. Report distinguishes UI process from the recorder process; measurements represent *time of export*, not memory at a prior crash.
- Keep prior process-exit history, foreground-service recovery and avoid any false claims about underclock tuning. Neither this nor previous read-only releases write CPU/GPU frequencies.

## Android / safety constraints

- Clicking Quick Settings is a direct user action. Android may still refuse `startForegroundService` under particular vendor restrictions; the tile catches RuntimeException and opens the main Telemetry screen. On Android 13+, the app requires `POST_NOTIFICATIONS` for its recording/Stop action and guides users to grant it in the main app.
- An idle UI/Quick Settings tile may cause the remote provider process to be created for a status query; **sensor collection nevertheless remains OFF** until a session is user-started. Do not claim zero RAM use when stopped.
- PSS is a proportional share of resident pages, not a direct absolute total and can change dynamically. RSS counts all shared pages in each process; do not sum RSS of UI + monitor. Process memory can be higher or lower during game launch than when later exported.
- A session started while a game is already running should collect just as one started beforehand. No boot/automatic session starts, dynamic OOM priorities, privileged writes or process-protection hacks.
- The current O2P Profile preview tile, if previously installed, will be removed from the manifest; user can remove any ghost tile from Android Quick Settings edit mode.

## Controlled comparison with ClusterTune

Use the same Eden game/driver/AYN performance mode, similar temperature, and repeat each case 3 times. Start the same game and allow its first playable scene to appear.

| Case | O2P | ClusterTune | Key observation |
|---|---|---|---|
| A — baseline | Recorder stopped | Off | Does Eden start consistently without either tool? |
| B — ClusterTune | Recorder stopped | On, same fixed tested profile or monitoring-only if possible | Does the game start reliably? |
| C — O2P after launch | Start session ONLY after game becomes playable; HUD notification-only, sample 5000ms | Off | Does recording persist after launch, and how much PSS does :monitor consume? |
| D — optional stress test | Start O2P session BEFORE launching the game, notification-only, sample 5000ms | Off | Is game launch specifically the trigger for interruption? |

Never run both tuners in parallel during future hardware-write testing. For current read-only O2P, ClusterTune tuning might alter reported ceilings and spoil an apples-to-apples profile comparison.

After each O2P test, export the lifecycle report and gameplay CSV. Look for `ANDROID PROCESS EXIT HISTORY` and `O2P RAM FOOTPRINT`. For ClusterTune memory data if desired use Android developer tools (`adb shell dumpsys meminfo <package>`) or Android Settings per-app usage. If Eden fails, capture Eden errors/logcat; O2P's own process exit report cannot tell whether **Eden** itself crashed.

Acceptance: single tile starts/stops the SAME recording with accurate active state after process restart, only the dedicated recorder samples hardware, on-demand report includes separately labeled UI/remote PSS, no regression to history/HUD settings, and the 3-way comparison provides enough evidence to decide whether game-launch interference remains.
