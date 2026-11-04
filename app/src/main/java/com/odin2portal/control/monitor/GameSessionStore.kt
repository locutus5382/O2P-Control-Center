package com.odin2portal.control.monitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class SavedGameSession(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String,
    val manualLabel: String,
    val recordIntervalMs: Long,
    val intervalCount: Int
)

/**
 * App-private SQLite history; no external storage permissions and no network.
 * Mutated only by the gameplay service (one sequential writer), and read by
 * the Activity. Rows are committed as their aggregation window completes.
 */
class GameSessionStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "o2p_gameplay_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE game_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                status TEXT NOT NULL,
                manual_label TEXT NOT NULL,
                record_interval_ms INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE game_intervals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                interval_ms INTEGER NOT NULL,
                app_label TEXT NOT NULL,
                app_package TEXT,
                attribution TEXT NOT NULL,
                samples INTEGER NOT NULL,
                readings_json TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_game_intervals_session ON game_intervals(session_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No upgrades yet; do not destroy user recordings")
    }

    fun start(startTime: Long, manualLabel: String, intervalMs: Long): Long {
        require(RecordingIntervals.valid(intervalMs))
        val content = ContentValues().apply {
            put("started_at", startTime)
            put("status", "RECORDING")
            put("manual_label", manualLabel.take(64))
            put("record_interval_ms", intervalMs)
        }
        val id = writableDatabase.insertOrThrow("game_sessions", null, content)
        require(id >= 1L)
        return id
    }

    fun append(sessionId: Long, records: List<RecordedInterval>) {
        if (records.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (row in records) {
                val values = ContentValues().apply {
                    put("session_id", sessionId)
                    put("start_time", row.startTimeMs)
                    put("end_time", row.endTimeMs)
                    put("interval_ms", row.configuredIntervalMs)
                    put("app_label", row.label.take(64))
                    put("app_package", row.packageName?.take(256))
                    put("attribution", row.attribution.name)
                    put("samples", row.sampleCount)
                    put("readings_json", encode(row.values))
                }
                db.insertOrThrow("game_intervals", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun finish(sessionId: Long, endTime: Long, status: String = "COMPLETED") {
        require(status in listOf("COMPLETED", "INTERRUPTED", "FAILED"))
        val values = ContentValues().apply {
            put("ended_at", endTime)
            put("status", status)
        }
        writableDatabase.update(
            "game_sessions", values, "id=? AND status='RECORDING'", arrayOf(sessionId.toString())
        )
        prune()
    }

    /** Run on a fresh process or before starting a new recording, not while another service is active. */
    fun recoverInterrupted(now: Long) {
        val values = ContentValues().apply {
            put("ended_at", now)
            put("status", "INTERRUPTED")
        }
        writableDatabase.update("game_sessions", values, "status='RECORDING'", null)
    }

    fun list(limit: Int = 20): List<SavedGameSession> {
        require(limit in 1..100)
        val sessions = mutableListOf<SavedGameSession>()
        val db = readableDatabase
        db.rawQuery(
            """SELECT s.id,s.started_at,s.ended_at,s.status,s.manual_label,s.record_interval_ms,
               (SELECT COUNT(*) FROM game_intervals r WHERE r.session_id=s.id)
               FROM game_sessions s ORDER BY s.id DESC LIMIT ?""".trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                sessions.add(
                    SavedGameSession(
                        id = c.getLong(0), startedAt = c.getLong(1),
                        endedAt = if (c.isNull(2)) null else c.getLong(2),
                        status = c.getString(3), manualLabel = c.getString(4),
                        recordIntervalMs = c.getLong(5), intervalCount = c.getInt(6)
                    )
                )
            }
        }
        return sessions
    }

    fun read(sessionId: Long, maxRows: Int = 5000): List<RecordedInterval> {
        require(maxRows in 1..10000)
        val data = mutableListOf<RecordedInterval>()
        readableDatabase.query(
            "game_intervals",
            arrayOf("start_time", "end_time", "interval_ms", "app_label", "app_package", "attribution", "samples", "readings_json"),
            "session_id=?", arrayOf(sessionId.toString()), null, null, "id ASC",
            maxRows.toString()
        ).use { c ->
            while (c.moveToNext()) {
                val attribution = runCatching { AppAttribution.valueOf(c.getString(5)) }
                    .getOrDefault(AppAttribution.NOT_AVAILABLE)
                data.add(
                    RecordedInterval(
                        startTimeMs = c.getLong(0),
                        endTimeMs = c.getLong(1),
                        configuredIntervalMs = c.getLong(2),
                        label = c.getString(3),
                        packageName = if (c.isNull(4)) null else c.getString(4),
                        attribution = attribution,
                        sampleCount = c.getInt(6),
                        values = decode(c.getString(7))
                    )
                )
            }
        }
        return data
    }

    fun delete(sessionId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("game_intervals", "session_id=?", arrayOf(sessionId.toString()))
            db.delete("game_sessions", "id=? AND status!='RECORDING'", arrayOf(sessionId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun prune() {
        val db = writableDatabase
        // Keep 20 most recent session records. Delete all old associated windows.
        val ids = mutableListOf<Long>()
        db.rawQuery("SELECT id FROM game_sessions ORDER BY id DESC LIMIT -1 OFFSET 20", null)
            .use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in ids) {
                db.delete("game_intervals", "session_id=?", arrayOf(id.toString()))
                db.delete("game_sessions", "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    companion object {
        /** Pure JSON serializers can be unit-tested without SQLite or a device. */
        fun encode(values: Map<Metric, SummaryValue>): String {
            val root = JSONObject()
            for ((metric, reading) in values) {
                val obj = JSONObject()
                obj.put("latest", reading.latest ?: JSONObject.NULL)
                obj.put("avg", reading.average ?: JSONObject.NULL)
                obj.put("min", reading.minimum ?: JSONObject.NULL)
                obj.put("max", reading.maximum ?: JSONObject.NULL)
                obj.put("unit", reading.unit ?: JSONObject.NULL)
                obj.put("count", reading.numericalObservations)
                root.put(metric.name, obj)
            }
            return root.toString()
        }

        fun decode(json: String): Map<Metric, SummaryValue> {
            val root = JSONObject(json)
            return Metric.entries.mapNotNull { metric ->
                val obj = root.optJSONObject(metric.name) ?: return@mapNotNull null
                metric to SummaryValue(
                    latest = if (obj.isNull("latest")) null else obj.optString("latest"),
                    average = if (obj.isNull("avg")) null else obj.optDouble("avg"),
                    minimum = if (obj.isNull("min")) null else obj.optDouble("min"),
                    maximum = if (obj.isNull("max")) null else obj.optDouble("max"),
                    unit = if (obj.isNull("unit")) null else obj.optString("unit"),
                    numericalObservations = obj.optInt("count", 0)
                )
            }.toMap()
        }
    }
}
