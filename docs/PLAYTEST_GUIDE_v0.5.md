# O2P Control Center v0.5 — Odin 2 Portal gameplay HUD test

**Status:** opt-in read-only APK candidate. Android build/test success does not establish GPU/CPU overhead or game compatibility. The root/default branch remains unchanged until review.

## 1. Install the v0.5 APK

1. Go to the GitHub **Actions** tab, select the most recent **successful** workflow on branch `feature/v05-hud-and-hardware-profile`.
2. Under **Artifacts** download `O2P-Control-Center-debug` (ZIP), unzip and install `app-debug.apk`.
3. If Android asks about an update/signature, **export important recordings first**: uninstalling the previous APK will delete on-device SQLite sessions.

## 2. Enable permissions only when needed

In O2P → **Sessions**:
- Android **Notification permission**: required for visible ongoing Stop action.
- **Usage Access**: optional; needed to label foreground Eden/other apps automatically (Android UsageStats; not Linux process or game-title detection).
- **Display over other apps**: required for the floating window only. Open the O2P button to visit Android Settings and return afterward; notification-only recording works without it.

No root, USB debugging or PServer is needed for read-only HUD sampling.

## 3. Try all display modes

1. Select **Floating + notification**, preset **Minimal**, dock **Free**, unlocked, opacity 70%, size 100%.
2. Select Monitor → live sample every 2 seconds and history interval 10 seconds. Open Sessions → Start gameplay session, then switch to Eden.
3. Verify the small O2P display remains above Eden, does not cover the entire screen, and can be **dragged to all four quadrants**.
4. Return to O2P → Sessions → change Dock to **Top, Bottom, Left, Right** (one at a time); verify it snaps and remembers the chosen edge. Use Free again to drag.
5. Toggle **Lock HUD**. Check the game still receives touches under the HUD. Unlock in O2P (the notification always offers Stop).
6. Test Minimal, Standard and Custom, then modify Custom metric switches. Missing FPS or fan RPM should display **Unavailable**, not 0.
7. Adjust opacity and size. When locked, actual window opacity is capped at 79% to respect Android 12+ untrusted-touch safety.
8. Use notification **Hide HUD** and **Show HUD** while recording remains active. Switch to **Notification only** and confirm the overlay disappears but the Stop action remains.
9. Rotate device if possible and confirm landscape/portrait positions are saved separately.
10. Press Stop; check overlay vanishes and the saved session is reviewable/exportable.

**If the overlay fails, covers controls or won't stop, stop the session immediately from Android notification.** If still stuck, open Android Settings → Apps → Special app access → Display over other apps and revoke O2P permission. Reboot is not normally required.

## 4. Visual regression

In Appearance, try Light Purple, Dark Gray, White and a deep blue mode. Press any O2P button: the latest release should have a **brighter ~720ms diagonal specular sweep**, animated rim highlight and tactile depression. Check readable text in all colorways and reduced motion settings.

## 5. Measure performance overhead (not yet benchmarked)

Run the *same* Eden game, emulator settings, area and duration (10 minutes minimum) under three conditions:
- O2P not running.
- O2P session recording + **Notification only**.
- O2P session recording + **Floating HUD** (Standard preset, 2s live samples).

Record average FPS/1% lows **only if Eden supplies them directly**; O2P currently does not measure per-game FPS. Share battery/thermal behavior, noticeable stutter, and Android Memory/Developer Tools process-memory readings if available. No performance numbers are assumed in advance.

## 6. Known limitations

- Android OEMs/secure windows can refuse overlay display. Some games intentionally disable overlays; O2P cannot guarantee presence above every protected screen.
- The system notification cannot always be a continuously updated high-resolution performance HUD; it remains the reliable Stop/Hide control.
- Background monitor uses direct read-only kernel/Android interfaces. CPU and GPU thermal readings show the peak of correctly named accessible sensors and may be unavailable in some firmware.
- GPU nominal 680 MHz, X3 advertised 3.2 GHz, etc. are **reference specifications**, not verified writable OPPs. We never change clocks.
- Overlay adds an Android View/window and remains active while the recording service is user-started; **actual CPU/RAM/battery cost is not measured yet**. Refer to [hardware profile](ODIN2_PORTAL_HARDWARE_PROFILE.md).

## 7. Send feedback

Please share:
- A landscape screenshot showing the overlay above Eden.
- A screenshot with the HUD locked/unlocked, and whether touch works under it.
- Whether Top/Bottom/Left/Right docking and separate portrait/landscape positions work.
- Any notification/permission errors.
- Whether the glass press sweep is finally visible.
- The recording CSV and approximate game duration, after checking its contents for private app identifiers.

This v0.5 beta is built on the v0.4 redesign and is separate from the original O2PKernelTurb project.
