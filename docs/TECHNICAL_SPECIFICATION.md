# O2P Control Center — Technical Architecture and Implementation Specification

**Status:** Proposed engineering specification, version 1.0  
**Target:** AYN Odin 2 Portal Base (8 GB RAM), Snapdragon 8 Gen 2 / Adreno 740; stock Android 13 as initial validation target  
**Starting code:** https://github.com/locutus5382/O2PKernelTurb  
**Primary goal:** Improve *sustained* emulation frame pacing per watt, while minimizing avoidable heat and preserving OEM thermal protection.  
**Scope:** Architecture and delivery specification, not a claim that any untested register path or tuning value is supported by the user's firmware.

## 1. Architecture decision and product boundaries

Build a **separate app** (`com.odin2portal.control`, provisional application ID) with a clean Kotlin codebase. Reuse and adapt the current app's PServer discovery technique and Quick Settings concepts, not its unchecked shell-script model. The new app must coexist with the original for rollback, but must detect and warn about active competing CPU/GPU tuning apps; instruct the user to disable other tuners before enabling AutoPilot. Do not silently kill or modify competitors.

**First release features:** hardware capability discovery; read-only live monitoring; baseline snapshot/verified restore; manual CPU/GPU ceiling profiles; safe user-started tuning session; thermal safety; diagnostic export.  
**Later releases:** adaptive governor; per-emulator profiles; optional display overlay; sustained benchmarks; fan control **only after firmware verification**; optional game identification when reliable emulator integration is available.

Non-goals for v1: CPU/GPU overclocking, undervolting, disabling thermald/thermal protection, direct DDR control, custom scheduling affinities, hidden Android API dependencies without compatibility verification, arbitrary command execution, or promises of fixed temperature savings. PerformanceHintManager cannot be used to issue hints for another app's threads through ordinary public APIs; tune the emulated application's host via verified privileged interfaces instead.

## 2. Repository audit and migration

The current repo contains `PServerExec.kt`, `MainActivity.kt`, `BootReceiver.kt`, `TurboTileService.kt`, XML views and a small Gradle/AndroidManifest setup. The reviewed `app/build.gradle` specifies `minSdk 31`, `targetSdk 34`, Kotlin with Java 17, version 1.1. The app currently changes read-ahead, I/O stats, rq_affinity, `kgsl-3d0/idle_timer`, `kgsl-3d0/bus_split`, VM swappiness and proactive compaction.

| Existing behavior | Risk | Required replacement |
|---|---|---|
| `runAsRoot()` returns `OK` for empty Binder output or empty `su` stdout | Success can be false | Typed `ExecutionResult` with exit code or verified readback and explicit `UNKNOWN` |
| `su` path ignores exit status | Failed write can appear successful | Capture exit code, stderr and timeout |
| Shell writes hide errors using `2>/dev/null` | Partial and silent failure | Validate path/value before write; independently verify each write |
| `revertToStock()` uses fixed constants | Incorrect factory assumptions | Session-scoped baseline snapshots, each with provenance and validity |
| Revert omits `bus_split` and `compaction_proactiveness` | Persistent unintended changes | Restore all altered nodes (if still owned by the session) |
| `isTurboActive()` checks two values with OR | Incorrect current state | Per-node `desired` vs `effective` status; partial state explicit |
| Status hardcodes bridge `uid=0` and missing values | Misleading UI | Show `CONNECTED`, `UNAVAILABLE`, `DENIED`, `UNVERIFIED` from actual probes |
| Delayed main-thread handler in `BootReceiver` | Boot work may not execute; double trigger | Idempotent boot reconciliation, no unsafe unconditional background start |
| UI and Quick Settings tile execute writes synchronously | UI blocking, no rollback | Route actions to `SessionManager` + serialized I/O; only display confirmed results |

The current read-ahead = 2048 KB, `vm.swappiness = 10`, `idle_timer = 80`, and related tweaks must be placed behind an **Experimental** section, disabled by default. They are not established universal optimizations. `Stock` must never mean writing guessed default constants such as 512/60/64.

