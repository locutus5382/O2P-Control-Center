# O2P Control Center — Premium Four-Pillar UI/UX Specification

**Design proposal, 9 November 2026.** Design-only branch; no production behaviors, IPC, display colors, kernel permissions or frequency writes change in this PR. Start from v0.8 Alpha 2. Implement in separately reviewed increments.

## 1. Product position and non-negotiables

O2P is a **landscape-first premium handheld companion for the Odin 2 Portal**. Its first job is safe, evidence-driven **underclock maximums**, so users can preserve smooth gameplay while reducing heat/power. Its second job is **fast, reliable, low-overhead telemetry** and an always-on-top HUD. It is not a task cleaner, arbitrary kernel editor, automatic overclocker, fan controller, charging controller, or redundant controller-mapping application.

Product priorities, in order:
1. **Easy to understand** — current applied state vs selected preview is unmistakable.
2. **Fast to use inside a game** — two existing Android Quick Settings tiles: O2P Profile (picker/cycle/tune) and O2P Session (Start/Stop/Show/Hide/Refresh HUD).
3. **Verifiably safe** — no active frequency write without validated OEM Stock baseline, CPU min/max bounds, and transactional rollback; no user root or su.
4. **Premium visual identity** — consistent rounded surfaces, spacing and animation, tasteful glass, coherent theming, controller focus.
5. **Diagnostics as an opt-in tool** — no debug walls on ordinary pages.

No fabricated “optimization score”, measured FPS or applied frequency. The circular hero displays a **real available metric** or an explicit unavailable state. On first release, CPU temperature is a sensible hero choice; also allow GPU temp or CPU/GPU load.

## 2. Exactly four navigation destinations

**Underclock | Telemetry | Advanced | Diagnostics**. No separate Home, Monitor, Sessions, History, Studio or Stock tabs.

A persistent **left navigation rail** is primary on landscape. Recommended width 76–92 dp collapsed / up to ~152 dp with labels; top displays small logo, bottom shows active profile/session icons and Settings. Use the available viewport rather than a hard-coded 16:9 assumption. On narrow window sizes, collapse to icon rail or bottom navigation. Focus order: rail → content → actions.

Android controller support inside the app: D-pad/left-stick navigation with visible focus border; A select, B back; L1/R1 optional section navigation **only when our Activity owns focus**, never globally intercept hardware buttons. Horizontal frequency sliders: left/right one exact OPP step. Long-press is not required for critical controls.

Each destination has a clear hero and at most two vertical card groups visible before scrolling. Existing screens are refactored/reused rather than duplicated in a new parallel state system.

### Underclock (default launch page)

**Purpose:** pick, inspect and eventually apply a safe maximum-frequency cap.

- **Left hero, 38–42% of content:** title "Current tuning"; a circular ring or tier bar with factual status, e.g. `Not applied • Stock unverified`, `Stock verified`, `Light applied • readback confirmed`, `Restore incomplete`, `Unknown`. Place selected profile and **verified hardware status** on separate lines, never same value. Show active AYN OEM mode if user/firmware verified, otherwise "OEM mode unknown".
- **Profile strip:** `Stock`, `Light`, `Medium`, `Strong`, and custom profiles. Stock is not a cap and means no O2P-imposed limits. Never label a profile `Max` if it could suggest overclocking. Selected is not applied.
- **Primary actions:** Apply (disabled with a human-readable reason until a verified writer exists); Restore Stock (only enabled after a tested restore implementation exists); Save Custom; Undo edits.
- **Right slider panel:** Efficiency CPU policy0, Performance policy3, Prime policy7 and GPU, using the detected real OPP table. Exact MHz/GHz labels, current target, verified Stock cap and current effective minimum. GPU power level mapping must be verified before an enabled GPU slider. No interpolated illegal MHz steps.
- **Easy / Pro toggle**: Easy shows profile cards and short explanation; Pro expands all exact sliders, validated range explanations, and profile management.
- **Quick Settings entry:** install/configure O2P Profile tile, cycle favorites, pick profile in a compact panel. Today it chooses **read-only previews**; when tuning is available it must use the same authoritative backend and transaction state.
- Do not expose governors, CPU min frequency, GPU min power level, voltage or thermal controls.

### Telemetry

**Purpose:** single glance at system load and temperature, HUD and recording.

- **Left hero:** fact-based selectable circular gauge (CPU temperature default), with clear Celsius and named sensor; Start/Stop Recording and HUD Show/Hide; explicit live/paused/stopped states. No arbitrary score.
- **Right bento:** CPU aggregate load + busiest core; GPU load + clock; CPU/GPU temps; RAM + memory pressure; battery; foreground app/FPS availability. If FPS, fan or headroom is unsupported, show "Unavailable" rather than fake 0.
- **Below:** choose metrics tracked for live HUD/history, intervals, HUD style (Compact Pill / Expanded Card), position, scale, alpha, lock, manual Refresh HUD, and a session history card with mini sparklines and export.
- **HUD styling:** low-cost faux glass/matte, no background blur while Eden renders. Avoid extra SurfaceFlinger composition passes or expensive chart redraws; limit update rate to configured 1/2/5 sec, only changed text and small samples. HUD session lifecycle and always-on-top behavior are independent; do not claim attached implies visible.
- **Quick Settings:** existing O2P Session tile opens Start, End, Show/Hide and Refresh HUD. Notification Stop and Refresh are fallback. Never create a second sampler.

