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
    private var activePackage: String? = null

    fun hasUsageAccess(): Boolean = try {
        appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: RuntimeException) { false }

    fun usageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns verified package context or explicitly unattributed fallback. */
    fun current(now: Long, manualFallback: String): SessionAppContext {
        if (!hasUsageAccess() || power?.isInteractive == false || keyguard?.isKeyguardLocked == true) {
            activePackage = null
            lastProcessedTime = 0L
            return SessionAppContext.manuallyEntered(manualFallback)
        }
        // One-time lookback permits picking up the foreground activity before monitoring started.
        // Subsequent calls query only new usage events (plus a small time overlap).
        val from = if (lastProcessedTime == 0L || now < lastProcessedTime) {
            activePackage = null
            (now - 5 * 60_000L).coerceAtLeast(0L)
        } else (lastProcessedTime - 1000L).coerceAtLeast(0L)
        val events = try { usage?.queryEvents(from, now + 1L) } catch (_: RuntimeException) { null }
            ?: return SessionAppContext.manuallyEntered(manualFallback)

        val event = UsageEvents.Event()
        var maxSeen = lastProcessedTime
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp <= lastProcessedTime || event.timeStamp > now) continue
            maxSeen = maxOf(maxSeen, event.timeStamp)
            val pkg = event.packageName?.takeIf { it.isNotBlank() }
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg != null) activePackage = pkg
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (pkg == activePackage) activePackage = null
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN -> activePackage = null
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
}