Migration: do not rely on the new app reading the old app's private preferences. Offer manual setup and an explicit export/import format if needed. Keep the old app installed only for reference, with its tuning and boot option disabled before use of the new controller.

## 3. Package/module structure

Recommended initial build: single Android app module with clean package boundaries; split Gradle modules only when tests and dependencies justify the overhead.

```text
app/src/main/java/com/odin2portal/control/
  app/                 ControlApplication.kt, DependencyGraph.kt
  ui/                  MainActivity.kt, dashboard/, profiles/, diagnostics/, settings/
  ui/tiles/            ProfileTileService.kt
  service/             TuningSessionService.kt, BootReceiver.kt, SessionNotification.kt
  domain/model/        Capability.kt, Sample.kt, Profile.kt, ControlAction.kt, Result.kt
  domain/control/      AutoPilotController.kt, PolicyStateMachine.kt, PolicyBounds.kt
  domain/session/      SessionManager.kt, SnapshotManager.kt, RestoreCoordinator.kt
  domain/safety/       SafetySupervisor.kt, ConflictDetector.kt, OwnershipLedger.kt
  data/hardware/       HardwareGateway.kt, PServerTransport.kt, SuTransport.kt
  data/discovery/      SysfsDiscovery.kt, CpuPolicyDiscovery.kt, GpuDiscovery.kt
  data/telemetry/      CpuReader.kt, GpuReader.kt, ThermalReader.kt, FrameReader.kt,
                       MemoryReader.kt, PowerReader.kt, TelemetryRepository.kt
  data/storage/        ProfileRepository.kt, SettingsRepository.kt, SessionDatabase.kt
  data/foreground/     ForegroundAppDetector.kt
  diagnostics/         SupportBundleExporter.kt, BenchmarkRecorder.kt
```

Recommended dependencies: Kotlin, coroutines/StateFlow, Jetpack Compose/Material 3, lifecycle ViewModel, DataStore for settings, Room for sessions and time series, JUnit and MockK/fakes or equivalent. Pin vetted versions in a Gradle version catalog. Prefer no network permission and no analytics; allow manual export of redacted diagnostic files.

**Data flow:**

```text
Compose UI --user intent--> SessionManager --profile+policy--> AutoPilotController
                                        ^                          |
TelemetryRepository --samples----------+--------------------------+
                                        |                          v
                              SafetySupervisor  --->  HardwareGateway
                                                          |
                                                PServerTransport / SuTransport
                                                          |
                                  verified CPU/GPU sysfs nodes, thermal and OEM state
```

The **HardwareGateway** is the sole application component permitted to write kernel settings. All requests are serialized through one coroutine actor/mutex. The supervisor may veto any operation and must be able to request restoration independently of the UI.

## 4. Hardware discovery and privilege design

### 4.1 Privilege transport

Introduce an interface:

```kotlin
interface PrivilegedTransport {
    suspend fun probe(): TransportHealth
    suspend fun execute(script: VerifiedScript, timeoutMs: Long): ExecutionResult
}

data class ExecutionResult(
    val transport: TransportKind,
    val status: ExecutionStatus, // SUCCESS, FAILED, TIMEOUT, UNVERIFIED
    val stdout: String,
    val stderr: String?,
    val exitCode: Int?,
    val durationMs: Long,
    val error: String? = null
)
```

**PServer:** preserve current reflection-based discovery (`ServiceManager.getService("PServerBinder")`) as an isolated adapter. The existing source uses Binder `transact(0)` with two strings and reads a byte array from the reply. Treat this protocol as **firmware-dependent**. Discovery of a Binder does *not* prove that it is UID 0 or that a particular command succeeded. Verify using a harmless identity/read probe and a known writable test only after explicit user consent. Binder outcomes may not provide exit codes: add a controlled shell trailer/marker and rely on readback for any mutating result. Never concatenate user input into a command.

