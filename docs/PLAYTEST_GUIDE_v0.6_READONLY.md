# Manual Performance Studio — v0.6 Alpha 1 (read-only)

This is **not a tuning release**. It cannot underclock the Odin 2 Portal yet. Its goal is to establish genuine supported frequency tables and validate the profile experience before any privileged writer exists.

## Inspecting the Studio

1. Install the latest successful debug APK built from `feature/v06-readonly-studio`.
2. Open **Studio** in the left-hand navigation on the Portal's landscape screen.
3. Choose **Refresh Direct**. The editor enumerates read-only CPU and GPU frequency tables available to an ordinary Android app. If a complete table cannot be read, that domain will not have a slider.
4. Optionally choose **Probe PServer** to run the existing *read-only* privilege probe and capture. A failed output-marker probe **does not prove** PServer is absent: we need a separate authenticated host handshake before enabling write access.
5. Inspect Stock / Light / Moderate / Strong profiles, select a non-Stock preset, move the discrete sliders, save changes or reset the preset to its original preview.
6. Name and save a Custom profile, edit it and confirm it survives closing and reopening O2P. The stored profile belongs to the device's current firmware fingerprint and is rejected if the policy list or firmware changes incompatibly.
7. Note the disabled **Enable tuning** and **Apply** controls: they are disabled intentionally until PServer access and safe Stock restoration are hardware verified. No Apply button should work.
8. Export the Diagnostics report to us so we can compare actual `scaling_available_frequencies`, `scaling_max_freq`, `cpuinfo_max_freq`, `gpu_available_frequencies`, `max_pwrlevel` and other exposed nodes.

## Important interpretation

- **Observed ceiling** is the current reported maximum from the firmware. It is a *candidate baseline*, **not** a certified OEM Stock value. AYN mode and other apps may change it.
- The frequency slider selects only observed exact available steps. Percentages are approximate descriptions of the selected step relative to the observed ceiling.
- The built-in templates (95%, 85%, 75% requested ceilings) are **read-only illustrations**, not benchmarked safe presets or performance guarantees.
- The currently saved profile is only a **desired configuration**; it is never claimed to be applied to hardware.
- Root/su execution was removed from the v0.6 branch. PServer is a powerful firmware-provided privileged service; do not equate it with a normal unprivileged API.

## Next gates before underclocking can ever be enabled

- An authenticated, bounded PServer host with an explicit read-only handshake on the physical Odin 2 Portal
- Independent verification of exact CPU/GPU OPP tables, device firmware, stock ceiling for current OEM mode and any vendor floor
- Typed, allowlisted frequency-ceiling requests with validated read-back, rollback, persistent journal and Stock restore
- Failure tests, app/service death tests, AYN performance-mode changes and first-party device verification
- Separate Quick Settings tile implementation sharing the same verified writer (no bypass)

All work remains on draft development branches; the main release and v0.5 are preserved.
