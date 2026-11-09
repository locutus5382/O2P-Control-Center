# O2P v0.8 Alpha 1 — recording resilience + PServer read-only host

**Scope:** AYN Odin 2 Portal / Android 13, 9 November 2026. This branch is a device-test candidate, not a tuning release. No CPU/GPU frequency writes exist.

## Product hypothesis

Our target is **the same stable FPS with lower energy/heat**, and **higher sustained FPS only where a verified thermal bottleneck exists**. Lowering the CPU maximum does *not* create raw processor performance. Total CPU load of ~35% can hide one saturated emulation thread; GPU load at 40–70% may reflect a frame-rate cap, GPU headroom or CPU bottleneck. Without validated FPS/frame times, no improvement can be claimed.

### Monitoring addition

`Busiest CPU core` is computed from per-core deltas in the **same single `/proc/stat` read** used for total CPU utilization. It is included in CSV export and available through Custom HUD metric selection. This is a core-level utilization approximation, **not thread attribution**, and it cannot prove Eden's precise bottleneck.

## Recording stability (new)

Previous `GameplayMonitorService` was `START_NOT_STICKY`. Android does not automatically recreate a service after killing its process in that mode. The foreground service now uses `START_STICKY` **only for an explicitly started, ongoing recording**, backed by a bounded recovery lease and the existing SQLite session:

- Persist the session ID, original start time, sample/history intervals, manual label, last heartbeat and process ID to app-private preferences.
- Update the durable heartbeat every ~10 seconds; write a small bounded lifecycle event history.
- When Android restarts the same foreground service with a **null Intent**, only resume if the previous lease was explicitly active, not user-stopped, last heartbeat <=120 seconds old, and the SQLite row is still `RECORDING`.
- Reattach the existing session ID, appending new history windows without creating a second recording. The gap while the process was dead cannot be reconstructed and must never be fabricated.
- Refuse restart after user Stop, outdated lease, database mismatch or reboot. No boot receiver and no background self-start.
- Explicit Stop records its intent before cancellation. `onTaskRemoved`, `onDestroy`, exceptions, cancellations, start failures and restarts produce lifecycle breadcrumbs.
- Existing SQLite table remains unchanged; user history is retained. A new explicit Start marks old orphaned `RECORDING` rows as `INTERRUPTED`.
- Both Sessions and Quick Settings Session panel display recent lifecycle events; the tile distinguishes a live service from a lingering persisted recovery lease.
- Foreground-service start still requires notification permission and visible Stop control. An unexpected vendor termination is not universally recoverable.
- Sessions shows Android's battery-optimization status with a link to system app settings, **without granting or bypassing any permission automatically**.

### Why this matters

User observed that starting Eden or starting a Mario Kart game may terminate the active recording. The three 2026-11-09 CSVs show **three distinct recording sessions**, but the exports do **not** identify whether OS memory pressure, AYN's game optimizer, a foreground-service exception, or explicit Stop caused their ending. New lifecycle records should identify which cases are recoverable.

## PServer authenticated host prototype (new, read-only)

Earlier O2P reliably saw `PServerBinder`, but the legacy command-output probe failed. We already integrated ClusterTune's current MIT-licensed **launch-only, output-disabled Binder transport**, retaining its MIT license notices. In this release, the user can explicitly try a **single-use read-only host**, from `Studio → OEM Stock Evidence → Verify read-only PServer host`.

Architecture:

1. Confirm firmware `PServerBinder` is alive, without sending commands as part of the normal diagnostics screen.
2. Extract the app's `classes*.dex` into its private code cache and construct an internally generated, fixed host launcher script. PServer receives only the short command to execute that trusted script.
3. The host can run only if firmware starts it with UID 0 (system root) or UID 1000 (system). **No user rooting and no `su` fallback.**
4. A random 192-bit nonce, exact package, owner UID and app generation identify the handoff. The host returns a Binder to an explicitly package-scoped, temporary dynamic receiver.
5. Client sends a Binder challenge; the host calls back to the app, allowing the app to verify **kernel-supplied `Binder.getCallingUid()`**, and must prove the same nonce and generation.
6. Read-only host's IPC supports only `PING`, `READ` and `STOP`. The `READ` command uses an embedded immutable allowlist of 21 known Portal sysfs paths (CPU policy0/3/7 topology, effective min/max, firmware frequency lists and relevant GPU KGSL cap/level values). **It accepts no user or client-supplied file paths or shell commands.**
7. Client validates returned path count, names and lengths, and issues `STOP` in finally. Host also self-terminates after 20 seconds even if its client disappears. The readout is shareable through an Android SEND intent.
8. **No** privileged write requests exist; no OPP application, Stock restore, chmod, CPU minima, thermal, governors, GPU levels, fan or memory edits are implemented.
9. **Physical-device compatibility is not yet verified**: host extraction, firmware command format, `app_process`, hidden system-context APIs and SELinux may cause this prototype to fail closed on the Odin 2 Portal. Any returned error is diagnostic input, not permission to try arbitrary shell code.

Full source references:
- ClusterTune MIT PServer host launch: https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/root/PServerHostLauncher.kt
- ClusterTune host handoff: https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/root/host/HostRendezvous.kt
- ClusterTune host entry: https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/root/host/ClusterTuneHostEntry.java
- Android foreground-service restart contract: https://developer.android.com/reference/android/app/Service#START_STICKY

## Test guide — on the actual Odin 2 Portal

1. Install latest passing debug APK from this branch. Export important histories first; debug signing can differ across GitHub Actions runners, requiring an uninstall.
2. In **Sessions**, grant notifications and Display over other apps. Verify Android battery restrictions; optionally open system app settings if the system is restricting O2P.
3. Start a session **before** launching Eden. Keep O2P notification accessible.
4. Launch Eden, then Mario Kart. Check whether the ongoing notification remains, whether HUD disappears, and whether it returns after a single game-transition reattach.
5. If Session tile says Stopped, open its panel: **Session lifecycle** and the last four events. Record exact wording. If there is a recent lease but no service, report it; do not assume it's still recording.
6. If the foreground service is actually killed, Android may restart it via `START_STICKY` if permitted; history should continue under the **same SQLite ID**, with a gap but no fictitious samples. If not restarted, app reports a stale heartbeat/interrupted lease.
7. Stop via Quick Settings. Confirm session history saved and no automatic restart after explicit Stop. Reopen Eden and ensure recording does not silently begin.
8. Run a second 10-minute game session and export a CSV. Inspect **Busiest CPU core** alongside total load, GPU load, CPU/GPU clock and temperatures. Do not claim a game is underutilizing the SoC solely from total CPU/GPU percentages.
9. In Studio use **Verify read-only PServer host**. If successful, export the report showing UID and CPU effective min/max, and verify each path was explicitly read-only. If failed, export the diagnostic reason. **Do not use root or change Android system files manually**.
10. For OEM Stock captures, enter the actual AYN mode name and save two distinct snapshots at least 20 seconds apart. A single authenticated PServer host read does **not** yet prove an independently verified OEM Stock restore target.

## Next gates

- Accept/repair read-only PServer host on this firmware using device-produced evidence. Don't enable writes from an unverified host.
- Verify CPU policy0 min/max, policy3/7 boost ceiling vs listed OPP, GPU power-level mapping and AYN mode, ideally under a game load.
- Implement a separate underclock-only transaction and Stock restore behind an explicit, reviewed PR; test preflight, rollback, crash and AYN vendor overrides.
- Real FPS/frame-time measurement must be validated before making comparative tuning claims.