**Root fallback:** use `su -c` only when available and explicitly approved. Execute off the main thread, drain stdout and stderr concurrently, use a bounded timeout, close pipes, terminate timed-out processes, and inspect process exit status. Do not pretend an empty stdout is success. If neither transport is working, run in **Read-only** mode.

Only allow generated operations with explicitly validated canonical paths, enumerated nodes and sanitized numeric values. Do not expose an unrestricted shell console in release builds. A generic `runAsRoot(command: String)` must not be callable by AutoPilot or UI code.

### 4.2 Capability discovery

At each user-authorized tuning session start, enumerate:

- CPU policy directories (`/sys/devices/system/cpu/cpufreq/policy*`), `related_cpus`, `cpuinfo_max_freq`, `scaling_min_freq`, `scaling_max_freq`, `scaling_available_frequencies` when present. Match CPU topology from policy metadata, not assumed policy numbers. Use `time_in_state` or another validated source where available for frequency residency.
- GPU under `/sys/class/kgsl/kgsl-3d0/` and device devfreq paths. Detect actual controls, current frequency, available frequency levels, units, min/max relationship, readable status, and effective governor/owner. Do not assume all Adreno 740 devices expose the same node names or that 680 MHz is always the last frequency step.
- Thermal zones (`type` and `temp`) plus Android `PowerManager` thermal status/headroom. Map descriptive sensor types; never assume `thermal_zone0` is SoC temperature. Preserve raw values, units and sensor labels.
- Fan interfaces and OEM mode, read-only initially. Do not assume `hwmon*/pwm1` controls the Odin's fan; require physical verification and verified write/restore before enabling writes.
- Memory (`/proc/meminfo`, `/proc/pressure/memory` where supported, swap/ZRAM stats, available LMK diagnostic signals); power/battery current/voltage where supported.

For each node record: `path`, `units`, `readable`, `writable`, `allowedRange`/enumeration, `readbackSupported`, `transport`, `sampleTime`, `confidence`, and `whyUnavailable`. Capabilities are never inferred from the model name alone.

### 4.3 Hardware action contract

```kotlin
sealed interface ControlAction {
    data class SetCpuMax(val policyId: String, val khz: Long) : ControlAction
    data class SetGpuMax(val hz: Long) : ControlAction
    data class RestoreNode(val capabilityId: String) : ControlAction
}

data class ApplyOutcome(
    val action: ControlAction,
    val requested: Long?,
    val observed: Long?,
    val status: ApplyStatus, // APPLIED, CLAMPED, NO_CHANGE, REJECTED, FAILED
    val reason: String?,
    val elapsedMs: Long
)
```

Before a write: verify known allowlisted node, session ownership, supported frequency step, physical bounds, and `min <= max`. After a write: read back the node; update UI only with the observed effective result. If OEM software changes it again, report `OVERRIDDEN`; suspend AutoPilot for that node rather than racing the OEM in a rapid write loop.

## 5. Lifecycle, baseline restoration and watchdog

All tuning is **session scoped**. Only one hardware-affecting session may run at a time; all UI surfaces and quick tiles submit requests to the same `SessionManager`.

```text
IDLE → DISCOVERING → SNAPSHOTTING → OBSERVING → TUNING
  ^                                                |
  +--------------- RESTORING <----- PAUSED / ERROR -+
```

Session start:
1. Verify privilege and hardware capability map; check competing tuners and AYN mode conflicts.
2. Read current effective settings **before any write**; persist a snapshot, firmware/build fingerprint and an ownership ledger.
3. Start telemetry in observe-only mode; confirm sensor plausibility and FPS source confidence.
4. Permit tuning only if safety preconditions and restore support are satisfied.

Stop/pause (screen off, emulator exit, privilege loss, critical thermal state, user Stock action): stop controller; cancel queued tuning actions; restore each *owned* node in reverse order; verify readback; log any partial restore failure. Never overwrite a changed OEM value unless the user explicitly requests a deliberate reset and the safe target is known. A missing snapshot must disable tuning, not trigger guessed values.

