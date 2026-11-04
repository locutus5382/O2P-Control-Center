package com.odin2portal.control.monitor

/**
 * Prepares summaries in memory. All timestamps and attribution are explicit.
 *
 * A session label is entered by the user; it is NOT an automatically detected
 * running Android process. Automatic foreground-app detection will require a
 * separate opt-in background monitor and an appropriate trusted backend.
 */
enum class AppAttribution(val description: String) {
    MANUAL_LABEL("User-entered label"),
    NOT_AVAILABLE("No application identified")
}

data class SummaryValue(
    val latest: String?,
    val average: Double?,
    val minimum: Double?,
    val maximum: Double?,
    val unit: String?,
    val numericalObservations: Int
)

data class RecordedInterval(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val configuredIntervalMs: Long,
    val label: String,
    val attribution: AppAttribution,
    val sampleCount: Int,
    val values: Map<Metric, SummaryValue>
)

object RecordingIntervals {
    val choicesMs = listOf(5_000L, 10_000L, 30_000L, 120_000L)
    const val defaultMs = 10_000L

    fun valid(ms: Long): Boolean = ms in choicesMs
    fun minutesCovered(ms: Long, records: Int = 600): Int =
        if (!valid(ms) || records < 0) 0 else (ms * records / 60_000L).toInt()
}

private data class NumericReading(val value: Double, val unit: String)

/** Only single, explicit numeric values may be averaged. Never average compound clock lists. */
private object NumericParser {
    private val numeric = Regex("""^(-?\d+(?:\.\d+)?)\s*(%|°C|MHz|GHz|RPM)$""")
    fun parse(raw: String?): Pair<Double, String>? {
        val found = raw?.let { numeric.matchEntire(it.trim()) } ?: return null
        val n = found.groupValues[1].toDoubleOrNull() ?: return null
        if (!n.isFinite()) return null
        return n to found.groupValues[2]
    }
}

private class MetricAccumulator {
    var latest: String? = null
    var count = 0
    var sum = 0.0
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    var unit: String? = null

    fun add(raw: String?) {
        latest = raw
        val parsed = NumericParser.parse(raw) ?: return
        if (unit != null && unit != parsed.second) return
        unit = parsed.second
        count += 1
        sum += parsed.first
        min = minOf(min, parsed.first)
        max = maxOf(max, parsed.first)
    }
    fun freeze() = SummaryValue(
        latest = latest,
        average = if (count > 0) sum / count else null,
        minimum = if (count > 0) min else null,
        maximum = if (count > 0) max else null,
        unit = unit,
        numericalObservations = count
    )
}

/**
 * Bounded, opt-in, time-window aggregation independent from live polling.
 * Elapsed time boundaries use actual sample timestamps, not a counter.
 * On stop, close the partial window. No periodic file writes or system access.
 */
class SessionHistory(
    intervalMs: Long = RecordingIntervals.defaultMs,
    private val maxRows: Int = 600
) {
    init {
        require(RecordingIntervals.valid(intervalMs))
        require(maxRows > 0)
    }

    var recordingIntervalMs: Long = intervalMs
        private set
    private val stored = ArrayDeque<RecordedInterval>()
    private var begin: Long? = null
    private var end: Long = 0L
    private var samples: Int = 0
    private var label: String = ""
    private var readings = mutableMapOf<Metric, MetricAccumulator>()
    var droppedRows: Long = 0
        private set

    val rowCount: Int get() = stored.size
    val pendingSampleCount: Int get() = samples

    fun snapshot(): List<RecordedInterval> = stored.toList()

    fun reset() {
        stored.clear()
        droppedRows = 0
        discardPending()
    }

    fun interval(ms: Long) {
        require(RecordingIntervals.valid(ms))
        if (recordingIntervalMs != ms) {
            closePartial()
            recordingIntervalMs = ms
        }
    }

    /** The label is manually entered and cannot be mistaken for autodetection. */
    fun offer(sample: MonitorSample, enteredLabel: String) {
        val normalized = enteredLabel.trim().take(64)
        // A new session or time reversal must never silently mix readings.
        if (begin != null && (sample.timestamp < end ||
                    sample.timestamp - begin!! >= recordingIntervalMs ||
                    normalized != label)) {
            closePartial()
        }
        if (begin == null) {
            begin = sample.timestamp
            end = sample.timestamp
            label = normalized
        }
        end = sample.timestamp
        samples++
        for (metric in Metric.entries) {
            readings.getOrPut(metric) { MetricAccumulator() }.add(sample.get(metric).value)
        }
    }

    fun closePartial() {
        val start = begin ?: return
        if (samples > 0) {
            val attribution = if (label.isBlank()) AppAttribution.NOT_AVAILABLE
                              else AppAttribution.MANUAL_LABEL
            val row = RecordedInterval(
                startTimeMs = start,
                endTimeMs = end,
                configuredIntervalMs = recordingIntervalMs,
                label = label.ifBlank { "Not specified" },
                attribution = attribution,
                sampleCount = samples,
                values = readings.mapValues { it.value.freeze() }
            )
            if (stored.size >= maxRows) {
                stored.removeFirst()
                droppedRows++
            }
            stored.addLast(row)
        }
        discardPending()
    }

    private fun discardPending() {
        begin = null
        end = 0L
        samples = 0
        label = ""
        readings = mutableMapOf()
    }
}
