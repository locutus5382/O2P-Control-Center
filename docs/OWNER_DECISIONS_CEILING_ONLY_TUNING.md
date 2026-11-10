# O2P Control Center — Owner decisions: CPU ceiling-only tuning and useful UI

Decision recorded: 2026-11-10.
Applies to development after v0.9 Alpha 1; does **not** authorize enabling unverified writes or claim on-device testing.

## Product boundary: firmware-respecting, maximum-only underclocking

**Owner objective:** never overclock or exceed the maximum exposed by the Portal's current OEM performance mode, keep the system's observed minimums, and let the native DVFS governor adjust actual frequencies. Use only detected, actual hardware operating points. Do not copy competing utilities' system tweaks unless they solve an independently demonstrated problem.

1. Discover CPU policy topology from `related_cpus`; don't pretend each CPU has an independently controlled max when multiple CPUs share `policyN`.
2. Read hardware capability maximum, effective `scaling_max_freq`, `scaling_min_freq`, actual supported OPPs, and firmware fingerprint through the authenticated PServer host at **every application attempt**. Establish a trustworthy no-other-tuner/session provenance for the OEM-mode baseline and preserve the exact original limit for restoration.
3. **The application must not write `scaling_min_freq`**, change its file permissions, reduce voltage, select other governors, lock frequencies or alter thermal protections. A current min is a constraint, not a target for a write.
4. Only write discovered, allowlisted `policyN/scaling_max_freq` ceilings. Every requested cap must be an independently confirmed supported OPP, >= current effective min, and <= the verified session/OEM maximum. 100% is a **special Stock/Restore** state, not an interpolated OPP. Restore exact captured original max, including firmware-exposed boost maxima not present in `scaling_available_frequencies`, only when host verifies that restoring it is permitted.
5. Investigate `scaling_boost_frequencies` where present; `cpuinfo_max_freq` is an upper hardware report, not by itself proof that a missing intermediate frequency is a writable operating point. No imaginary step interpolation.
6. Preserve default OEM thermal, scheduler, DVFS and power-management decisions. If minimum votes rise above selected caps or firmware overrides a cap, report blocked/overridden, stop or restore as appropriate; **never silently lower mins or chmod nodes to fight the vendor**.
7. Apply via typed, serialized authenticated host operation with preflight, snapshot, exact post-write readback, bounded rollback, an ownership-aware host-side lease/watchdog and a durable crash-recovery plan tested on the Portal. Don't force 100ms real-time guarantees, no unconditional takeover of external tuners, no background/boot persistence without a separately tested design.
8. GPU follows the same maximum-only concept, **but** KGSL GPU max and power-level index mapping must be physically proven before enabling. Do not assume GPU `devfreq` and `max_gpuclk` are interchangeable independent writes.

## CPU cluster strategy (prototypes for measurement, not validated performance gains)

Portal host evidence: policy0 CPUs 0–2 effective min 902400 kHz, observed max 2016000; policy3 CPUs 3–6 effective min 1651200, max 2803200; policy7 CPU 7 effective min 1843200, max 3187200. The two highest reported ceilings (2803200, 3187200) are above the normal listed `scaling_available_frequencies` but were **observed** as effective maxima by UID0 host. Treat those as baseline restore targets and probe extra boost frequency evidence.

Policy principle: start gently on the 4-core performance cluster; preserve the single prime core's single-thread burst headroom; keep efficiency cores mostly untouched initially. The OS scheduler picks actual tasks and governor selects actual clock; O2P only limits the ceiling per CPU policy. No universally correct fixed proportions: test Eden steady frame times and battery discharge plus one other emulator. Evaluate one cluster at a time, starting with a mild performance-cluster cap, and A/B against Stock. Per-app overrides allowed only after manual backend verified.