**Crash recovery:** An ordinary Android foreground service cannot guarantee cleanup after process death. Design and experimentally validate a minimal **independent privileged watchdog** (possible via a constrained PServer/root-launched helper) that holds a recovery snapshot and restores on lost heartbeat, e.g. >15 seconds. Verify that the selected transport actually supports the helper's lifecycle. If unavailable, label guaranteed post-crash restoration as **unsupported**, use limited session durations and opportunistic restoration on next launch, and explain that reboot typically resets transient sysfs settings. Never falsely claim that a coroutine `finally` block covers force-kills. Persist watchdog state privately, with no world-writable command files.

**Boot policy:** v1 does not automatically reapply tuning after boot. `BootReceiver` should reconcile stale session metadata after unlock, with action de-duplication, and optionally show an opt-in prompt to resume. Do not use a delayed `Handler` or start an ineligible foreground service from boot. Any later auto-start feature must satisfy current Android background/foreground-service policy and OEM testing.

**Android service:** A user-started foreground service may coordinate long gaming sessions. Choose a legitimate, documented foreground-service type (likely `specialUse`, subject to Android/platform/Play approval), declare corresponding permissions and explain the user-visible notification. Avoid using a falsely labeled `dataSync`, `mediaPlayback`, or `systemExempted` service. Preserve an explicit Stop/Restore action in its notification. Handle permission denial and screen-off behavior gracefully.

## 6. Telemetry specification

| Source | Initial cadence | Reliability policy |
|---|---:|---|
| CPU utilization from delta `/proc/stat` | 1 second | Differentiate busy% from clock and capacity; report unsupported if inaccessible |
| CPU current frequency/max ceiling | 1 second | Read effective values, distinguish real core frequency from policy ceiling |
| GPU busy/frequency | 1 second | Validate format and timebase; support unavailable/malformed responses |
| Temperature zone readings | 1–2 seconds | Type/units mapping; reject implausible values; maintain 5–10 s EMA and slope |
| Android thermal status | Event/callback + fallback | Thermal status is authoritative safety signal, not proof of specific die temp |
| Thermal headroom | 5–10 seconds initially | Handle NaN/unsupported/rate limits and never use alone to claim temp |
| SurfaceFlinger game layer FPS | 2–5 seconds | Treat as presentation cadence, not GPU render duration; identify actual game layer |
| RAM, swap/ZRAM, PSI | 2–5 seconds | Flags for pressure, not arbitrary swappiness tuning |
| Battery current, voltage, charging | 5 seconds | Treat as optional/noisy; handle sign and unit conventions |

Use bounded ring buffers (e.g. last 2 minutes of 1-second samples) and downsample longer sessions into Room; avoid keeping detailed raw `dumpsys` output indefinitely. Use `SystemClock.elapsedRealtimeNanos()`/monotonic timestamps for elapsed timing. Record sensor provenance and confidence with each series. Mark gaps explicitly; never substitute a hardcoded nominal reading.

### Frame pacing and emulator identification

SurfaceFlinger `--latency` / `--timestats` expose *presentation timing*, not the CPU/GPU work time. Identify an actual game layer on the relevant display; do not mistake emulator UI or refresh frequency for rendered game FPS. For a 120 Hz panel, a stable 30 FPS game may present every fourth refresh. Compute per-layer observed FPS, miss ratio, P95/P99 presentation interval, and confidence. Distinguish 30 FPS targets (33.33 ms) and 60 FPS targets (16.67 ms). If layer identification or permission fails, **do not run FPS-based automatic lowering**; provide a manual/telemetry-only fallback.

Automatic foreground-app recognition may use a user-approved Usage Access path or a verified privileged process/task observer; scope detection to authorized emulator packages. Detect emulator process and package; a specific Switch title running inside Eden is not automatically discoverable. Provide manual profile names for individual titles until a dependable emulator-specific integration exists.

## 7. AutoPilot algorithm (v1)

### 7.1 Optimization objective

