# O2P Control Center — PServer, Underclocking and Quick Settings Architecture Review

**Review date:** 9 November 2026  
**Hardware:** AYN Odin 2 Portal Base, Snapdragon 8 Gen 2 / Adreno 740, Android 13  
**Status:** source-code comparison plus physical Portal read-only diagnostics. **No frequency writes were made.**  

## Decision in brief

1. Keep O2P **GPLv3** and **PServer-only**, with no user-root/su feature.
2. Adopt the current **MIT-licensed ClusterTune PServer lifecycle and authenticated host concepts**, retaining its MIT notice, but **not** the optional `su` execution method or unrestricted shell surface.
3. Implement **maximum-frequency underclocking only** at first, with verified exact CPU/GPU operating points and readback. Do **not** blindly adapt CPU minimum protections, governor controls, permission-locking, or fan settings.
4. Prefer ClusterTune's **atomic privileged-host transaction model** to PULSE's generated shell-script writer.
5. Adopt ClusterTune's excellent **Quick Settings Profile Picker**, but implement O2P-specific UI, a prominent **Stock / Disable tuning**, and a separate optional **Restore Stock** tile.
6. Gate the actual writer on authenticated PServer host handoff, verified stock maximum, effective minimum, GPU level mapping, OEM mode coordination, rollback, and physical-Portal testing. Until then, only read-only discovery and profile preview work.

## First: what "Stock" actually means

Stock = **OEM-permitted maximum CPU/GPU frequency ceilings** for the active OEM performance mode. It is not an instruction to run CPUs constantly at full clock. On the O2P UI, **100% Stock** means no O2P-imposed underclock; turning tuning **OFF** must request OEM ceiling restoration, verify the resulting state, and release O2P ownership. Never offer values above a verified OEM baseline; no overclocking, undervolting or governor overrides.

Linux CPUFreq rejects writing a `scaling_max_freq` smaller than the effective `scaling_min_freq`. The actual Portal minimums were not directly readable in either submitted diagnostic. Until the host can read these, the application **must not attempt a write**, even if a CPU maximum node is visible.

## A. ClusterTune current MIT implementation — actual flow

