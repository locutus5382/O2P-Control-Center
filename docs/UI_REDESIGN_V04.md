# O2P Control Center v0.4 — UI redesign
Status: native Android Jetpack Compose implementation, review branch. Hardware appearance has not yet been verified on an Odin 2 Portal.

## What prompted this
Physical Portal screenshots of the v0.3 alpha showed:
- Giant vertically stacked components on a wide landscape device
- Inconsistent oversized typography and full-width oversized buttons
- Severe dark-text-on-dark-blue contrast problems
- The chosen color only tinted accents while the main canvas stayed blue
- Barely visible or unsatisfying touch animations
- Raw CPU cluster and thermal sensor lists cluttering the main screen

The user requested a substantial rewrite rather than a set of incremental color tweaks.

## New information from the real Portal diagnostic
- AYN Odin2 Portal, Android 13, with three CPU cpufreq policies (0, 3, 7)
- Named CPU thermal sensors `cpuss-*` and `cpu-*` (do not display all on the home screen)
- Named GPU thermal sensors `gpuss-*` (can use a reported peak)
- GPU KGSL clock and busy-percent readings are directly readable; busy-percent node may contain a space before `%`
- Read-only direct paths succeed; PServer attempted but privilege status was **not verified** ("No verified exit marker")
- Other thermal zones include misleading values such as PMIC, modem, unavailable inputs; do not pick arbitrary first thermal zone as a CPU/GPU temperature
- Eden app detection from Usage Access appeared in prior gameplay reports; do not claim internal emulator FPS or exact process activity

## Design principles
1. Landscape-first, responsive two-pane layouts; compact fixed-width navigation rail on wide devices, horizontally scrolling navigation only on narrow screens.
2. **One CPU and one GPU card**, each with one clearly explained summary. CPU/GPU temperatures use the highest readable sensor whose label belongs to the respective name group. They are *not* claims of calibrated die temperature.
3. Separate Monitor (live telemetry) from Sessions (background recording/history) and Diagnostics (raw kernel nodes). No huge unreadable tables on main pages.
4. **18 colorways are whole-app palettes**, not accent-only. Palette drives canvas, gradient, glass panels, borders, navigation, buttons, foreground ink and muted text. Light colorways use dark ink; dark colorways use light ink.
5. Glass mode: translucent tinted surfaces, rim highlights, soft atmospheric background glow, tactile press scale, 520 ms diagonal light sweep after every action; independent saved colorway and configurable translucency.
6. Material mode: solid surfaces, tighter corners, same accessible semantic tokens and independent saved colorway.
7. Do not claim pixel-perfect Apple optical displacement. The React/SVG/Skia refs below demonstrate effect *principles*, while this implementation uses GPU-conscious Compose layers without copying their web engine or proprietary assets.
8. Keep monitoring read-only and preserve session SQLite history and user-started foreground-service behaviors.
9. Preserve raw access and diagnostics export, while keeping unverified privilege explicitly unverified.
10. Respect reduced motion in future QA (Android system animation scale and accessibility) before shipping as polished release.

## Design references supplied by project owner
- https://github.com/rdev/liquid-glass-react : refraction, edging, frosted glass, elastic press motion. React/Web.
- https://github.com/Appllama/liquid-glass-screens : glass depth, motion language, background composition. React Native/Skia, GPL-3.0; **reference only**, no copied assets.
- https://github.com/dazecoop/liquid-glass : fresnel-like glass rim, specular highlights and press tactility. Browser SVG/DOM, reference only.
- https://github.com/kartiknagar333/StatusApp : generic Android app structure but unrelated UI/application domain; no functionality or code adopted.

## Test gates
- [ ] GitHub :app:testDebugUnitTest and :app:assembleDebug pass.
- [ ] Install on Portal; inspect at native landscape resolution, 100%, larger text and Android font scaling.
- [ ] Test full-app colorways: white, black, dark gray, light gray, blues, reds, yellows, greens, purples and oranges.
- [ ] Contrast-check all text on dark, light and translucent surfaces; no black text on dark blue.
- [ ] Verify actual shimmer is visible on press; no continuous animations while gaming.
- [ ] Monitor shows one CPU/GPU reading; raw sensors remain accessible in Diagnostics.
- [ ] Check recording/notification, saved sessions, export and permissions are unaffected.
- [ ] Confirm no system writes and no false privilege claim.