### Advanced

Three clearly bounded sections **Appearance / Device / Quick actions**:

**Appearance:** Four independently polished visual families (below), preserved user accent palette, HUD theme linkage switch, glass/matte intensity, animation level (Full/Reduced/Off), text scaling within accessibility limits, contrast option, and an in-app live preview. Every colorway recolors canvas, surfaces, borders, rings, toggles, sliders, focus states and charts—not only the button fill.

**Device controls — strict scope:**
- **Display Colors:** include a compact card **from v0.9** with `Open system display color settings` using Android `Settings.ACTION_DISPLAY_SETTINGS`, if resolvable. Allow native color modes to remain owned by AYN/Android. Do **not** promise Xiaomi-style `Vivid/Saturated/Standard/Original` selectors or a color-temperature wheel unless a Portal-specific, user-approved, reversible integration is independently verified.
- **Research finding:** OdinTools `SettingsRepo.setSfSaturation` sends a privileged shell request `service call SurfaceFlinger 1022 f [value]` via PServer. This is not public Android display-color control. OdinTools upstream compatibility is documented on older Odin 2 firmware, not confirmed on this Portal build. Open upstream issue #57 (2026-04-06) reports saturation slider stuck at 1.0 on Odin 2 Portal. O2P must **not** copy arbitrary `service call`, silently change persistent properties, or claim readback without proven display mode ownership. A future controlled feature needs firmware compatibility test, original-state capture, exact restore, visible confirmation/revert timeout and no auto-apply at boot. If the native display menu already meets the need, omit custom saturation altogether.
- **Color temperature wheel:** omit initially. Native Night Light/color settings may be linked when available; a system-wide temperature matrix requires separate verified, reversible color pipeline research. Do not offer a fake preview affecting only the O2P app while saying it changes the device display.
- **Charging separation / bypass:** omit automatic controls. The built-in AYN facility is sufficient; perhaps link to native controls, no threshold scheduler.
- **Controller style / mapping / trigger mode:** omit duplicates of AYN stock settings and OdinTools.
- **Fan mode/curves, vibration strength:** omit kernel controls. Existing AYN fan modes remain authoritative; display fan telemetry only if actually readable.
- **Single-press Home button:** possible **later** as an opt-in if compatible and truly useful, not in initial Advanced page.

**Boot behavior (minimal):**
- Save/restore theme, selected page, HUD layout, and user preferences automatically.
- Default **no tuning and no recording on boot**. No boot-time privileged host, no automatic reapply of OPP caps and no background recorder without consent.
- Separate clearly from user-started recording `START_STICKY` process recovery during an active session; no boot receiver required.
- Future optional "restore previously verified profile on app launch" only after OEM Stock rollback and competing-tuner coordination are physically tested.

**Notification / Quick Settings controls:**
- Profile tile tap: open picker (default), optionally cycle favorite **previews**; long-press opens Underclock. Add tile prompt and clear applied vs preview status.
- Session tile tap: small panel for Start, End, Show/Hide, Refresh HUD. If service died, show Interrupted/Stopped, not a fake active state. Compact notification shows latest trustworthy sensor sample and Stop.
- Configurable tile accent/icon style, HUD repair behavior, telemetry notification visibility, metric sampling and notification permission guidance. No silent permission bypass.
- Keep the visible Quick Settings behavior easy to find in Advanced and a context link in Underclock/Telemetry.

### Diagnostics

Entire engineering feature set lives here. The **Diagnostic Mode** toggle controls *extra diagnostic scans and verbose local logging only*. **OFF does not disable safety checks**, trustworthy readback, restore verification, normal telemetry, session lifecycle recovery or permission enforcement. Security/safety is never optional.

- When OFF: one compact system-health/compatibility summary and `Enable diagnostics` switch. No per-node tables or prolonged scans; no PServer host launch on page visit. Export disabled or limited to routine non-invasive support summary.
- When ON: PServer authenticated read-only host check (explicit action), CPU/GPU/kernel inventory, named thermals, fan/hwmon details, OEM Stock capture and review, errors/errno, memory and service/HUD lifecycle, export JSON, CSV/history links, compare firmware snapshots.
- Hardware gates always report separate `PServer host verified`, `required nodes readable`, `Stock baseline verified`, `Rollback proven`, and `writer allowed`. At the current milestone **Stock restore and writes remain blocked**.
- Add `Run Quick Check` (passive/direct) and `Deep Read-only Check` (explicit host, once), with progress, cancellation and clear failed-node explanations.
- One Share Diagnostic Bundle action packages the chosen redacted files **only with user consent**. Do not background upload or include serials, tokens, private paths or host nonces.

## 3. Four themes, equal feature parity

