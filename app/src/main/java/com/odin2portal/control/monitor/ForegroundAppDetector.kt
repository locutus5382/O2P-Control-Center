package com.odin2portal.control.monitor

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.content.Intent

/**
 * Optional, OS-permission-gated foreground application attribution.
 *
 * Usage events identify an ACTIVITY/package, not the process, running emulator
 * game, or internal frame rate. No Accessibility, QUERY_ALL_PACKAGES, or root.
 * The detector is only used by a user-started gameplay monitor session.
 */
class ForegroundAppDetector(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var lastProcessedTime = 0L
    private var lastQueryEnd = 0L
    private var activePackage: String? = null
    private var activeActivityClass: String? = null
    /** Increments when a different Activity in the same Eden package is resumed.
     *  This is evidence of an Activity transition, not proof a game started.
     */
    var edenActivityTransitions: Long = 0L
        private set

    fun hasUsageAccess(): Boolean = try {
        appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: RuntimeException) { false }

    fun usageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns verified package context or explicitly unattributed fallback. */
    fun current(now: Long, manualFallback: String): SessionAppContext {
        if (!hasUsageAccess()) {
            activePackage = null
            activeActivityClass = null
            lastProcessedTime = 0L
            lastQueryEnd = 0L
            return SessionAppContext.manuallyEntered(manualFallback)
        }
        if (power?.isInteractive == false || keyguard?.isKeyguardLocked == true) {
            activePackage = null
            lastProcessedTime = 0L
            lastQueryEnd = 0L
            // Lock screen is not a verified active game, even with Usage Access.
            return SessionAppContext.manuallyEntered("")
        }
        if (now < lastProcessedTime || now < lastQueryEnd) {
            activePackage = null
            lastProcessedTime = 0L
            lastQueryEnd = 0L
        }
        // One-time lookback permits picking up foreground activity when recording starts.
        // Subsequent calls query only new usage events (plus small overlap).
        val from = if (lastQueryEnd == 0L) {
            activePackage = null
            (now - 5 * 60_000L).coerceAtLeast(0L)
        } else (lastQueryEnd - 5000L).coerceAtLeast(0L)
        val events = try { usage?.queryEvents(from, now + 1L) } catch (_: RuntimeException) { null }
            ?: return SessionAppContext.manuallyEntered(manualFallback)
        lastQueryEnd = now

        val event = UsageEvents.Event()
        var maxSeen = lastProcessedTime
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp <= lastProcessedTime || event.timeStamp > now) continue
            maxSeen = maxOf(maxSeen, event.timeStamp)
            val pkg = event.packageName?.takeIf { it.isNotBlank() }
            val cls = event.className?.takeIf { it.isNotBlank() }
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg != null) {
                    if (pkg == EDEN_PACKAGE && activePackage == pkg &&
                        activeActivityClass != null && cls != null &&
                        cls != activeActivityClass) {
                        edenActivityTransitions++
                    }
                    activePackage = pkg
                    activeActivityClass = cls
                }
                // Games can resume a second Eden Activity before the previous Activity
                // finishes stopping. A stale STOPPED from the old Activity must not
                // erase a newer RESUMED activity within the same package.
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (pkg == activePackage &&
                    (activeActivityClass == null || cls == activeActivityClass)) {
                    activePackage = null
                    activeActivityClass = null
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN -> {
                    activePackage = null
                    activeActivityClass = null
                }
            }
        }
        lastProcessedTime = maxSeen

        val pkg = activePackage
            ?: return SessionAppContext.manuallyEntered(manualFallback)
        // Android 11+ package visibility may hide metadata: the package name itself
        // is a reliable identifier; a missing label must never erase attribution.
        val displayName = try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
        return SessionAppContext(
            label = displayName.take(64),
            packageName = pkg.take(256),
            attribution = AppAttribution.USAGE_EVENTS
        )
    }
    companion object {
        const val EDEN_PACKAGE = "dev.eden.eden_emulator.nightly"
    }

}
