# Odin 2 Portal Base / Snapdragon 8 Gen 2 — engineering reference

**Target device:** AYN Odin 2 Portal Base, 8 GB. **Purpose:** factual reference and *read-only* discovery plan for O2P Control Center. This is **not** a safe kernel-write configuration.

## Confirmed product specifications

| Component | Base model |
|---|---|
| Processor | Qualcomm Snapdragon 8 Gen 2 / SM8550, 4 nm |
| CPU | 1 × Cortex-X3 at advertised up to 3.2 GHz; 2 × Cortex-A715 up to 2.8 GHz; 2 × Cortex-A710 up to 2.8 GHz; 3 × Cortex-A510 up to 2.0 GHz |
| GPU | Qualcomm Adreno 740; product/spec databases often quote 680 MHz (not a verified full OPP table) |
| RAM | 8 GB LPDDR5X |
| Internal storage | 128 GB (Base); expandable via microSD |
| Display | 7-inch OLED, 1920 × 1080, 16:9, up to 120 Hz |
| OS | Android 13 originally |
| Battery / charging | 8,000 mAh; nominal maximum charging quoted around 27 W |
| Cooling | Active fan; a tachometer-readable RPM node is *not* guaranteed |
| Connectivity | Wi-Fi 7, Bluetooth 5.3, USB-C display output |

**Evidence:** AYN product specifications reproduced in device manuals and contemporary launch/review sources. Qualcomm's platform page uses the **up to 3.36 GHz** speed for some Snapdragon 8 Gen 2 variants; that does **not** override the Portal's advertised 3.2 GHz or kernel-reported actual limits.

- Qualcomm (official): https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-2-mobile-platform
- Device manual mirror: https://device.report/m/b8d3b892e9eae08fbe03d62854ab14e2af1f92420f82a7443648fa8a1c01e600
- Odin Portal launch spec: https://retrohandhelds.gg/ayn-odin-2-portal-announced-with-oled-and-hall-sticks/
- Independent specs: https://www.handhelds.wiki/Ayn_Odin_2_Portal

## Why a GPU clock of 680 MHz is not a tuning table

The published GPU peak is one nominal reference number. Real Qualcomm KGSL `devfreq` nodes can report a set of distinct supported operating points (OPPs), governed by firmware and runtime limits. The actual Portal may report clocks different from the published peak or different GPU clock reporting nodes.

Before attempting manual tuning, read **actual available frequency lists, hardware min/max and scaling caps** from each device node; check the correct units (Hz vs kHz vs MHz) and verify the active firmware. The Snapdragon 8 Gen 2 can be marketed as up to 3.36 GHz on some variants, but this is no permission to set an Odin 2 Portal clock to 3.36 GHz.

## Observed on our Portal test reports (device-specific, not generic specifications)

- Three cpufreq policy groups were reported in the diagnostic export, commonly labeled policy0, policy3 and policy7. Their relationship to groups of cores must be verified through the corresponding `related_cpus` property rather than relying on labels.
- Multiple named CPU thermal zones (e.g. `cpu-*` and `cpuss-*`) and GPU thermal zones (`gpu-*`, `gpuss-*`). The UI presents one clearly labeled **highest named sensor** per group; do not treat it as a calibrated die hotspot or average.
- GPU telemetry has appeared through Qualcomm KGSL sysfs: live GPU clock in MHz and busy percentage. Samples such as **550 MHz** represent *observed current clocks*, not absolute hardware maxima.
- Privileged PServer access was not reliably verified in the earlier report; read-only direct telemetry is the only currently validated access path.
- Fan RPM needs a real tachometer (`fan*_input`). PWM or AYN fan mode is not measured RPM.

## Hardware-safe discovery

The O2P reader should collect, with no writes:

1. `/sys/devices/system/cpu/cpufreq/policy*/related_cpus`, `cpuinfo_min_freq`, `cpuinfo_max_freq`, `scaling_available_frequencies`, `scaling_cur_freq`, `scaling_max_freq`.
2. `/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies`, `cur_freq`, `gpuclk`, `gpu_busy_percentage`, `gpubusy` as exposed and readable. Interpret nodes individually and verify units.
3. `/sys/class/thermal/thermal_zone*/type,temp` with names, indexes, provenance and invalid values flagged; battery via Android BatteryManager.
4. `/proc/meminfo`, `/proc/stat` for system RAM and CPU load.
5. App foreground package via explicit Usage Access, with source and permission state; a detected app is not a detected emulator game.
6. FPS via verified per-layer display timing only, not display refresh-rate assumptions.

**Safety rules:** Never disable thermal mitigation, write CPU/GPU frequencies, override governor limits or change charging/fan settings based solely on a marketing spec. Obtain on-device OPPs first, establish valid elevated access, snapshot old effective settings and make restoration reliable. Overlay monitoring remains read-only.

## UI implications

- Main cards: **CPU, GPU, battery, system RAM**, one summary each.
- Diagnostics: the full CPU policies and thermal zones.
- Manual Performance Studio (future): verified frequency steps per actual policy, source and units, no guessed slider.
- HUD v0.5: reuse the same telemetry collector; one optional small floating overlay above Eden, no extra expensive GPU shaders in-game; hide on lock/screen-off and always offer notification Stop/Hide.