Primary: maintain requested target frame rate and stable P95/P99 presentation intervals over a sustained session. Secondary: minimize thermal load, battery drain and fan noise. Never chase a specific silicon temperature such as 52–58°C unless calibrated against a real sensor and OEM behavior. Require the OEM's thermal protection to remain active.

**Modes:**

| Mode | Behavior | Frame-margin policy |
|---|---|---|
| Cool | Prefer lowering heat/power; accept a documented, user-defined FPS tradeoff | More aggressive exploration, slower recovery allowed |
| Balanced | Meet target FPS with energy-saving adjustments and safety headroom | Moderate exploration; fast recovery |
| Performance | Preserve broad CPU/GPU boost headroom; thermal safety still active | Minimal exploration |
| Manual | Use user-validated ceilings, no governor changes | User-controlled |
| Stock | No app-owned performance modification | OEM defaults/other controllers only |

### 7.2 Control states

```text
OBSERVE → STABLE → PROBE_LOWER → VALIDATE → STABLE
                 ↘             ↘
                  RECOVER ← DEGRADATION
Any state → THERMAL_GUARD / SENSOR_LOSS / CONFLICT → PAUSE or RESTORE
```

Suggested initial *tunable defaults* (not hardware constants):

- 10–20 seconds observe-only warm-up; do not adjust during app/game loading or rapid foreground changes.
- Sampling: 1 Hz cheap CPU/GPU/thermal, 0.2–0.5 Hz relatively costly FPS; controller decision every 3 seconds, with independent faster safety event callbacks.
- To lower: require 3 successive trustworthy stable windows with observed FPS >= target − 1 FPS, low tail stutter and no emerging memory pressure; lower **one** applicable ceiling by **one discovered OPP step**; hold at least 8–12 seconds to validate.
- To recover: if 2 successive windows miss frame goals, a sustained spike in CPU/GPU busy occurs, or recent change measurably worsens P95/pacing, restore the last known-good ceiling promptly; set 10–20-second cooldown before another lowering probe.
- A single missed frame during shader compilation should not trigger a permanent boost. Likewise one idle menu frame should not justify aggressive limits. Use hysteresis and rate limits.
- Store `lastKnownGoodEnvelope`, `trialEnvelope`, previous metrics, confidence, cooldown timers and source reason codes.
- Never raise a ceiling above the session's validated OEM/user-selected maximum envelope. Never override an OEM thermal clamp. If actual FPS cannot meet target at available limits, report **target unattainable** rather than silently enabling unsafe behavior.

**Bottleneck classification (heuristic, not absolute):**

- **GPU-bound candidate:** GPU busy sustained high, FPS below target, CPU headroom available. Hold/restore GPU ceiling; avoid lowering GPU.
- **CPU-bound candidate:** high prime/performance-cluster load or run-queue pressure with non-saturated GPU and missed FPS. Hold/restore relevant CPU ceilings.
- **Frame-capped candidate:** stable target FPS, both resource classes with measurable headroom. Probe a single ceiling conservatively.
- **Shader/memory disturbance candidate:** isolated hitches coincident with high memory pressure or load spikes. Avoid oscillation; annotate data.
- **Unknown:** weak FPS visibility or inconsistent counters. Do not perform closed-loop tuning based on unsupported assumptions.

Do not hardcode a threshold such as `GPU 78% => instantly jump to 680MHz`. No app-level timer can rescue a frame deadline already missed, and sysfs writes do not produce deterministic sub-millisecond boosting.

### 7.3 Reference policy pseudocode

```kotlin
fun decide(s: MetricsWindow, st: ControllerState, p: Profile): Decision {
    if (!s.isSafe || s.hasOemThermalClamp || s.privilegeLost) return PauseAndRestore
    if (!s.frameSourceTrusted) return HoldAndReport("No trusted FPS signal")
    if (s.memoryPressureSevere || s.externalTunerConflict) return HoldAndReport("Uncertain bottleneck")
    if (s.fpsMissPersisted || s.tailLatencyRegressed) {
        return RestoreLastGoodAndCooldown
    }
    if (st.inCooldown || !s.stableFor(requiredStableWindows = 3)) return Hold
    val axis = chooseSingleAxisToProbe(s, st, p) ?: return Hold
    val next = nextLowerSupportedOpp(axis, st.lastGood, p.bounds) ?: return Hold
    return ProbeOneStep(axis, next, validationHoldSeconds = 10)
}
```