Architecture: a `ThemeFamily` enum and shared semantic tokens, not forked composable pages or hard-coded accent-specific layouts. Keep existing 18 accent options (white, black, light/dark gray, light/dark blue, red/light red, yellow variants, green variants, purple variants, orange variants) as colorways, adjusted for contrast on each family.

| Family | Surface treatment | Background and highlight | Typography and input |
|---|---|---|---|
| **Obsidian Liquid Glass** (default) | Dark translucent layered rounded panels, specular rim | Deep navy/graphite with restrained cyan/violet ambient glow | High contrast, subtle animation, AMOLED-friendly |
| **Aero Frost Light Glass** | Ice-white satin/frost cards, subtle white rim and soft shadow | Pale blue-to-pearl mesh, cyan highlights | Dark ink, generous whitespace, precise dial |
| **Violet Nebula Glass** | Semi-transparent lavender and blush glass | Violet-magenta pastel gradient, soft orbs | Dark-violet ink, light motion, charts with accessible contrast |
| **Decky Tactical Cockpit** | **Matte slate**, almost no transparency or glow, squared 12–16dp corners | Charcoal/steel panel hierarchy; clear focus outline | **Chunky stepped horizontal frequency sliders**, D-pad one-step adjustments, monospace clock/load badges, utility-first; NO glass effect required |

Define `ThemeTokens` for canvas, surface, elevated surface, text, muted, emphasis, warning/error, accent, chart lines, HUD fill/outline, focus stroke, corner radii, elevation, opacity, motion durations and controller focus. Recompute token colors for every accent/family combination with contrast checks. Matte can show the same 18 user-selectable accent colors; palette changes only color, not its console-first structure.

**Animations:** 120–220ms entrance/press easing only when enabled; glass button sweep is subtle and never blocks actions. Respect system reduced-motion and provide manual Off. For tactical, near-instant 80–120ms focus transitions, no shimmer. HUD never uses live-blurred content. Preserve legibility in bright outdoors and dim rooms.

**Typography:** hierarchy based on title/subtitle/body/tiny metadata; large only for temperature/frequency values. All controls minimum ~48dp touch target, D-pad focus ring, error text not color-only. Avoid unlicensed bundled fonts: system sans and system monospace suffice.

## 4. Data and state rules

UI must consume one view model for verified hardware state, a separate draft/preview model for underclock controls, existing Session/GameplayRuntime authoritative service state, and a diagnostics-on-demand store. A control's enabled status depends on actual capability, not screen location or theme.

- Selected preview != applied and read-back verified.
- `100% Stock` != forcing cores to fixed max.
- No pretending FPS or display-color capabilities are present.
- Read-only PServer results do not authorize writes, even with UID 0.
- Visible `Restore Stock` path always prominent on Underclock once it exists, NOT hidden under Diagnostics.
- If Android kills session service or suppresses HUD, show specific state, manual Refresh and persistence hints; no bypass of protected overlays.
- Thermal/cap warnings may interrupt navigation when a verified safety issue occurs; safety does not respect Diagnostics OFF.

## 5. Acceptance and implementation sequence

**UI milestone A (first):** four destinations and left rail, redirects from old nav, four theme families via shared tokens. Diagnostic Mode default OFF (extra scans off). All current PServer and read-only safety behavior unchanged. No frequency writer.

**UI milestone B:** premium Underclock/Telemetry cards, profile preview vs hardware state, saved custom profiles, two QS tiles, compact/expanded HUD appearance, full palette, controller focus. At least one 60-second screen recording test for every theme in landscape, checking overflow at actual font scale and emulator launch.

**UI milestone C:** consolidating Advanced and Diagnostics; system Display Colors shortcut; JSON and stock capture under diagnostics, quick check/deep check, export and hardware gate wording; optional color mode research only after AYN-specific compatibility.

**Later separate hardware milestone:** independently validated CPU maximum-only transaction, readback, rollback, Stock restoration and firmware conflicts; after which profile Apply and QS application are enabled. Never tie writer enablement to a theme redesign.

**Testing:** functional state per screen, snapshot/golden UI across four families and accent contrast extremes, portrait/window resize fallback, D-pad focus traversal, L1/R1 behavior, low-memory Eden background transition, Quick Settings launch, readability with 120Hz panel and reduced-motion, no changes to privileged host access from UI refactor.

## 6. Explicit exclusions

Do not add app cleaner/boost-score fiction, persistent root, CPU minimum/voltage/governor/thermal changes, duplicated fan/controller/trigger features, automatic bypass charging, untested boot tuning, unverified global color saturation or color temperature wheel, and AutoPilot before measured FPS and readback validation. Add no new kernel controls merely to make Advanced look full.

**References:** `langerhans/OdinTools` SettingsRepo.kt (PServer-backed `service call SurfaceFlinger 1022`); upstream open issue #57 (Odin 2 Portal saturation slider unable to adjust); Android `Settings.ACTION_DISPLAY_SETTINGS` public SDK; current O2P `Appearance.kt` accent palette; AYN's native settings and Quick Settings.
