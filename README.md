# O2P Control Center

**New application derived from the O2PKernelTurb concept.** First working source milestone: read-only hardware explorer for the AYN Odin 2 Portal Base (Snapdragon 8 Gen 2, 8 GB RAM).

![Stage](https://img.shields.io/badge/stage-read--only%20alpha-informational)

## What it does

- Scans actual CPU frequency policies and displays live frequency, min/max and hardware frequency levels where exposed.
- Reads Adreno GPU stats, supported raw frequency nodes and devfreq information when available.
- Enumerates named thermal zones; does **not** assume zone0 is the SoC temperature.
- Reads `/proc/meminfo` and memory-pressure metrics.
- Can attempt **explicitly requested** PServer or root (`su`) inspection, using the original app's PServer transaction format. It never executes writes.
- Exports a diagnostic report using Android's share sheet.

## Build

1. Open this directory as a project in Android Studio with Android SDK 35, Java 17 and Gradle 8.9 installed.
2. Sync the Gradle project and run `:app:testDebugUnitTest` and `:app:assembleDebug` using the IDE/installed Gradle. The Gradle wrapper JAR is not included; a local Gradle installation or the GitHub Actions workflow is required.
3. Or push this project to a GitHub repository: **Actions → Android debug build → Run workflow**. Download the `O2P-Control-Center-debug` artifact after its build succeeds.
4. Install the resulting `app-debug.apk` on the Odin as a separate app.

### Important

This project has **not yet been APK-compiled** because the current source-generation environment lacks Android SDK and Gradle. The CLI Kotlin compiler is used for syntax checks of Android-free components. Android hardware and PServer behavior must be verified on the user's device. See [implementation status](docs/IMPLEMENTATION_STATUS.md).

### Privileged access

Select Direct for ordinary Android app reads. Select PServer to probe `ServiceManager.getService("PServerBinder")`; a valid command result and reported UID are shown separately. Select Root only if you want a `su` prompt. This is a read-only app. PServer is a firmware-specific, non-public interface and might be unavailable or change.

### Safety

- This alpha **never writes to `/sys` or `/proc`**; it cannot underclock or change OEM thermal controls.
- AutoPilot is not represented as operational.
- Do not run two future frequency controllers simultaneously.
- Do not publish the exported diagnostics without reviewing for device-identifying information.

## Engineering notes

Read the [status and next steps](docs/IMPLEMENTATION_STATUS.md). A separate technical design was prepared as `O2P_Control_Center_Technical_Specification.md` (if included with this archive).
