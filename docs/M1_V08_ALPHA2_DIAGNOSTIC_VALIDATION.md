# v0.8 Alpha 2: M1 Read-only hardware validation

No frequency, fan, thermal, governor, permission or voltage writes. No root/su. The PServer host is optional, single-use and read-only. No auto upload.

## What's new
- Bounded thermal zone 0..127 discovery, with names from each sensor type file, plus a cached CPU/GPU sensor map in live monitoring to avoid repeated directory enumeration
- Android PowerManager thermal status and NaN-aware headroom sampled conservatively
- Read-only hwmon fan labels, tachometer and PWM observations; PWM is NOT RPM
- ClusterTune-inspired host result protocol with separate authenticated host identity and per-node OBSERVED/PERMISSION_DENIED/NOT_FOUND/EMPTY/IO_ERROR plus Linux errno
- Cached live PServer Binder, death invalidation, zero automatic retries on ambiguous host launch
- Versioned redacted hardware JSON, session/HUD lifecycle breadcrumbs and Read-only M1 validation checklist in Diagnostics

## Test A: Idle direct diagnostic
1. Select the normal AYN performance mode, close other tuners, open Diagnostics and select Direct.
2. Check 3 CPU policies, GPU frequency table, named CPU/GPU temperature sensors, Android thermal state/headroom and hwmon entries.
3. Share JSON and save the exported text as portal_direct_idle.json. JSON does not include serial, Android ID, accounts, host nonce or private launch path.

## Test B: Authenticated host and error handling
4. In Diagnostics explicitly tap Verify PServer host (read-only) once.
5. Share JSON again as portal_pserver_result.json. Review pserverHost.status, UID, and each node's typed status, value, source and Linux errno.
6. Host authenticated does NOT mean nodes readable and NEVER means OEM Stock or writes verified. A failed host-launch request is a useful compatibility result. Do not root the device.

## Test C: Eden monitoring reliability
7. Start monitoring before Eden, then launch Mario Kart, run 10-15 minutes and check ongoing O2P notification/HUD.
8. Stop via O2P Session Quick Settings tile. Export gameplay CSV, then refresh Direct Diagnostics and export portal_after_game.json. Inspect sessionDiagnostics lifecycle history and whether any gap was recorded.
9. Note average CPU and busiest core, GPU utilization, named sensors, thermal headroom. FPS remains unavailable without a verified game-layer source.

## Test D: AYN performance modes
10. Change AYN's mode through its own UI; refresh Direct and export another JSON. For Stock Evidence enter the actual user-selected mode label and save two captures at least 20 seconds apart. Those captures are observational evidence, not an authenticated Stock reset target.

## Readiness gates
- CPU and GPU OPPs: real listed steps; firmware runtime ceilings shown separately
- Thermal: at least one named CPU and GPU sensor, not index guesses
- Fan: optional; no tachometer is acceptable, never infer RPM from PWM
- PServer: host UID proof AND per-node statuses, with failures reported explicitly
- Recording: no silent service interruption; failures/restarts leave lifecycle breadcrumbs
- OEM Stock restoration: BLOCKED until independently physically validated
- Hardware profile writes: DISABLED for this entire release

Please send portal_direct_idle.json, portal_pserver_result.json, portal_after_game.json, the gameplay CSV, and a note about HUD visibility at Eden game launch. The app must not claim 100% hardware validation solely because Android build/tests passed.

Next: fix firmware-specific host read errors, determine exact effective CPU minima, investigate KGSL level mapping, then plan a separate audited maximum-ceiling-only writer with rollback and verified Stock restore. AutoPilot remains frozen.
