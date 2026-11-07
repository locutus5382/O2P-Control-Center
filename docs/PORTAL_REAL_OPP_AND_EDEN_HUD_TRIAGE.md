# v0.6 Alpha 3 — Odin 2 Portal diagnostic regression and Eden HUD triage

## What the user's actual reports establish (2026-11-07)

Source: `O2P Control Center — (1).txt`, `O2P READ-ONLY OEM STOCK.txt`, `O2P_Gameplay_1_20261107_2046.csv`.

- Firmware: `qti/kalama/kalama:13/TKQ1.231222.001/eng.Odin2P.20260319.182423:user/release-keys`.
- PServerBinder exists and responds as alive, but **legacy output-marker identity is not verified** (UID null). This is not a validated host handshake.
- `policy0` cores `0 1 2`, hardware max 2,016,000 kHz, **runtime `scaling_max_freq` unavailable**, 16 steps up to 2,016,000 kHz.
- `policy3` cores `3 4 5 6`, runtime max 2,803,200 kHz and hardware max 2,803,200 kHz, **listed OPP table ends at 2,707,200 kHz** (19 steps).
- `policy7` core `7`, runtime max 3,187,200 kHz and hardware max 3,187,200 kHz, **listed OPP table ends at 2,956,800 kHz** (20 steps).
- `KGSL` Adreno GPU: listed 9 steps from 124.8 to 680 MHz, max_gpuclk 680 MHz; `max_pwrlevel=0`, `min_pwrlevel=4`, `num_pwrlevels=9`. Power-level indices are **not MHz**; policy defaults may constrain actual reachable levels.
- All three CPU `scaling_min_freq` nodes unreadable without elevated access. Stock cannot be independently authenticated; no Apply/underclock writes.
- `O2P READ-ONLY OEM STOCK.txt` has **NO_CAPTURES**, empty manually entered AYN mode; the raw diagnostic portion is valid but does not provide Stock verification.
- 14 recorded gameplay history windows (approximately 77.5 seconds wall time); Eden had only two short windows and 5 captured samples, roughly 6.1 seconds measured window duration, so not an in-game sustained performance test.
- In that session O2P did capture `dev.eden.eden_emulator.nightly` from Usage Access; unavailable FPS/fan readings must remain unavailable.

## Important frequency Studio fix

Previous O2P read-only Studio discarded all policies whose `scaling_max_freq` was absent or not exactly listed in `scaling_available_frequencies`. This excluded **all three Portal CPU policies** from the editor. Fixed regression tests now require 16+19+20 CPU listed steps and 9 GPU steps. The app shows **highest listed step** separately from **reported runtime maximum**, with warnings when a value is unreadable or not in the OPP list. No value from a discrepancy authorizes a write. CPU min remains unknown, and Stock comparison cannot pass if an effective maximum is unreadable.

**Do not infer that listed OPPs are the absolute maximum or that the reported runtime maximum is a writable OPP.** The gameplay CSV shows policy7 current clock as 3.19 GHz, reinforcing that frequencies outside the list may be active (boost/driver-specific behavior). This must be investigated via verified firmware read-only facilities.

## User feedback: HUD visible before Eden, absent over Eden

This is a separate **release blocker**. The O2P HUD uses Android `TYPE_APPLICATION_OVERLAY`. An ordinary overlay is normally above applications, but Android 12+ allows protected foreground apps to suppress it using `HIDE_OVERLAY_WINDOWS`; the OEM window manager can also change visibility, position and size. We must not bypass security/protected-window restrictions.

The HUD status now shows whether an O2P floating window is **attached**, its coordinates, overlay permission/hidden/lock state, and warns that attachment does **NOT** prove visibility over Eden. Add user-initiated **Recenter HUD** (free positioning, both orientations). The ongoing notification remains available even if the HUD is suppressed; it now includes last collected CPU/GPU temperatures on a conservative ~12s update cadence, plus Hide/Show and Stop.

### Eden troubleshooting test

1. In O2P → Sessions → Floating + notification, check **Display over other apps** is permitted; choose `Free`, turn Lock OFF and select **Recenter HUD**. Start recording.
2. Confirm HUD above Android **Home** and another ordinary app, such as Settings or ES-DE.
3. Launch Eden. Check separately **Eden's app menu** and **inside a running game**: does the HUD remain in front?
4. Pull down notification shade. If the O2P recording notification is still updating CPU/GPU and session intervals, the recording service is active. If HUD isn't visible, it may be hidden by Eden/OEM or out of bounds. If notification vanished and no session continued, investigate service interruption.
5. Return to O2P → Sessions and read **HUD window state**. An `attached at (x,y)` result with a hidden HUD in Eden points to foreground-app/OEM visibility suppression, but not proof of which one.
6. Try the **Recenter HUD** action and repeat once. Don't repeatedly reattach windows, disable Android security, install root, grant accessibility control, or use system-alert permission workarounds.
7. Share screenshots of HUD above another app, Eden app menu and inside gameplay, and report whether the notification remains. No personally identifying details needed.

**Documentation:** Android's TYPE_APPLICATION_OVERLAY specification explicitly permits system-managed visibility changes. Android security docs describe apps opting out of third-party overlays with HIDE_OVERLAY_WINDOWS.

- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- https://developer.android.com/security/fraud-prevention/activities
- https://developer.android.com/develop/ui/views/layout/immersive

## Shipping gate

- [ ] GitHub CI Android unit tests + debug APK success
- [ ] Device shows 3 CPU policies and GPU OPPs in read-only Studio, with max discrepancies labelled honestly
- [ ] Stock Evidence remains incomplete when a kernel max cannot be read; no false Stock verification
- [ ] O2P notification continues while Eden runs; if it vanishes, diagnose service lifecycle
- [ ] HUD visible in at least one other foreground Android app before diagnosing Eden-specific suppression
- [ ] No privileged frequency writes, no root/su, no bypass of protected-window policies
