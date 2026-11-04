# O2P Control Center

A dedicated Android performance and diagnostics project for the **AYN Odin 2 Portal Base**. The user interface offers Liquid Glass and Material-style appearance options, and the current alpha is intentionally **read-only**.

## v0.2 alpha

### Hardware Explorer
- Inspect actual CPU frequency policies, GPU/KGSL nodes, named thermal zones and memory pressure.
- Choose **Direct**, **PServer**, or **Root** read-only inspection explicitly. No privileged mode is activated automatically.
- Export diagnostic data through Android's Share menu.

### Performance Monitor (new in v0.2)
- Open the **Monitor** tab and turn live monitoring on.
- Choose any combination of FPS, CPU/SoC temperature, GPU temperature, battery temperature, CPU load, CPU/GPU clocks and utilization, system RAM, and fan RPM.
- Select a sampling interval of 1, 2 or 5 seconds. The chosen metrics and interval are saved locally.
- All metrics have **Unavailable** behavior for missing/blocked sensors. FPS is not implemented until a valid SurfaceFlinger per-layer source is confirmed.
- Monitoring only operates while the Monitor screen remains visible and the Activity is active; it stops when you leave the screen or the app goes into the background. No daemon, notification, floating HUD, or system setting writes are enabled yet.

### Appearance
- Choose Glass or Material and independent color accents, plus adjustable glass translucency and press-triggered light sweep.

## Build

The Android sources are tracked as ordinary files in GitHub. The old ZIP-based workaround has been removed from the new branch.

- Java 17; Android SDK 35; Gradle 8.9
- From the repository root: `gradle :app:testDebugUnitTest :app:assembleDebug`
- Alternatively, GitHub **Actions → Android debug build** publishes `O2P-Control-Center-debug` containing `app-debug.apk`.
- This project includes no Gradle wrapper executable JAR; use a local Gradle installation or GitHub Actions.

**Validation:** GitHub Actions CI passed for the v0.2 feature branch. The new monitor has not yet been tested on physical Odin hardware. Sensor access, GPU formatting and privileged inspection are firmware-dependent and must be verified on-device.

## Safety

- This alpha never writes to `/sys`, `/proc`, charging, fan, or thermal settings.
- Manual tuning and AutoPilot are planned, not operational. No fake FPS, temperature or fan readings are generated.
- The exported diagnostics could contain device-specific information. Review before sharing publicly.

See [product roadmap](docs/PRODUCT_ROADMAP.md) and [v0.2 draft pull request](https://github.com/locutus5382/O2P-Control-Center/pull/5).

## History

The project is based on concepts from [O2PKernelTurb](https://github.com/locutus5382/O2PKernelTurb); its original repository remains unchanged.
