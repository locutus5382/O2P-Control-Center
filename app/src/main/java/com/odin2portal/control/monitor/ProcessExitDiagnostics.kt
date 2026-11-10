package com.odin2portal.control.monitor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date

/**
 * Inspect the Android 11+ system record for previous app process deaths.
 *
 * Read-only, user-exported evidence. In particular, a 6 GiB "used" value or a
 * missing CSV interval cannot be treated as proof that LMKD terminated O2P.
 * Some firmware reports LMK as SIGNALED/SIGKILL, and OEM kill tooling may be
 * reported as OTHER or USER_REQUESTED. This report never guesses the cause.
 */
object ProcessExitDiagnostics {
    fun report(context: Context): String = buildString {
        appendLine()
        appendLine("ANDROID PROCESS EXIT HISTORY (SYSTEM-REPORTED)")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendLine("Unavailable: requires Android 11 or newer")
            return@buildString
        }

        val manager = context.getSystemService(ActivityManager::class.java)
        if (manager == null) {
            appendLine("Unavailable: ActivityManager missing")
            return@buildString
        }

        val supportsLmkReason = runCatching {
            ActivityManager.isLowMemoryKillReportSupported()
        }.getOrNull()
        appendLine("Explicit LOW_MEMORY reporting supported: " +
            (supportsLmkReason?.toString() ?: "unknown"))
        val records = try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 12)
        } catch (e: Exception) {
            appendLine("Could not query exit history: " + e.javaClass.simpleName)
            return@buildString
        }

        if (records.isEmpty()) {
            appendLine("No retained process exit records. This does not prove there was no kill.")
            return@buildString
        }
        for (exit in records.take(12)) {
            val time = runCatching {
                DateFormat.getDateTimeInstance().format(Date(exit.timestamp))
            }.getOrElse { exit.timestamp.toString() }
            appendLine(
                "time=" + time +
                " | process=" + (exit.processName ?: "unknown") +
                " | pid=" + exit.pid +
                " | reason=" + reasonLabel(exit.reason) +
                " | status=" + exit.status +
                " | importance=" + exit.importance
            )
        }
        appendLine(
            "A LOW_MEMORY reason is strong evidence of LMK when supported; " +
            "SIGNALED may also include memory kills on devices without explicit reporting. " +
            "No exit history entry, by itself, proves which gameplay transition triggered it."
        )
    }

    internal fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCES"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_" + reason
    }
}