Source:
- [PServerHostLauncher.kt](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/root/PServerHostLauncher.kt)
- [ClusterTuneHostClient.kt](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/root/host/ClusterTuneHostClient.kt)
- [HostProtocol.kt](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/root/host/HostProtocol.kt)
- [HostFilesystem.kt — HostApplyEngine](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/root/host/HostFilesystem.kt)
- [HostMinimumProtection.kt](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/root/host/HostMinimumProtection.kt)
- [PerformanceRepository.kt](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/data/PerformanceRepository.kt)
- [Execution-methods plan](https://github.com/AurelioB/ClusterTune/blob/main/docs/execution-methods-plan.md)

### PServer and host startup
- Use `ServiceManager.checkService("PServerBinder")` and `isBinderAlive` for **liveness**, not a privileged identity claim.
- The launch-only Binder transaction is code 0 with an int32 count of 2, one UTF-16 shell command and the string `"0"` to disable command-output capture. No interface token; max command length 255 UTF-8 bytes; do **not** retry an ambiguous attempt.
- A dedicated host is started via a bounded application `app_process` command. The client uses nonce-protected handoff / verified Binder identity, generation and method checks, and host liveness. Binder accepting the launcher is **not** proof of host startup or privileged capability. ClusterTune has tested this on the **AYN Thor**; no verification on this Odin 2 Portal yet.
- Its execution resolver also offers a root-shell fallback; **O2P must omit it**.

### Manual underclock transaction
1. Detect `cpufreq/policy*` from the actual system, including available frequency lists, min and max limits, hardware maxima, and the GPU interface.
2. Resolve the selected profile to per-policy CPU caps and optional GPU cap. Validate IDs, actual frequency steps, device identity and limits.
3. Send a typed `APPLY_PROFILE` request to the privileged host, not a free-form user-supplied command. Repository serializes operations.
4. Host preflights **all** domains and captures current caps/modes; writes the needed nodes; reads back actual caps; rolls back if the transaction fails. On lost transport after mutation it can report **indeterminate** instead of claiming a safe success.
5. Stock is a virtual profile/reset request. Host tries a discovered Stock ceiling, then a recognized selectable fallback if necessary, verifying readback; it must not simply set every current clock to 100% constantly.
6. Current ClusterTune also has an optional **minimum-frequency protection** manager: it can lower CPU mins and temporarily protect sysfs permissions to resist the vendor daemon, with original-mode journal/recovery. That feature was physically investigated on Thor only. It is materially different from our **maximum-only v0.6 proposal**.
7. ClusterTune Auto Tune has a separate `maximumsOnly` host path and session expiry, demonstrating that the architecture supports our intended narrower actuator. Its normal fixed-profile path may include minimum protection depending on host capabilities.

**Assessment:** Excellent foundation for O2P after removal of root fallback and optional minimum/permission writer. Requires independent Portal validation and an O2P-owned, small, allowlisted host.

## B. PULSE GPLv2 fork — actual flow

Sources:
- [RootExec.kt](https://github.com/keiretrogaming/pulse/blob/main/app/src/main/java/com/kei/pulse/root/RootExec.kt)
- [PerformanceCommandBuilder.kt](https://github.com/keiretrogaming/pulse/blob/main/app/src/main/java/com/kei/pulse/root/PerformanceCommandBuilder.kt)
- [PerformanceRepository.kt](https://github.com/keiretrogaming/pulse/blob/main/app/src/main/java/com/kei/pulse/data/PerformanceRepository.kt)
- [SystemTuning.kt](https://github.com/keiretrogaming/pulse/blob/main/app/src/main/java/com/kei/pulse/data/SystemTuning.kt)
- [PerformanceTileService.kt](https://github.com/keiretrogaming/pulse/blob/main/app/src/main/java/com/kei/pulse/tile/PerformanceTileService.kt)

### Execution
- Legacy output-enabled PServer Binder transaction with a string-array command and `"1"` output capture; `RootExec` decodes a byte-array reply. O2P's earlier legacy command-output/exit-marker probe **failed** on the physical Portal; do not treat an accepted Binder transaction as verified command execution.
- Manual `PerformanceRepository.applyValuesInternal` builds a shell script and executes it through its command runner. The writer uses `chmod 666`, `echo value > node`, then permissions `444` for an underclock and typically `644` for CPU reset. This is a wider kernel-permission mutation surface.
- PULSE may lower and protect CPU **minimums**, notably the prime cluster, before a maximum cap; this can counteract a vendor minimum floor, but on other clusters may provoke a vendor override.
- For Adreno, it converts a selected GPU frequency into a **KGSL power-level index** (index 0 fastest). It first sets the GPU `min_pwrlevel` to the slowest level, then `max_pwrlevel` to the chosen ceiling, and can leave bounds permission-protected even for a “Max” tier.
- The repository subsequently reads current limits and computes `verificationPassed`, but that is not equivalent to ClusterTune's privileged, whole-profile transactional rollback/journal. Its result may be represented as a successful command even if detailed verification is false unless the caller checks the field.
- PULSE offers percentage-based Power Target and CPU/GPU floors/governors, and supports cycling Max/Balanced/Power Saving/Custom from the Quick Settings tile. These are wider controls than we should enable initially.

**Assessment:** Useful insights on real Adreno power-level mappings and vendor behavior, but the direct command, chmod and min-floor logic should **not** be transplanted to safety-first O2P. The repository declares **GPLv2**; do not copy GPLv2-only implementation into our GPLv3 code.

## C. O2P v0.6 — current state and proposed divergence

- Current read-only Studio already finds three CPU policies, GPU frequencies, and supports editable preview presets/custom profiles. It has **no kernel writer**.
- Actual Portal `policy0` cores 0–2 has a hardware maximum 2,016 MHz, but `scaling_max_freq` is unreadable without elevated access.
- `policy3` cores 3–6 reports 2,803.2 MHz effective max but the available-frequency list tops out at 2,707.2 MHz.
- `policy7` core 7 reports 3,187.2 MHz effective max but the list tops out at 2,956.8 MHz.
- KGSL GPU has 9 listed steps (124.8–680 MHz), `max_pwrlevel=0`, `min_pwrlevel=4`, `num_pwrlevels=9`. GPU frequency-to-index mapping must be verified on this firmware.
- CPU scaling minimums are unreadable in the submitted diagnostics, and PServer privilege isn't authenticated. These mismatches block writable profile application and mean that even a 100% “Stock” placeholder cannot yet be treated as a verified restore target.
- **Current branch change:** adapted ClusterTune's MIT transport into `PServerHostTransport.kt` with the full MIT notice, removed the legacy shell-output bridge, and made normal diagnostics **probe liveness only**. It **does not launch a host** and **does not send a PServer command**. Future host launching is deliberately not connected to any production UI or service. The only new underclock validation is pure Kotlin, not a sysfs writer.
- Proposed writer: maximums-only, no min/governor/thermal/voltage/permissions/fan writes; fail when effective minimum > proposed cap; no automated retries fighting OEM; strict, verified restoration after OFF/Stock selection.

### Engineering trade-off
If stock firmware actively enforces a minimum above the requested maximum, the maximum-only method may fail to provide the desired underclock. In that case **show “blocked by OEM minimum”**. We should not quietly change CPU minimum nodes or their permissions. A future, independent and explicitly approved device-specific assessment could examine a stronger method, but it must not become an automatic hidden fallback.

## D. Direct comparison

| Dimension | ClusterTune | PULSE | O2P proposed |
| --- | --- | --- | --- |
| PServer | Output-disabled launch-only; authenticated host | Legacy command/output shell bridge | ClusterTune-style no-root host |
| Root / su option | Offered as fallback | PServer route used, broader privileged tuning | **Never** |
| Core frequency operation | Per-policy maximum caps; optional min protection | CPU max, optional prime min floor | CPU max only |
| GPU frequency operation | KGSL/devfreq, host-verified | KGSL level-index writes incl. min bound | Max cap only if mapping verified |
| Writes | Typed host request, preflight/readback/rollback | Generated shell script + chmod | Narrow typed allowlist, one transaction |
| Failure behavior | Phase/indeterminate/rollback state | Script output + later readback flag | Fail closed; no false success |
| Stock | Host baseline/reset and permission release | Reset script / tier Max; some GPU permissions retained | Verified OEM max restoration, ownership release |
| User profiles | Bundled + editable user profiles | Tier/power target/custom | Editable templates + user profiles |
| Quick Settings | User-add tile; picker, cycle, compact tuner, HUD | Picker/tier/cycle | Picker first, Stock shortcut, optional favorites cycle |
| Game automation | App assignments and optional Auto Tune | Power-target/AutoTDP extensions | Deferred after safe manual writer |
| Firmware-tested target | Thor; not proof for Portal | Repos document others; not a Portal safety validation | Physical Portal required before writes |

## E. ClusterTune Snapdragon 8 Gen 2 presets — useful comparison, NOT recommendations yet

[QCS8550.json](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/assets/bundled_profiles/QCS8550.json) contains three CPU-only underclock tiers. **All nine CPU values are present in our real Portal's reported available frequency lists**, which makes them useful test candidates *after* host/baseline validation. That does not prove the firmware will honor them, that they work safely in gameplay, or that CPU minimums permit them.

| ClusterTune preset | policy0 (MHz) | policy3 (MHz) | policy7 (MHz) |
| --- | ---: | ---: | ---: |
| Small | 1,785.6 | 2,323.2 | 2,476.8 |
| Medium | 1,555.2 | 2,054.4 | 2,092.8 |
| Large | 1,459.2 | 1,785.6 | 1,843.2 |

As percentages of the separately **reported hardware maxima** 2,016 / 2,803.2 / 3,187.2 MHz (not verified Stock under load), these are:
- Small: 88.6% / 82.9% / 77.7%
- Medium: 77.1% / 73.3% / 65.7%
- Large: 72.4% / 63.7% / 57.8%

The reported ceiling/list mismatch on policies 3 and 7 is why O2P must display **exact MHz first** and “% of verified Stock” only once Stock has been authenticated. GPU is absent from this bundled JSON and needs its own validation.

## F. Quick Settings: what to adopt and improve

ClusterTune uses [TileService](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/tile/PerformanceTileService.kt), its [compact profile picker](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/ui/CompactProfilePickerState.kt), and Android 13's [requestAddTileService](https://github.com/AurelioB/ClusterTune/blob/main/app/src/main/java/com/aure/clustertune/tile/QuickSettingsTilePrompt.kt). Taps can show a floating profile picker, cycle favorites, open the full app or control other views. Its picker depends on Draw Over Other Apps permission for the overlay.

Recommended O2P design:
- One Android **O2P Profile** user-addable tile in the Quick Settings shade, showing an **actually verified** current status: Stock, a profile name, Pending, Unknown, or Restore failed. Do not confuse saved selection with applied hardware state.
- Tap opens a compact native profile picker (or overlay fallback where appropriate). **Stock / Tuning OFF** is always first; Light, Moderate, Strong and all saved custom profiles follow. Choosing one submits a single validated request; tile only updates after a verified readback.
- Optional **Restore Stock** second tile for rapid recovery; user may add both to the shade.
- Long press opens Studio/settings; optional favorites-cycle tap mode only after the reliable picker works.
- Android 13 API 33 supports the tile-add prompt. Android 14+ activity launch should use the PendingIntent version of `startActivityAndCollapse`. Native `TileService.showDialog` can be evaluated to reduce dependence on Draw Over Other Apps permissions.
- On locked screens, follow `unlockAndRun`; no mutation while user is locked without explicit safety approval.
- **No tile applies frequencies until the authenticated host, full Stock baseline and rollback are independently validated**.

Relevant Android documentation: https://developer.android.com/develop/ui/views/quicksettings-tiles and https://developer.android.com/reference/android/service/quicksettings/TileService

## G. License and provenance

O2P remains **GNU GPLv3**. ClusterTune's current `main` is MIT, but historical source revisions may have different provenance. The directly adapted PServer launch-only transport carries an MIT SPDX attribution to Aurelio Bernal © 2026 and the full `licenses/ClusterTune-MIT.txt` notice. All additions not adapted from external code retain O2P's existing GPLv3 project context.

PULSE currently declares GPLv2 and is a fork/derivative of older ClusterTune; no PULSE code has been copied. If further copying is contemplated, confirm file-specific licensing rather than assuming repository-wide labels cover every historical revision.

## H. Staged implementation / decisions required

### Gate 0 — Completed in draft branch
- Adapt and test ClusterTune MIT PServer Binder **transport**. A read-only liveness probe must not launch a host.
- Delete the unsupported legacy command-output reader. Keep Direct diagnostics as fallback.
- Add pure Kotlin underclock safety rules rejecting unverified host, stock, firmware, CPU minimum, power-level mapping, unsupported steps and above-Stock targets.
- Build/tests must pass; no sysfs writes or root/su.

### Gate 1 — Next separate PR, device validation required
- Build **one small O2P-specific read-only privileged host** with strict Binder identity and nonce-protected handoff. Only READ_CAPABILITIES, READ_STATE and STOP. Never expose arbitrary commands or sysfs paths.
- A host request must be user-triggered and read-only; verify exact firmware PServer handshake and that host is truly a verified elevated process.
- Compare CPU `scaling_min/max`, `cpuinfo_max`, `time_in_state`, three policy sets and KGSL GPU OPP-to-power-level mapping across AYN modes. The host is **not yet in this build**.
- If host launch fails, show PServer host unsupported and keep all tuning disabled.

### Gate 2 — Future writer PR only after Gate 1 passes
- Add typed `APPLY_LIMITS` / `RESTORE_STOCK`, allowlisted max nodes, time-bounded transaction, preflight, snapshot, readback and reverse rollback.
- On service death or uncertain status, clearly report UNKNOWN / RESTORE UNVERIFIED, rather than claiming Stock. No background auto-reapply.
- Test Stock → small cap → Stock, midpoint failure, service death, reboot, AYN mode changes, repeated rapid taps, conflicting ClusterTune/PULSE installs and CPU min-over-max refusal.

### Gate 3 — Quick Settings and profile UX
- Create native O2P Profile tile and profile picker + optional Stock tile using the **same** authoritative backend, statuses and safety gates; never a separate privilege path.
- Presets editable via saved overrides, Stock immutable, user profiles named/duplicated/exported as previously agreed.
- AutoPilot/per-app automation deliberately later.

**Recommendation:** Go forward with ClusterTune's authenticated host design **and its proven maximum-only transaction path**. Do not copy PULSE's permissive shell scripts, OEM min protection or GPU power-level writes without Portal-specific research. The Quick Settings picker is a high-value interface feature and should be implemented after or in parallel with *read-only* host verification, but not permitted to write before Gate 2.
