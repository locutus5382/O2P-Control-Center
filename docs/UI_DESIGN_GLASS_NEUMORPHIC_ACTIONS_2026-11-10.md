# O2P UI refinement — high-contrast controls and a one-shot perimeter animation

Date: 2026-11-10. Work is isolated on a **separate UI branch** based on the tested v0.9 Alpha 2.2 **session-only** branch, so the user's evening Eden/ClusterTune experiment is not affected by UI iterations.

## User design references

1. [Design Studio: Glassmorphism UI](https://www.designstudiouiux.com/blog/what-is-glassmorphism-ui-trend/) — frosted translucency, layered depth, border highlighting, glow and colored backgrounds; warns that heavy blur and weak contrast harm usability. The Android UI uses semitransparent layered gradients and highlights but **does not claim real background backdrop blur**: expensive RenderEffect treatments are intentionally avoided.
2. [Design Studio: Neumorphism](https://www.designstudiouiux.com/blog/neumorphism-ui-design/) — tactile elevation, highlights and shadows, softened surfaces. The pure trend's lack of contrast is deliberately *not* adopted; all action outlines remain visible and controller focus has a stronger border.
3. [They Make Design: App Design Inspiration 211](https://medium.com/@theymakedesign/app-design-inspiration-vol-211-370dd0ceae3c) — selective chromatic emphasis, high-clarity typography, compact functional hierarchy. Primary selected actions get accent fills; secondary controls stay neutral; 'Stop recording' uses a distinct danger color.
4. Existing owner goals: compact, landscape/controller-first, 18 accent colorways, four themes; no heavy animation or overlay work during game launch.

## Proposed design behavior

- Shared `O2PAction`: at least **46 dp** high; **1.5 dp** secondary and **1.9 dp** primary/danger high-contrast borders. Focus **2.8 dp** border with a contrasting warm/cool color for D-pad visibility.
- One **620 ms** clockwise accent arc travels along the REAL rounded-rectangle perimeter of the button after an activation. Uses a bounded Android `PathMeasure` drawing on the tapped control only. No infinite or global animation; follows Compose system animation scale. The 135 ms shallow press scale creates tactile response.
- All themes use the perimeter orbit on action, controlled by the user's existing `lightSweep` animation preference; update UI label to `Animated press-outline (all themes)`.
- Three glass families keep composited semitransparent glass panels, static radial glows and glossy rim treatment. The Decky/material family gets soft raise/press shading and a clear outline. No continuously animated UI or real-time blur in the gaming HUD.
- Tune canvas/panel accent ratios down to reduce full-screen saturation, leaving selected controls as the visual focal points.
- No changes to service, Quick Settings logic, CPU/GPU policy, PServer host, sampling or Android process priorities.

## Acceptance on the Portal

1. Build tests pass; animated button is perceptible and non-intrusive on-device.
2. Dark and light themes: enabled and disabled text readable; borders recognizable; selected vs unselected controls unmistakable; OLED dark mode remains legible.
3. Test touch, rapid repeated tap, keyboard/D-pad focus, selected profile, Start Session and red Stop recording.
4. Turn off "Animated press-outline" and confirm press effects stop; page changes still respond.
5. Confirm no animation occurs while in Eden simply because the main UI is in the background.
6. Note this stage is a code visual iteration, not a claim that design has been approved by the owner. Collect device screenshots across themes and refine afterward.
