package com.odin2portal.control.monitor

import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * Crash-resistant, app-private lifecycle breadcrumb trail. A session is only
 * considered LIVE when a running service confirms it; the persisted lease is
 * a recovery hint, never proof that monitoring is presently active.
 */
data class SessionLease(
    val sessionId: Long,
    val startedAt: Long,
    val heartbeatAt: Long,
    val sampleMs: Long,
    val historyMs: Long,
    val manualLabel: String,
    val stopRequested: Boolean,
    val processId: Int,
    val lastEvent: String
)

class SessionReliabilityJournal(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "o2p_recording_recovery_v1", Context.MODE_PRIVATE
    )

    fun lease(): SessionLease? = runCatching {
        val raw = prefs.getString("lease", null) ?: return@runCatching null
        val data = JSONObject(raw)
        val value = SessionLease(
            sessionId = data.optLong("id", 0),
            startedAt = data.optLong("started", 0),
            heartbeatAt = data.optLong("beat", 0),
            sampleMs = data.optLong("sample", 0),
            historyMs = data.optLong("history", 0),
            manualLabel = data.optString("label").take(64),
            stopRequested = data.optBoolean("stop", false),
            processId = data.optInt("pid", -1),
            lastEvent = data.optString("event")
        )
        value.takeIf { it.sessionId > 0 && it.startedAt > 0 &&
            it.heartbeatAt >= it.startedAt &&
            it.sampleMs in listOf(1000L, 2000L, 5000L) &&
            RecordingIntervals.valid(it.historyMs)
        }
    }.getOrNull()

    fun begin(value: SessionLease) {
        save(value.copy(lastEvent = "SERVICE_STARTED", processId = Process.myPid()))
        event("SERVICE_STARTED", "recording=" + value.sessionId)
    }

    fun heartbeat(sessionId: Long, now: Long) {
        val state = lease()?.takeIf { it.sessionId == sessionId && !it.stopRequested } ?: return
        // A single asynchronous preference write every 10s. No synchronous I/O per sample.
        save(state.copy(heartbeatAt = now, processId = Process.myPid()))
    }

    fun stopRequested() {
        val current = lease() ?: return
        save(current.copy(stopRequested = true, lastEvent = "STOP_REQUESTED"))
        event("STOP_REQUESTED", "recording=" + current.sessionId)
    }

    fun finish(sessionId: Long, status: String, detail: String) {
        event(status, detail.take(140))
        if (lease()?.sessionId == sessionId) prefs.edit().remove("lease").commit()
    }

    fun markOrphaned(reason: String) {
        event("ORPHANED", reason.take(140))
        prefs.edit().remove("lease").commit()
    }

    fun event(type: String, detail: String = "") {
        val entries = events().takeLast(23)
        val updated = JSONArray()
        (entries + (System.currentTimeMillis().toString() + " | " +
            type.take(32) + " | " + detail.take(140))).forEach(updated::put)
        prefs.edit().putString("events", updated.toString()).apply()
    }

    fun events(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("events", "[]") ?: "[]")
        (0 until array.length().coerceAtMost(24)).map { array.optString(it) }
    }.getOrDefault(emptyList())

    fun status(now: Long = System.currentTimeMillis()): String {
        val state = lease() ?: return events().lastOrNull() ?: "No session lease recorded"
        val age = (now - state.heartbeatAt).coerceAtLeast(0L) / 1000L
        return if (state.stopRequested) "Stop requested; finalization pending"
        else if (age < 30L) "Recent heartbeat ($age s ago); service confirmation required"
        else "Heartbeat stale ($age s); session may have been interrupted"
    }

    private fun save(value: SessionLease) {
        val o = JSONObject()
            .put("id", value.sessionId)
            .put("started", value.startedAt)
            .put("beat", value.heartbeatAt)
            .put("sample", value.sampleMs)
            .put("history", value.historyMs)
            .put("label", value.manualLabel)
            .put("stop", value.stopRequested)
            .put("pid", value.processId)
            .put("event", value.lastEvent)
        // The process can be killed abruptly under emulator RAM pressure.
        // A tiny 10-secondly synchronous checkpoint avoids losing the lease
        // while SharedPreferences.apply() still has an unwritten disk commit.
        prefs.edit().putString("lease", o.toString()).commit()
    }
}

/** Deterministic restart policy. No boot receiver and no unconditional restart. */
object SessionResumePolicy {
    const val MAX_GAP_MS = 120_000L
    fun shouldResume(lease: SessionLease?, now: Long, isRecordingInDb: Boolean): Boolean {
        if (lease == null || lease.stopRequested || !isRecordingInDb) return false
        if (lease.sessionId <= 0 || lease.heartbeatAt > now + 5_000L) return false
        return now - lease.heartbeatAt in 0L..MAX_GAP_MS
    }
}
