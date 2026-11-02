# O2P Control Center — Product Scope and Delivery Plan

Status: proposal, 2026-11-03. Not a claim of tested device compatibility. Target: Odin 2 Portal Base (8 GB); discover hardware/firmware capabilities rather than hardcoding.

## North star
A single, polished, safe-to-use app combining (1) performance monitoring and adaptive optimization, (2) optional advanced manual frequency profiles, and (3) Odin handheld controls. Retain stock thermal protection and allow the app to work in read-only mode without root/PServer.

## Navigation
- **Home:** active state, thermals, FPS (where measurable), shortcuts, privilege status.
- **Performance:** Stock, AutoPilot, Manual, per-app profiles, live readback, conflict warning.
- **Odin Controls:** buttons, display saturation, controller/L2R2, fan/performance OEM modes, charging/vibration as supported.
- **Monitor:** named sensor readings, frequency trend, RAM/memory pressure, logs and export.
- **Appearance & Settings:** Glass or Material, per-style 18 accent colors, opacity, tap-light sweep, reduced motion, recovery, permissions and firmware support.

## Control ownership
- All device mutations go through a serialized typed HardwareGateway.
- AutoPilot and Manual never concurrently own CPU/GPU maximum-frequency ceilings. OEM thermal control is always authoritative.
- Entering a manual session snapshots the effective policy values and expected node metadata. Changes are allowlisted, discrete verified OPP entries, write-verified, and audited.
- Restore changes only nodes/values that this app still owns. If another tuner or firmware changed the value unexpectedly, stop or leave untouched and report conflict instead of overriding.
- Crash recovery and privileges are validated on physical hardware before enabling write features. Stock and read-only remain available without privilege.
- Every optional module has read-only capability probe; unsupported settings show a non-destructive explanation.

## Manual Performance Studio (issue #1)
- Frequency ceiling controls per *discovered* CPU policy and supported GPU clock domain.
- Readable MHz/GHz; optional normalized bars only as visual affordance.
- Stock / manual / AutoPilot switch; save/load, per-app profile, import/export after validation; one-tap restore.
- First release excludes CPU governor replacement, thread affinity/cpuset forcing, CPU hotplug, voltage modification, thermal disable, unsupported DDR devfreq writing and minimum clocks.
- Benchmarks compare sustained FPS stability, thermal trajectory, and battery power when measurable.

## Odin Controls (issue #2)
**Home:** use firmware-supported single-press behavior, not arbitrary reassignment of protected HOME routing unless a safe, separately verified device-specific mechanism exists.
**M1/M2:** support vendor mappings verified on target firmware, with default/restore.
**Saturation:** optional system-wide adjustment; known OdinTools route uses 'service call SurfaceFlinger 1022' and property 'persist.sys.sf.color_saturation'. These are private and not guaranteed on Odin 2 Portal. Verify support; present a neutral default and explicit preview/reset. Never claim display calibration.
**Controller:** AYN controller-style and L2/R2 settings; per-app overrides when verified, plus quick settings if reliable.
**OEM modes:** performance/fan modes can be selected where supported; no concurrent conflict with manual fan or AutoPilot ownership.
**Charging:** charging separation only if device reports verified supported behavior; never experiment with arbitrary current levels. Do not market as an exact charge limit unless measured.
**Vibration:** optional, firmware validated.
No permission escalation, auto-start accessibility, or boot persistence without explicit user consent.

## Appearance
- Glass + Material rendering use one semantic typography/contrast system.
- Glass is translucent with readable backplates (configurable 55–94% opacity), subtle light accents, press-triggered diagonal shimmer, reduced-motion support, and no continuously expensive animations.
- Separate accent selection per style: white, black, dark gray, light gray, dark blue, light blue, red, light red, light yellow, yellow, bright yellow, light green, green, dark green, light purple, dark purple, light orange, dark orange.
- Keep UI animation away from sustained game workloads; no persistent blur overlay in gameplay by default.

## Delivery gates
1. **Foundation / CI** (issue #3): unpack archive to tracked source, compile/test/lint, stable debug APK, no privileged writes.
2. **Read-only device discovery:** obtain diagnostic capture from Odin 2 Portal Base; identify firmware, policies, GPU nodes, thermal sensors, vendor settings, validated privilege transport.
3. **Manual tuning beta** (issue #1): hardware gateway with explicit authorization, safe OPP selections, verified snapshot + restore + conflict handling, session logs.
4. **Odin Tools beta** (issue #2): begin with Home single-press and saturation after firmware validation, then controller and M buttons; charging and vibration later.
5. **AutoPilot beta:** use measured frame quality and load, conservative step size, thermal observation, active app profiles, recovery and comparative benchmarks.
6. **Polish / release:** UI tests, contrast and motion checks, install/update, changelog, offline help, exportable diagnostics and reproducible signed builds.

## External references and compliance
- Original project: https://github.com/locutus5382/O2PKernelTurb
- ClusterTune: https://github.com/AurelioB/ClusterTune (MIT in current versions; respect per-revision terms)
- OdinTools: https://github.com/langerhans/OdinTools (MIT; tested firmware listed in README is Odin 2 1.0.0.288, not an Odin 2 Portal validation)
- Code from third parties must carry applicable license and attribution; prefer independent implementation based on documented behavior.

## Ship criteria
- Never show success for empty/failed privileged replies.
- No unverified writes on device.
- All controls recoverable or clearly labeled irreversible/unsupported.
- Read-only and stock states function without PServer/root.
- CI green before distributing debug APK.
