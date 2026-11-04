# O2P Control Center — implementation status

Date: 2026-11-03

## Implemented in this prototype

- Independent Android app ID (`com.odin2portal.control`), so the old O2PKernelTurb installation remains unchanged.
- Kotlin + Jetpack Compose dashboard with CPU policy, GPU, named thermal-zone, RAM/swap, memory-pressure observations.
- Read-only hardware discovery with no hardcoded frequency, thermal-zone or fan assumptions.
- User-selected direct / PServer / `su` inspection, no silent privilege escalation.
- Reuse of the **PServerBinder** reflection + `Parcel` transaction mechanism from the author's O2PKernelTurb, but *without* copying its arbitrary shell-write API.
- Verified command-status marker, rejection of path injection, explicit unavailable/unknown UI states.
- Error reporting and shareable plain-text diagnostics.
- Unit tests for parsing, path validation, and the command protocol.
- GitHub Actions workflow to compile and test an APK when this project is pushed to GitHub.

## Not implemented yet

- No CPU/GPU writes, no manual caps, no AutoPilot, no fan control.
- No session snapshot/restore because no writes are enabled in this milestone.
- No boot receiver, background monitoring or foreground service.
- No overlay, per-game detection, frame timing, Profile DB, or historical telemetry.

These features are not represented as working in the UI.

## Build verification limitation

This delivery container has a Kotlin compiler, but **no Android SDK, Gradle or Gradle wrapper JAR**, and cannot download them. JVM-only logic can be syntax-checked using `kotlinc`, but an APK **has not been compiled or installed on Odin hardware** here. Use Android Studio's Gradle toolchain or the included GitHub Actions job to compile and run `:app:assembleDebug :app:testDebugUnitTest`. The CI workflow fetches Gradle via `gradle/actions/setup-gradle`, so it does not depend on a bundled wrapper JAR.

## Device validation requests

1. Start the app and use **Direct**, then **PServer**. Root is opt-in if PServer cannot read protected nodes.
2. Share the diagnostic report; verify CPU policy names (not assumed to be 0/3/7), GPU nodes, thermal zone types, and reported PServer UID.
3. Compare readings against stock AYN monitoring while idle and gaming; identify sensors that track SoC, skin or battery temperatures.
4. Only after verification implement SnapshotManager + verified restoration + manual CPU/GPU ceilings.
5. Require manual hardware/safety validation before enabling an adaptive frequency controller.

## Code from the original repository

Audited: `PServerExec.kt`, `MainActivity.kt`, `BootReceiver.kt`, `TurboTileService.kt`, original `app/build.gradle` and manifest, from https://github.com/locutus5382/O2PKernelTurb .

The original has an unchecked PServer result path (`OK` on empty bytes), unverified `su` exit status and hardcoded `revertToStock` values. This new project does not transfer its unsafe tuning functions. It retains the PServer protocol concept and makes no writes.

## Extra offline work

`autopilot/AutoPilotPlanner.kt` contains an **offline, deterministic** algorithm prototype with separate propose, validation, recovery and cooldown actions. It is not connected to the privileged gateway and cannot change clock frequencies. The corresponding tests exercise stable-window, regression rollback, untrusted timing and thermal-guard behavior.
