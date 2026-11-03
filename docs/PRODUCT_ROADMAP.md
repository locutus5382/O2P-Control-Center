# O2P Control Center — Product Scope and Delivery Plan
Status: scope revised with owner, 2026-11-03. Device validation pending. Target: AYN Odin 2 Portal Base (8 GB), discover actual supported firmware and device controls.

## Product goal
A polished Odin-focused **adaptive/manual performance manager and performance monitor**, plus a **small set of valuable extra device controls**. Avoid duplicating standard AYN toggle/dashboard functions.

### Scope for release candidate
1. **AutoPilot:** closed-loop CPU/GPU frequency-ceiling optimization with validated recovery, no replacement of stock thermal management.
2. **Manual Tuning:** independent CPU-policy/GPU maximum-frequency settings and per-app profiles; discrete OPP options discovered on-device, one-tap restore, no experimental voltage/thermal changes.
3. **Performance Monitor:** opt-in dashboard + configurable in-game FPS, CPU/GPU thermal/clock/load, RAM (system and process when possible), battery temperature and fan RPM (if measured) as overlay or notification.
4. **Display Saturation:** prefer official natural/sRGB/OEM color mode; if absent, test optional privileged SurfaceFlinger factor adjustment with clear reset and firmware gating. Neutral `1.0` does not guarantee panel color calibration. The persisted property is not a reliable runtime readback.
5. **Single-press Home (optional):** only when AYN firmware toggle works reliably on Odin 2 Portal; omit otherwise.
6. **Appearance:** Glass/Material, 18 accents each, opacity and light-sweep, reduced motion.

### Explicit exclusions
- No duplication of AYN Xbox/Switch controller style, trigger-mode, vibration controls or routine fan/performance-mode buttons.
- No charging separation or charge-level controller without a separately validated and safe design; **not planned for first release**.
- M1/M2 remapping deferred unless real user value beyond stock features is established.
- OEM fan/performance modes can be **observed** to respect user-chosen state; our optimizer must not override those modes automatically.
- No CPU undervolting, governor swaps, forced thread affinity, unsafe DDR throttling, or disabled Android thermal protection.

## Navigation
- **Dashboard:** active mode, metrics, device readiness and shortcuts.
- **Performance:** Stock / Manual / AutoPilot, profile management, benchmark comparisons.
- **Monitor:** master ON/OFF, metric chooser, notification, HUD/overlay, logging and export.
- **Display:** saturation/native color mode checks and reset; optional Home key in a compact device-settings section if verified.
- **Appearance:** Glass vs Material, independent accents, clarity and light-sweep.
- **Settings & Diagnostics:** privileges, verified sensor status, firmware/device support, full restore and troubleshooting.

## Ownership & safety contract
- Single typed `HardwareGateway`, serialized writes, allowlisted nodes and verified OPP frequency options.
- Snapshot actual effective values and ownership before any mutations. Never silently assume successful PServer command. Recover only what is still app-owned, detect external changes.
- AutoPilot and Manual cannot simultaneously own clocks. AutoPilot sees OEM performance/fan mode as external input; OEM thermal limits remain authoritative.
- Telemetry is read-only and can run without tuning. A shared collector must serve monitor and AutoPilot to avoid duplicate hardware polling.
- Where readings are missing or restricted, show **Unavailable** and source/permission state. No fabricated RPM, GPU temp, per-game FPS, or RAM usage.
- No background polling or boot persistence by default. Consent and robust cleanup for ongoing foreground session/overlay.

## Monitor design (issue #4)
- User-chosen fields: presented FPS (when privileged SurfaceFlinger layer timing works; distinguish from emulator internal FPS), CPU/GPU temperature (named sensors), battery temperature, CPU/GPU frequency/utilization, system RAM, optional privileged active-process RAM and actual RPM tachometer.
- Views: dashboard, a movable/lockable minimalist floating overlay and optional notification.
- Default sampling suggestion: 1 s CPU/GPU/thermal, 1–2 s FPS windows when safe, 5–10 s process memory; 5–10 s notification updates. Benchmark monitoring overhead.
- Persistent sessions require appropriate Android FGS type; overlays need `SYSTEM_ALERT_WINDOW` opt-in. Stop action and idle shutdown are mandatory.
- Record/CSV export only with explicit opt-in. See issue #4 for exact backend and acceptance criteria.

## Display & Home design (issue #2)
- Check device-provided display color mode first and recommend Natural/sRGB when available.
- AOSP Android 13 SurfaceFlinger binder transaction `1022` offers saturation boost factor clamped to 0–2 on some builds; private interface, firmware-dependent, not a supported universal app API. Use PServer/root only after safe discovery and device tests.
- Begin with nonpersistent saturation preview, mild desaturation and obvious reset. Do not promise exact original restoration if no runtime readback exists.
- Single-press Home limited to known firmware toggle; avoid an accessibility key interceptor.
- Other AYN settings intentionally omitted.

## User experience
- Clean premium design with readable Glass or Material theme, 18 selectable colors per visual style, saved preferences, reduced motion and low-overhead animations.
- Glass translucency is UI-only; OLED saturation affects the **whole display** and belongs under Display, not Appearance.
- Monitor HUD remains simple and light, not continuously blurred; never cover important gameplay by default.

## Build and validation gates
1. **First alpha builds**: CI tests + assemble success on 2026-11-03, workflow run 41547923246; physical device testing has *not* yet happened.
2. **Repository hygiene** (issue #3): unpack source from original ZIP into ordinary tracked files, remove CI unzip workaround, ensure reproducible lint/tests.
3. **Hardware discovery**: user installs debug APK on Odin 2 Portal Base and exports report; determine PServer privilege, CPU policies/OPPs, named GPU/thermal/fan capabilities, display mode and firmware version.
4. **Performance Monitor MVP** (issue #4): implement read-only live dashboard, selectable metrics and notification; overlay and FPS once privilege/layer validation is proven.
5. **Manual tuning beta** (issue #1): write-verified CPU/GPU ceilings, original-value snapshots, emergency restore and conflict detection. Must pass on-device tests.
6. **Display beta** (issue #2): official native color mode if available, otherwise tested SurfaceFlinger saturation with reset. Single-press Home only if reliable.
7. **AutoPilot beta**: adaptive control with benchmarked sustained frame quality/thermals and no conflict with manual mode.
8. **Polish and release**: UI/accessibility tests, motion contrast, complete permissions/help, signed builds, provenance and crash recovery.

## References and license
- https://github.com/locutus5382/O2PKernelTurb
- https://github.com/AurelioB/ClusterTune (current MIT license, check historic code version before copying)
- https://github.com/langerhans/OdinTools (MIT; Odin 2 1.0.0.288 documented, not Portal validation)
- https://developer.android.com/topic/performance/issues/slow-session
- https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android13-release/services/surfaceflinger/SurfaceFlinger.cpp
- Retain third-party license notices and independent attribution for incorporated code.

## Release gates
- No unverified privileged writes, no false-success statuses, no hidden thermal-safety bypass.
- No monitoring in the background without user-enabled session.
- Per-metric validity and clearly labeled FPS/temperature sources.
- Safe reset even under crashes where architecture permits; otherwise honest limitations.
- No device-specific claims until verified by hardware report and on-device tests.