A separate **SafetySupervisor** runs before and after the controller and wins over its decisions. Manual mode also remains subject to write bounds, OEM thermal safety and restore requirements.

## 8. Thermal and fan strategy

Use mapped thermal sensors with a calibrated, per-sensor interpretation; report sensor type alongside °C. Compute temperature EMA and a smoothed derivative for *trend detection*, not prediction with guaranteed lead time. Android's current thermal status, credible thermal headroom and rapid rises can trigger cautionary mode transitions. Severe thermal events suspend exploratory tuning and release non-OEM caps according to tested safety policy; never suppress OEM throttling.

Fan control should have a **capability gate**. Start with `AYN_AUTO` / `OEM_SMART` and no direct fan writes. Only implement alternate fan curves when the actual Odin 2 Portal firmware exposes a confirmed and reversible control, RPM/feedback is available or physical response is verified, and the operating mode does not fight the AYN fan controller. A smooth monotonic temperature curve with hysteresis and minimum safe floor is safer as a first implementation than arbitrary PID gains. Include sensor-loss fail-safe to OEM fan control. No silent fan stopping.

## 9. Profiles and persistence

A profile is a declarative policy, not a list of shell commands. Example schema (units explicit):

```json
{
  "schemaVersion": 1,
  "id": "eden-balanced-30",
  "name": "Eden · Balanced 30",
  "packageName": "<verified emulator package>",
  "gameLabel": null,
  "mode": "BALANCED",
  "targetFps": 30,
  "cpuMaxPolicy": "AUTO_DISCOVERED",
  "gpuMaxPolicy": "AUTO_DISCOVERED",
  "fanPolicy": "OEM_SMART",
  "allowExperimentalKernelTweaks": false,
  "autoStartOnForeground": false
}
```

Do not save guessed sysfs paths or absolute unsupported MHz values in portable profiles. Save normalized policy preferences, with device capability map resolved at apply time. Require confirmation when imported data requests settings outside the active firmware's capabilities. Provide profiles for emulators (Eden, Android native, PS2, GameCube/Wii, Winlator) by package only when detected or explicitly provided. Allow manual title tags; never claim to automatically recognize internal ROM titles without proven integration. Do not modify emulator resolution, driver or shader settings unless the emulator provides a documented, safe API or user-initiated configuration export/import.

Suggested Room tables: `DeviceFingerprint`, `HardwareCapability`, `Session`, `MetricAggregate`, `ControlEvent`, `RestoreEvent`, `BenchmarkRun`, `Profile`. DataStore handles small user settings; keep original snapshots in an integrity-checked persistent store before any writes. Export JSON + CSV, redacting identifiers and paths where privacy-sensitive.

## 10. UI and user experience

**Dashboard:** current session and mode; actual game FPS with confidence badge, CPU/GPU utilization/clocks, mapped sensor temperatures, power/battery if supported; active profile; AutoPilot status and decision reason; prominent **Stop & Restore** action.

**Profiles:** Stock, Cool, Balanced, Performance and Manual; emulator packages; rules for session start/stop; manual ceilings constrained to discovered OPP steps.

**Monitoring:** per-sensor charts, frame pacing, sustained averages, recent control decisions, memory pressure. When a metric is unknown, display `Unavailable` and a reason, not a fictional value.

**Advanced:** CPU policies; supported GPU controls; fan capability readout; OEM performance-mode interactions; experimental kernel tweaks with opt-in warnings; diagnostics support bundle.

**Safety & Status:** transport health (`PServer verified`, `su verified`, `Read-only`), detected conflicts, snapshot state, last restore status, watchdog support, and whether changes were overridden by AYN's control service.

