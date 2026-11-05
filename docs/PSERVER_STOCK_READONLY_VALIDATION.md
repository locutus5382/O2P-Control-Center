# v0.6 Alpha 2 — PServer compatibility and OEM Stock evidence (READ ONLY)

## Purpose and safety boundary

This build does **not** write CPU/GPU limits, run a root shell, install/start an elevated PServer host, alter system file permissions, or claim that the OEM Stock baseline is known. It safely distinguishes:

1. **PServerBinder visible/alive:** private firmware Binder service appears accessible; this alone establishes no privileged command capability.
2. **Legacy command-output protocol verified:** a single explicit `id -u` query returned a properly marked, successful reply and UID 0. This establishes the *legacy read-only response format*, not authorization to tune hardware.
3. **PServer host verified:** NOT IMPLEMENTED, because that requires a separate authenticated host-launch/handoff test on the physical Odin 2 Portal.
4. **Stable observed ceilings:** exact frequency tables/ceiling values match across two read-only captures of the same manually labelled AYN performance mode, at least 20 seconds apart.
5. **OEM Stock independently verified:** NOT IMPLEMENTED. Even identical observed ceiling values may reflect a different AYN mode, a stale underclock or another tuning app. There is no kernel write path.

In earlier O2P tests, the PServer legacy readout returned `No verified exit marker`. ClusterTune's newer implementation intentionally uses an **output-disabled, launch-only** protocol with a separate host handoff; its Thor device tests do not prove that handshake works on the Odin 2 Portal. This release improves probe accuracy but does **not** attempt the host launch.

## How to test on Odin 2 Portal Base

1. Install the latest GitHub successful `feature/v06-pserver-stock-verification` artifact ZIP → `app-debug.apk`. Export any old sessions before uninstalling if signing differs.
2. Close other clock-tuning applications (ClusterTune, PULSE or others). Leave the device's built-in thermal protection and fan mode alone.
3. In the AYN system settings, select the device's **normal/OEM Stock performance mode** (whatever it is named by your firmware). O2P never changes this mode.
4. Open **Studio**, choose **Refresh Direct**. Look for all 3 CPU policy entries and Adreno GPU supported steps. Absent entries mean insufficient access, **not** safe guessed clocks.
5. In **OEM STOCK EVIDENCE**, manually enter the exact AYN mode label you selected. Select **Capture current observed limits**.
6. Wait at least 20 seconds in the same AYN mode. Press **Refresh Direct**, then **Capture current observed limits** again.
7. Inspect the result: `STABLE_OBSERVED`, `CHANGED`, or `INCOMPLETE`. Even `STABLE_OBSERVED` must say **OEM Stock UNVERIFIED**.
8. Optionally choose **Probe PServer**; it sends one explicit read-only UID query. Observe and record whether it says Binder absent, alive-but-output-unverified, UID not elevated, or UID 0 legacy read-only verified. **No PServer host launch or sysfs write occurs**.
9. Optionally repeat captures under a *different manually entered* AYN mode if you want to distinguish vendor ceilings. Do not mislabel captures as if the mode were authenticated.
10. Use **Share evidence + raw device report** and send the resulting text for analysis. It includes raw OPPs, current/maximum frequencies, GPU KGSL nodes, firmware fingerprint, AYN mode labels and source/protocol limitations. Review for device identifiers before sharing.
11. Test that saved candidate evidence survives closing/reopening the app. Use **Clear this mode's evidence** if a mode was incorrectly labelled.

## Interpreting results

- **No captures**: no saved evidence for this firmware and manual mode.
- **One capture**: a second independent sample is needed.
- **Too soon**: samples are fewer than 20 seconds apart.
- **Changed**: CPU/GPU ceilings, hardware maxima, domain identities or frequency tables differ.
- **Incomplete**: fewer than the expected 3 CPU policies or 1 GPU table were observed.
- **Stable observed**: two matching snapshots exist. This is **not** OEM Stock verification, and does not enable Apply.

A snapshot captured while another tuning app is active or under a different AYN performance mode **must not** become the trusted Stock restore target.

## Next implementation after evidence arrives

The next stage is a **separately reviewed, no-root, PServer-only host compatibility prototype**. It must first:
- identify the firmware Binder protocol safely, with a bounded, opt-in, read-only host handshake;
- authenticate the host and device topology;
- discover actual minimums, OEM maximums, operating points and CPU/GPU constraints, with units;
- establish repeatable tests under Stock and each intended AYN performance mode;
- implement dry-run transaction validation and failure scenarios before introducing **any** privileged writer.

Only after these pass on your Odin 2 Portal should we consider a narrowly allowlisted **maximum-ceiling underclock writer** with journaling, readback verification, rollback, and a tested **Tuning OFF → OEM Stock restore**. Automatic recovery is not guaranteed after all types of crashes/reboots; failure must remain visible.

Keep all PRs draft until physical-device checks pass.
