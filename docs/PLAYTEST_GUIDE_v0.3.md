# O2P Control Center v0.3 — Odin 2 Portal Playtest

**Status:** Experimental APK, build-tested on GitHub Actions but **not yet tested on an Odin 2 Portal**. Your old O2PKernelTurb app is untouched. This alpha is completely **read-only**: it cannot underclock or modify device, fan, charging, or OLED controls.

## Install

1. In the [Android debug build for the gameplay branch](https://github.com/locutus5382/O2P-Control-Center/actions), find the latest **successful** run whose branch is `feature/background-gameplay-monitor`.
2. At the bottom under **Artifacts**, download `O2P-Control-Center-debug`, unzip it and install `app-debug.apk` on the Portal. The app identifies itself as O2P Control Center.
3. If Android asks to approve APK installation for the browser/file manager, use its **Install unknown apps** setting and disable afterward if desired.

## A. Permission and foreground-service smoke test (2–3 minutes)

1. Open **O2P Control Center → Monitor → Gameplay Recording**.
2. Tap **Grant Usage Access in Android Settings**; select **O2P Control Center** and allow usage access. Return to the app. This is optional for recording, but required to automatically identify foreground app *packages*. It does not grant kernel/root control.
3. Tap **Allow monitor notifications** if shown (Android 13+). We require this so the **Stop** action stays visible.
4. Select **Live sample interval = 2s** and **History interval = 10s**. Do not enable the separate on-screen recorder: the gameplay session has its own recorder.
5. Tap **Start gameplay recording**. Confirm O2P says **RECORDING** and Android displays an ongoing O2P notification with **Stop**.

**If no notification appears, or the app shows an error, stop here and send the exact message.** We should not silently record in the background.

## B. Identify Eden and save telemetry

1. Leave O2P running via Home, then open **Eden**. Keep a game running for approximately 2–5 minutes.
2. Return to O2P via its notification or launcher. The service should still show **RECORDING**. It may temporarily identify **O2P Control Center** itself because the foreground application changed when you returned.
3. Tap **Stop gameplay recording** in O2P (or the notification's **Stop**).
4. Under **Saved Gameplay Sessions**, tap **Refresh**, choose the latest session, then **View session**. Confirm there are saved recording windows and inspect **Detected foreground apps**.
5. Use **Export selected session CSV** and choose a local file location. The CSV has UTC start/end times, app labels, verified package IDs where available, attribution sources, and per-metric last/mean/min/max values.
6. If the device successfully identified Eden, the report should show its actual Android package ID, not necessarily the emulator's in-game title. We **must not infer Eden** from a manually typed label.

For a longer run, set **History interval = 2m** before starting the session. Two hours at two-minute intervals is roughly 60 windows, possibly more if the foreground app changes. Live sampling can stay at 2s independently.

## C. What to send back

- A screenshot of the **Gameplay Recording** card while running.
- A screenshot of the **Saved Gameplay Sessions** view after stopping.
- The exported session CSV (review package names and other device data before sharing).
- The **Direct** and **PServer** diagnostics reports from the Overview screen, if available.
- Your device firmware version and whether the background service stayed alive while Eden was open.

Please describe: Did Android request notification access? Was Usage Access granted? Did the status stay recording after switching to Eden? Which sensors showed actual values versus **Unavailable**?

## Important limitations

- FPS is not measured yet; the app does **not** know Eden's internal frame rate.
- No floating in-game HUD yet: this milestone adds session history while other apps run, plus an ongoing notification.
- Sensor paths may be inaccessible on your firmware without PServer/root. The gameplay service itself currently uses safe **direct reads only**, even if you successfully ran a PServer diagnostic earlier.
- Usage Access identifies foreground **app packages/activities**, not exact Linux processes or the Switch game running inside Eden.
- If Usage Access is not granted, manual labels are recorded as manual. The app does not claim to have detected Eden.
- Android/AYN battery restrictions may still stop a user-started foreground service unexpectedly; recorded completed windows remain in the private database, but the final partial window can be lost if the process is killed without cleanup.
- Sessions are stored locally, not in a cloud account. The app retains the last 20 sessions and up to 5,000 intervals per session. A session can be deleted from the app (after confirmation).
- The `specialUse` foreground-service category requires additional review if this APK is ever distributed through Google Play; this GitHub alpha is for device testing.

## Troubleshooting

- **Cannot start:** Check notification permission in Android Settings → Apps → O2P Control Center and return to the app to retry.
- **Package names unavailable:** Verify Usage Access; some OEM builds delay or restrict usage events.
- **Monitoring stops unexpectedly:** Check device battery optimization/background restrictions. Report the duration and any system message; don't disable device security to troubleshoot.
- **Sensor Unavailable:** Share a diagnostic report so we can verify the actual Portal kernel nodes rather than invent a reading.
- **No historical records:** Leave the app recording for at least one configured interval, then Stop and Refresh. The last partial interval is normally saved on Stop.