**Quick Settings tile:** cycle Stop/Stock → Balanced → Performance only while a validated session can be controlled. Delegate to `SessionManager`; no direct kernel command in tile callback. Reflect actual state and `PARTIAL_RESTORE` errors.

**Notifications:** show active tuning, selected profile and Stop & Restore. No permanent notification or persistent privileged writes merely for displaying the dashboard.

## 11. Security, reliability and lifecycle requirements

1. No overclocking, undervolting, thermal-service disabling, direct arbitrary command execution or changes outside a strict allowlist.
2. No static temperature target framed as universally safe; no hardcoded CPU/GPU OPP lists or hardcoded Stock values.
3. Exclusive session ownership, serialized writes, and immutable original snapshots.
4. Read-before-write and read-after-write for every accepted action, including clear status for OEM override.
5. Bounded timeouts for privileged operations, retries only for demonstrably transient failures, and rate limits for writes.
6. Handle privilege revocation, display changes, device sleep, app crash, emulator exit and firmware upgrades.
7. On firmware/build fingerprint change: disable prior device-specific write capabilities until rediscovery/verification.
8. Read-only mode remains useful without PServer or root; never falsely indicate UID 0.
9. Keep tuning disabled at boot in v1. A foreground session must be explicitly user started and meet Android FGS restrictions.
10. No collection/upload of app names, game titles or metrics without explicit user action; support offline operation.

## 12. Benchmark methodology and acceptance tests

**Baseline design:** compare (A) Odin stock, (B) ClusterTune manually configured, (C) ClusterTune Auto Tune, (D) legacy O2PKernelTurb alone, and (E) new O2P AutoPilot, running **one tuner at a time**. Use the same game scene, emulator build, GPU driver, rendering scale, display mode, AYN performance/fan mode, charging state and approximately equivalent starting temperatures. Include at least three reproducible runs per configuration, each long enough (e.g. 20–30 min) to capture sustained thermal behavior. Randomize run order where practical. Cold-start and shader-cache-warmed runs are separate categories.

Report FPS mean/median, 1% low where reliable, P95/P99 presented-frame interval, percentage of frames beyond deadline, CPU/GPU clock residence, sensor-labeled temperature trajectory, OEM thermal-status events, power/battery estimates with confidence, memory pressure and crashes. Do not interpret a one-off improvement as causality.

**Safety acceptance:** no unsupported writes; no false success; successful readback after writes; full owned-node restore across normal stop; correct partial restoration error; no reapplication at boot by default; forced process death tested with and without watchdog; any feature requiring crash-proof rollback remains disabled if watchdog reliability is not proven.

**Performance acceptance (provisional, measured rather than promised):** AutoPilot mean FPS at least 98% of stock within repeatability; P95 frame pacing not worse by >5%; median sensor temperature ideally 2°C lower on meaningful warmed-up runs, or comparable temperature with less energy/fan noise; average tuning overhead below 1% CPU with no material stutter. If savings cannot be achieved without violating the frame-pacing constraint, report 'No safe improvement found' and keep baseline.

**Test matrix:** PServer absent / denied / malformed / slow; root fallback absent; broken symlink; read-only sysfs; unit mismatch; missing GPU utilization; missing thermal type; headroom NaN; SurfaceFlinger unavailable or wrong display layer; game switching; screen off; update firmware; OEM mode changed; competitor running; process killed by OS; device reboot; battery charging/unplugging; system under high memory pressure. Include automated unit tests with fake HardwareGateway and property-based invariants for controller decisions.

## 13. Build milestones, test gates and delivery order