| Candidate | policy0 kHz | policy3 kHz | policy7 kHz | GPU |
|---|---:|---:|---:|---|
| Stock / 100% | restore captured exact 2016000 | restore captured exact 2803200 | restore captured exact 3187200 | OEM original |
| Light | 2016000 | 2592000 | 2956800 | OEM original |
| Balanced | 1900800 | 2457600 | 2841600 | OEM original |
| Cool | 1785600 | 2323200 | 2726400 | OEM original |

All candidate non-Stock CPU frequencies above appear in the observed Portal OPP tables and exceed the measured current policy minimums; they are illustrative, not proven optimal or guaranteed writable. Dynamic-min spikes and other constraints still require live preflight. The profiles are initial test hypotheses, not hardcoded values for unknown firmware.

- **Easy:** Stock / Light / Balanced / Cool profiles, built from discovered OPPs, not arbitrary MHz.
- **Pro:** separate discrete OPP slider for each CPU **policy** and, later, GPU; display exact MHz. Values between steps are not fabricated.
- CPU reductions need not be proportionally identical across clusters. If measured frame pacing regresses, back off that cluster rather than automatically increasing all other clocks.
- Battery energy savings are not guaranteed: longer active runtime can negate lower peak instantaneous power.
- For adaptation, prefer trustworthy frame timing and thermal veto; load-only heuristics are optional experimental efficiency mode, never claimed as FPS-target compliance.

## Quick access, app automation, monitoring

- **Quick Settings remains the primary system-wide shortcut** and there is one authoritative profile/state controller. Profile status must distinguish selected from actually applied. Keep a prominent Stock/Restore action.
- **Small optional in-game HUD expansion**, not a separate large copy of the app or AYN sidebar. First fix HUD staying visible over Eden. When the user taps its existing pill, offer a compact picker for Stock/Light/Balanced/Cool/Auto (only enabled when validated), plus a concise CPU/GPU/battery readout. Explicitly close and return focus to the game. Controller D-pad interactions are optional, testable behavior; do not globally intercept gamepad input or claim an overlay never interrupts a game.
- **Per-app automatic profile selection** after manual tuning and watchdog pass; explicit opt-in, User -> one profile per detected package -> fallback Stock, no false success and no background session outside user authorization.
- FPS from privileged SurfaceFlinger only if tested and correctly labeled for presentation cadence vs emulator game FPS; battery W from battery-side current × voltage when accessible, not exact SoC power.
- Suspend opt-in active telemetry polling on screen off and restore any O2P-owned temporary tuning where session rules require. Do not claim literally zero background power or guaranteed 300 ms wake reapply.

## Explicit exclusions confirmed by owner

- No dynamic joystick RGB or stick LED effects.
- No night-time volume adjustment: O2P Tweaks already covers that need.
- No storage readahead / iostats booster: theoretical benefit without strong evidence, unnecessary compatibility risk.
- No automatic page-cache/RAM cleaner or `drop_caches` on emulator startup.
- No duplicated AYN OEM fan, controller, trigger, vibration or charging separation UI.
- Do not add minimum-frequency-permission protection by default. That mechanism modifies mins/permissions and violates the agreed maximum-only policy. Reconsider only if the owner explicitly changes the scope and separate hardware safety evidence justifies it.

## Next build sequence

1. Validate v0.9 UI on Portal, preserve known-good debug APK/test signing.
2. On **new feature branch** implement authenticated typed host write + lease/rollback foundation, boost-frequency reconciliation, and exactly **one** reversible CPU max-ceiling change. Test Stock restoration on the Portal before allowing several policy writes.
3. Expand sequentially to three CPU policies, GPU only after mapping proof, measured profile tuning, then Quick Settings and per-app switching.
4. Fix Eden HUD visibility first, then add **optional compact pill-to-picker** (not large Quick Access Bar).
5. Add privileged FPS evaluation, battery discharge telemetry and later fully validated AutoPilot.

This file is a planning/acceptance contract; there are **no code changes** in this decision.
