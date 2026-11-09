# v0.7 Alpha 1 — Eden HUD transitions, attribution, and two Quick Settings tiles
Date: 2026-11-09

## Uploaded files reviewed

- `O2P_Gameplay_4_20261109_1517.csv`
- `O2P READ-ONLY OEM STOCK (1).txt`
- `O2P READ-ONLY OEM STOCK (2).txt`
- `O2P Control Center — (2).txt`

Data and interpretation must be kept separate from assumptions about Eden.

## What the gameplay recording establishes

| Measure | Observed |
|---|---:|
| Total recording wall time | 3 min 58.022 s, 2026-11-09 14:13:06.495 to 14:17:04.517 UTC |
| History rows | 49 |
| Live samples captured | 230 |
| O2P foreground | 4 rows, 14 samples |
| Eden foreground | 2 rows, 7 samples |
| Unknown/unattributed | 42 rows, 208 samples |
| Launcher | 1 row, 1 sample |
| Unknown span | 14:13:25.138–14:17:00.410 UTC (~3 min 35 sec wall span) |
| Peak CPU sensor in unknown windows | 74.1°C |
| Peak GPU sensor in unknown windows | 49.7°C |
| Mean CPU load across unknown windows | ~40.17% |
| Mean GPU load across unknown windows | ~39.86% |
| Frame-rate measurements | All 49 rows Unavailable |
| Fan RPM measurements | All 49 rows Unavailable |

**Crucial:** The CSV **does not identify the foreground game** during the 42 unattributed windows. Sensor recording continued, but this does not prove exactly when the floating HUD was visible. User says HUD vanished when starting game, repeating earlier test.

Android UsageEvents can emit `ACTIVITY_RESUMED` for a new Activity and later `ACTIVITY_STOPPED` for an older Activity in the **same package**. The previous `ForegroundAppDetector` cleared Eden's current package on any STOPPED event with matching package, even when it belonged to the old activity. This is a plausible explanation of the persistent unknown rows (not a proven root cause). Code now tracks the class name as well and avoids erasing a newer resumed activity from an unrelated old activity's stop. Android might still withhold events or behave differently under a game: missing labels remain Unknown.

## HUD findings and change

- Existing `HudOverlay` has `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`. It attaches a single small View and renders telemetry ~2s apart.
- Existing monitor service can continue while game runs; window attachment status is not equivalent to actual visibility above a fullscreen game.
- User repeatedly sees HUD over Eden's launcher, then loses it **during game startup**. In a prior session a manual app return and HUD reattach restored it for the rest of gameplay. This is *consistent with* a game-launch window-manager transition, not proof of the actual reason.
- To avoid recurrent attempts against protected windows: v0.7 has **one opt-in reattach attempt per recording** following a detected Eden Activity transition (a different Eden Activity class is resumed), with a ~2.5-second settling delay, **or** after Eden is followed by two successive unattributed samples. Setting in Sessions → HUD, enabled by default, can be disabled. These events are heuristics for a game-launch transition, not proof a game started. If the window cannot be restored, O2P will not continuously retry.
- There is still a **manual Refresh HUD** control in the notification, new Session Quick Settings panel, and Sessions UI. It removes/recreates the overlay once without starting another monitoring loop.
- If the game or Android enforces protected-window overlay hiding, O2P respects that policy. No accessibility abuse, hidden fullscreen overlay, system-UI hacks, root, su, or continual re-add attempts.

## Diagnostic and Stock reports

Two Stock candidate reports and the direct report share firmware:
`qti/kalama/kalama:13/TKQ1.231222.001/eng.Odin2P.20260319.182423:user/release-keys` on Android 13 (API 33). The two candidate reports were captured roughly 363 seconds apart, but both have **empty manually entered OEM mode** and **NO_CAPTURES**, so their timestamps do **not** count as two saved Stock candidate observations. They both say `Stock verified: NO`.

PServerBinder is visible/alive and the v0.6 Alpha 5 architecture uses the ClusterTune-inspired passive transport, but **no elevated host identity or read-only privileged handshake is verified**. CPU policy0 effective max and all CPU minimums are unreadable. Policies 3/7 reported maxima exceed last exposed OPP, so do not equate slider 100% with verified Stock. GPU KGSL offers 9 OPPs, but raw max/min power levels must not be treated as MHz without an authenticated mapping.

No privileged sysfs writes, undervolting or clock controls are permitted in v0.7.

## Quick Settings implementation

Two user-addable Android tiles, available on Odin 2 Portal Android 13:

1. **O2P Profile** — opens a compact selector for Stock, Light, Moderate, Strong and saved custom frequency previews. Supports optional *tap-to-cycle preview selection*, and sliders for exact MHz and "save as custom preview". Every screen says **READ-ONLY / NOT APPLIED**. This tile **cannot** underclock, apply, restore, or claim a selected preview is the hardware state. When future writer is independently validated, this UI can share the authoritative tuning backend, not a separate shell command path.
2. **O2P Session** — opens a compact Start/Stop/Refresh/Show/Hide panel reusing the existing foreground telemetry service. Start is explicit, requires notifications and keeps the ongoing Stop notification. Refresh invokes the single existing remove-and-reattach action. Tile active state represents ongoing recording only.

Android tile add prompts appear in full Performance Studio and Sessions (API 33); manual add via Quick Settings → Edit also works. MainActivity opens directly to the requested section when invoked from a tile.

The Android tile services require `android.permission.BIND_QUICK_SETTINGS_TILE` and are exported to Android SystemUI; the compact panel Activity itself is unexported. API 34+ uses PendingIntent to start Activity; API 33 uses the supported Intent overload.

## Test plan (physical Odin 2 Portal)

1. Install the v0.7 Alpha 1 GitHub successful debug APK.
2. Open Sessions and use **Add O2P Session Quick Settings tile**, then Studio **Add O2P Profile Quick Settings tile**. Approve Android prompt, or use Quick Settings → Edit.
3. Pull down Android shade over Eden launcher/game; tap O2P Session. Start session, then close panel; confirm ongoing notification and HUD.
4. Launch a game. If HUD disappears, see if it returns with the single automatic recovery after two unattributed samples. If it does not, tap O2P Session → Refresh HUD (or ongoing notification → Refresh HUD).
5. Check whether CPU/GPU measurements and session recording remain uninterrupted. Test second game launch and note whether one-shot recovery has already been used.
6. Test Profile tile: choose a preset, cycle selection, create custom preview from exact observed frequency steps, save and reopen. **Actual clocks must not change** and all choices must be clearly marked **not applied**. Try optional tap-to-cycle and switch back to tap-to-open.
7. Stop from O2P Session tile and ensure a single saved recording is created, no second monitoring collector remains.
8. Export next gameplay CSV. If Eden is still largely unattributed, record the exact launch steps; consider a separate read-only foreground-activity event trace and compare against Android UsageStats.
9. Stock candidate verification still requires **manual OEM mode label** and two *saved* observations. Without this, do not enable tuning.

## Follow-up after on-device feedback

- If game-launch one-shot reattach doesn't work, investigate app/OEM protected-window behavior and actual window metrics; don't add a continuous overlay suppression bypass.
- If activity-aware package detection doesn't work, collect transient read-only usage-event details to confirm event order. Don't infer the foreground game from missing UsageEvents.
- Keep PServer privileged host milestone separate from the two UI tiles; only after verified host + complete Stock baseline + rollback may the Profile tile actually apply caps.
