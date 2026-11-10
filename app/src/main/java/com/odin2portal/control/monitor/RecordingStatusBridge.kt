package com.odin2portal.control.monitor

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process

/**
 * Private, same-application IPC status endpoint.
 *
 * The recording foreground service and this provider live in :monitor.
 * The UI / Quick Settings tiles live in the normal app process. A singleton
 * StateFlow cannot cross this process boundary: read the actual service
 * snapshot here rather than pretending a local default state means "stopped".
 *
 * No tuning, sysfs writing, or externally exported interfaces.
 */
class RecordingStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SNAPSHOT) return super.call(method, arg, extras)
        val app = context ?: return Bundle().apply {
            putString(KEY_ERROR, "Recording provider context unavailable")
        }
        val s = GameplayRuntime.state.value
        val journal = SessionReliabilityJournal(app)
        val last = s.latestSample
        return Bundle().apply {
            putBoolean("running", s.running)
            putBoolean("starting", s.starting)
            putLong("sessionId", s.sessionId ?: -1L)
            putLong("startedAt", s.startedAt ?: -1L)
            putLong("lastSampleAt", s.lastSampleAt ?: -1L)
            putString("foreground", s.foregroundLabel)
            putString("attribution", s.attribution)
            putInt("intervals", s.intervalsSaved)
            putString("cpuDisplay", last?.get(Metric.SOC_TEMP)?.display)
            putString("gpuDisplay", last?.get(Metric.GPU_TEMP)?.display)
            putString("hud", s.hudDiagnostic)
            putString("message", s.message)
            putInt("pid", Process.myPid())
            putBoolean("recoveryPending", journal.lease() != null)
            putString("journalStatus", journal.status())
            putStringArrayList("journalEvents", ArrayList(journal.events()))
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<String>?): Int = 0

    companion object {
        const val METHOD_SNAPSHOT = "status"
        const val KEY_ERROR = "error"
    }
}

/**
 * Client in the UI/Quick Settings process; queries the in-process provider on
 * the remote :monitor host. Binder results are bounded, read-only and contain
 * no fabricated samples. Polling is done ONLY while a UI is on screen.
 */
object RecordingStatusBridge {
    fun read(context: Context): GameplayStatus {
        val uri = Uri.parse("content://" + context.packageName + ".monitor.status")
        val reply = try {
            context.contentResolver.call(uri, RecordingStatusProvider.METHOD_SNAPSHOT, null, null)
        } catch (e: Exception) {
            return GameplayStatus(
                message = "Recorder status unavailable: " + (e.message ?: e.javaClass.simpleName),
                lifecycleStatus = "Cannot query monitoring process"
            )
        } ?: return GameplayStatus(
            message = "Recorder status provider returned no data",
            lifecycleStatus = "Monitoring process unavailable"
        )
        reply.getString(RecordingStatusProvider.KEY_ERROR)?.let {
            return GameplayStatus(message = it, lifecycleStatus = it)
        }
        val reportedSampleAt = reply.getLong("lastSampleAt", -1L).takeIf { it > 0L }
        val active = reply.getBoolean("running", false)
        val sampleDelay = reportedSampleAt?.let {
            (System.currentTimeMillis() - it).coerceAtLeast(0L)
        }
        val stalled = active && sampleDelay != null && sampleDelay > 20_000L
        return GameplayStatus(
            running = active,
            starting = reply.getBoolean("starting", false),
            sessionId = reply.getLong("sessionId", -1L).takeIf { it > 0L },
            startedAt = reply.getLong("startedAt", -1L).takeIf { it > 0L },
            lastSampleAt = reportedSampleAt,
            foregroundLabel = reply.getString("foreground") ?: "Not identified",
            attribution = reply.getString("attribution") ?: "Unknown",
            intervalsSaved = reply.getInt("intervals", 0),
            // MonitorSample is intentionally not marshalled over Binder; latest
            // verified visible metrics are passed as display strings instead.
            latestSample = null,
            hudDiagnostic = reply.getString("hud"),
            message = if (stalled) {
                "Monitoring process alive, but no new telemetry for " +
                    (sampleDelay!! / 1000L) + " seconds"
            } else reply.getString("message"),
            cpuDisplay = reply.getString("cpuDisplay"),
            gpuDisplay = reply.getString("gpuDisplay"),
            lifecycleStatus = reply.getString("journalStatus"),
            lifecycleEvents = reply.getStringArrayList("journalEvents")?.toList().orEmpty(),
            monitorProcessId = reply.getInt("pid", -1).takeIf { it > 0 },
            recoveryPending = reply.getBoolean("recoveryPending", false)
        )
    }

    fun diagnosticReport(status: GameplayStatus): String = buildString {
        appendLine("O2P RECORDER PROCESS / LIFECYCLE REPORT")
        appendLine("No hardware tuning or sysfs writes by this build.")
        appendLine("Service process pid: " + (status.monitorProcessId?.toString() ?: "unavailable"))
        appendLine("Service running: " + status.running + "; starting: " + status.starting)
        appendLine("Session ID: " + (status.sessionId?.toString() ?: "none"))
        appendLine("Start epoch ms: " + (status.startedAt?.toString() ?: "unknown"))
        appendLine("Latest sample epoch ms: " + (status.lastSampleAt?.toString() ?: "none"))
        appendLine("Persisted recovery lease: " + status.recoveryPending)
        appendLine("Foreground package or label: " + status.foregroundLabel)
        appendLine("Intervals saved: " + status.intervalsSaved)
        appendLine("Status: " + (status.lifecycleStatus ?: "unavailable"))
        appendLine("Message: " + (status.message ?: "none"))
        appendLine("HUD: " + (status.hudDiagnostic ?: "unknown"))
        appendLine("Recent lifecycle events:")
        if (status.lifecycleEvents.isEmpty()) appendLine("(none reported)")
        else status.lifecycleEvents.forEach(::appendLine)
    }
}
