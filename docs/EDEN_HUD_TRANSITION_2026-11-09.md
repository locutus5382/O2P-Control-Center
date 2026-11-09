# Eden fullscreen HUD transition — user repro, 2026-11-09

## Actual observation
The user installed the read-only v0.6 alpha test on AYN Odin 2 Portal. The O2P floating HUD was initially visible over Eden's menu. When a game started, the floating HUD disappeared. The user returned to the O2P app and brought the HUD back, then returned to the running Eden game, and the HUD remained visible until the game ended.

This is **not** evidence that Eden prevents all third-party overlays. It appears associated with a particular game-launch transition, and the ability to show the HUD once the game is running means recovery is possible. The exact window manager cause is unconfirmed. There is no Android public API guaranteeing visibility over every other app.

## Uploaded artifacts
- `O2P_Gameplay_2_20261109_1421.csv`: 210 persisted history intervals and about 17.9 minutes elapsed (2026-11-09T13:03:34Z to 13:21:28Z). 198 intervals attributed to `dev.eden.eden_emulator.nightly` by Usage Access, 986 live samples. The recorder **continued while Eden was foreground**; CSV has no field proving whether overlay window pixels were visible.
- Peak CPU sensor while attributed to Eden: 66.6°C; peak GPU sensor: 49.7°C. Mean window CPU load: about 41.1%; window mean GPU load about 30%. No trustworthy FPS source or fan tachometer; both remain unavailable. 17-minute run is not a performance comparison against overlay-OFF.
- `O2P Control Center —(1).txt`: PServerBinder visible/alive but legacy UID read not verified; CPU maximum policy0 unreadable; other two policy runtime maxima exceed listed available frequencies. Read-only only.
- `O2P READ-ONLY OEM STOCK(1).txt`: NO_CAPTURES / user OEM mode blank. Does not provide Stock authentication or permit tuning.

## Recovery behavior implemented
The existing `TYPE_APPLICATION_OVERLAY` is refreshed via a **user-initiated one-shot** service action `ACTION_REATTACH_HUD`, available both in the ongoing recording notification and O2P Sessions settings. The action:
1. Requires an ongoing opted-in session (does not start one).
2. Removes the existing HUD window and reattaches using the same saved user settings and most recent telemetry sample.
3. Does not start a second sensor collector, change application priorities, request root, or change kernel settings.
4. Reports “refresh requested; visibility unknown” rather than claiming success; keeps the Stop action available.
5. Does not enter an automatic reattach loop, bypass Android protected-window restrictions, or assume it is always visible.

Android: `TYPE_APPLICATION_OVERLAY` is above normal app windows but may be resized, moved or hidden by the system; protected windows can opt out of overlays using `HIDE_OVERLAY_WINDOWS`. There is no reliable public API confirming whether the user can see the window over a game.

- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- https://developer.android.com/security/fraud-prevention/activities

## Next physical test
1. In Sessions, start an ordinary floating HUD session as before. Check the HUD over Eden's main menu.
2. Start a game. If HUD disappears, **do not switch to O2P**; pull down Android's notification shade and tap **Refresh HUD**, then dismiss shade.
3. Record whether it becomes visible within gameplay, stays visible, and whether Show/Hide still works.
4. Test if starting a second game causes the issue again. The notification and CSV should continue recording either way.
5. Check whether the same issue occurs in other emulators. If Eden alone triggers it, we may need a specific, opt-in transition handling policy; never attempt to evade a security-induced overlay hide.
6. Share new diagnostic status and a screenshot if possible, without sharing private data.

The previous v0.6 alpha3 frequency-discovery and Stock fixes remain read-only in the parent branch. Full Stock restore and underclocking are still disabled.