| Milestone | Deliverable | Gate to proceed |
|---|---|---|
| M0 — Clean project | Android Studio project, package boundaries, CI, Compose shell, offline/no writes | Builds reproducibly; tests pass |
| M1 — Safe hardware layer | PServer adapter, `su` fallback, discover/read/write/readback, capability table | Read-only works and all failures explicit |
| M2 — Session/restore | Snapshot, session ownership, Stop & Restore, logs, watchdog feasibility test | Tested recovery, no hardcoded Stock |
| M3 — Monitoring | CPU/GPU/thermal/memory/FPS reader, confidence, charts | No fabricated metrics, controlled overhead |
| M4 — Manual profiles | Stock/Cool/Balanced/Performance bounded by discovered frequencies | Stable sustained manual tests |
| M5 — AutoPilot beta | State machine, one-OPP probes, hysteresis, recovery, conflict handling | Reproducible A/B tests vs stock/ClusterTune |
| M6 — Expanded controls | Emulator-package automation, overlay, verified fan integration, optional optimizer | Each feature independently validated |

The first implementation request to the coding agent should be **only M0 + M1 read-only**. No auto-tuning and no fan/sysfs writes until the interface, discovery, validation and tests exist. Keep PRs small and demand device-readable logs that prove each function.

## 14. Example developer implementation tickets

**Ticket 1 — Baseline audit:** tag the original repo version, document current PServer transaction and Android version, create clean new project/application ID, add static analysis and unit-test workflow. No behavior changes to the old app.

**Ticket 2 — Read-only transport:** implement `PrivilegedTransport`, PServer/su health detection and `ExecutionResult`. Write fake-provider tests for success, empty output, refusal, transport timeout, malformed Binder reply and root denial. Verify no UI string says `CONNECTED (uid=0)` without proof.

**Ticket 3 — Capability discovery:** read CPU policies, GPU frequency nodes and thermal zones; collect unit/OPP metadata and schema; export redacted device-capabilities JSON. No frequency/fan/VM writes.

**Ticket 4 — Snapshot and verified write:** implement single-node bounded CPU cap change with user confirmation, readback and restore. Test restricted path, unsupported frequency, failure mid-transaction, competing OEM writer, and missing snapshot. Add emergency Restore action. Do not enable in general release until all tests pass.

**Ticket 5 — Dashboard and monitor:** compose charts and confidence states; add thermal/headroom/memory frame-interval readers with mock data unit tests. No fabricated sample substitution.

**Ticket 6 — AutoPilot simulator:** implement controller state machine against synthetic 30/60 FPS datasets and recorded telemetry, with property tests that upper bounds are never exceeded and uncertain sensor data cannot trigger tuning.

**Ticket 7 — Device beta:** user-started foreground session; run on real Odin firmware; compare against stock/ClusterTune, log measurable improvements and regressions; release with tuning default OFF.

## 15. Open hardware questions that need a diagnostic capture on the actual Odin

Before shipping adaptive writes, capture: `getprop` build fingerprint/Android version; CPU policy directories and frequency tables; Adreno KGSL and GPU devfreq status; thermal zone `type` and temperature values; PServer harmless probe results (not assumed UID); AYN fan/performance mode controls; working SurfaceFlinger game layer on your installed Eden build; Android thermal-headroom support; memory PSI and battery-current support. Request user permission and provide a **read-only** diagnostics function. Do not assume stock firmware and custom ROMs are equivalent.

## 16. Primary references

- Starting app and code: https://github.com/locutus5382/O2PKernelTurb
- PServer implementation: https://github.com/locutus5382/O2PKernelTurb/blob/main/app/src/main/java/com/odin2portal/turbo/PServerExec.kt
- Current boot receiver: https://github.com/locutus5382/O2PKernelTurb/blob/main/app/src/main/java/com/odin2portal/turbo/BootReceiver.kt
- ClusterTune features and compatibility: https://github.com/AurelioB/ClusterTune
- Android SurfaceFlinger slow sessions/timestats: https://developer.android.com/topic/performance/issues/slow-session
- Android thermal API: https://developer.android.com/games/optimize/adpf/thermal
- Android foreground-service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android Performance Hint API rationale: https://source.android.com/docs/core/perf/performance-hint-api

**Final design principle:** The app must prove that it has improved a repeatable workload; if measurements are unavailable or improvements are unreliable, it must preserve stock behavior instead of guessing.
