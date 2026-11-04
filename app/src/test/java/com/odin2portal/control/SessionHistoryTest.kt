package com.odin2portal.control

import com.odin2portal.control.monitor.AppAttribution
import com.odin2portal.control.monitor.Metric
import com.odin2portal.control.monitor.MonitorCsv
import com.odin2portal.control.monitor.MonitorSample
import com.odin2portal.control.monitor.Reading
import com.odin2portal.control.monitor.RecordingIntervals
import com.odin2portal.control.monitor.SessionHistory
import com.odin2portal.control.monitor.SessionAppContext
import org.junit.Assert.*
import org.junit.Test

class SessionHistoryTest {
    private fun sample(t: Long, cpu: String? = null) = MonitorSample(
        timestamp = t,
        readings = mapOf(Metric.CPU_LOAD to Reading(cpu))
    )

    @Test fun fiveSecondHistoryAggregatesTwoSecondPolling() {
        val recorder = SessionHistory(5_000L)
        recorder.offer(sample(0L, "10%"), "Eden")
        recorder.offer(sample(2_000L, "20%"), "Eden")
        recorder.offer(sample(4_000L, "30%"), "Eden")
        recorder.offer(sample(6_000L, "40%"), "Eden") // closes first interval
        val first = recorder.snapshot().single()
        assertEquals(3, first.sampleCount)
        assertEquals(5_000L, first.configuredIntervalMs)
        assertEquals("Eden", first.label)
        assertEquals(AppAttribution.MANUAL_LABEL, first.attribution)
        val load = first.values[Metric.CPU_LOAD]!!
        assertEquals(20.0, load.average!!, 0.0001)
        assertEquals(10.0, load.minimum!!, 0.0001)
        assertEquals(30.0, load.maximum!!, 0.0001)
        assertEquals("%", load.unit)
        recorder.closePartial()
        assertEquals(2, recorder.rowCount)
        assertEquals("40%", recorder.snapshot().last().values[Metric.CPU_LOAD]?.latest)
    }

    @Test fun twoHourSessionAtTwoMinutesKeepsSixtyRows() {
        val recorder = SessionHistory(120_000L)
        for (t in 0L until 7_200_000L step 2_000L) recorder.offer(sample(t, "50%"), "Eden")
        recorder.closePartial()
        assertEquals(60, recorder.rowCount)
        assertEquals(0L, recorder.droppedRows)
        assertEquals(20 * 60, RecordingIntervals.minutesCovered(120_000L))
        assertEquals(60, recorder.snapshot().first().sampleCount)
    }

    @Test fun boundedHistoryDropsOldestNotNewest() {
        val recorder = SessionHistory(intervalMs = 5_000L, maxRows = 2)
        for (t in listOf(0L, 5000L, 10000L, 15000L)) recorder.offer(sample(t, "60%"), "")
        recorder.closePartial()
        assertEquals(2, recorder.rowCount)
        assertEquals(2L, recorder.droppedRows)
        assertEquals(10000L, recorder.snapshot().first().startTimeMs)
        assertEquals(AppAttribution.NOT_AVAILABLE, recorder.snapshot().first().attribution)
    }

    @Test fun intervalChangesClosePartialBucket() {
        val recorder = SessionHistory(5_000L)
        recorder.offer(sample(100L, "21%"), "Eden")
        recorder.interval(120_000L)
        assertEquals(1, recorder.rowCount)
        assertEquals(5000L, recorder.snapshot().first().configuredIntervalMs)
        recorder.offer(sample(200L, "22%"), "Eden")
        recorder.closePartial()
        assertEquals(2, recorder.rowCount)
        assertEquals(120000L, recorder.snapshot().last().configuredIntervalMs)
    }

    @Test fun missingValuesStayUnavailableAndManualLabelsExportSafely() {
        val recorder = SessionHistory(10_000L)
        recorder.offer(sample(0L, null), "=HYPERLINK(\"evil\")")
        recorder.closePartial()
        val value = recorder.snapshot().single().values[Metric.CPU_LOAD]!!
        assertNull(value.average)
        assertNull(value.minimum)
        assertNull(value.maximum)
        val csv = MonitorCsv.formatHistory(recorder.snapshot())
        assertTrue(csv.contains("User-entered label"))
        assertTrue(csv.contains("Unavailable"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"evil\"\")\""))
        assertTrue(csv.contains("\"10\"")) // configured seconds
    }

    @Test fun foregroundPackageChangeSplitsHistoryAndPreservesSource() {
        val recorder = SessionHistory(120_000L)
        val eden = SessionAppContext("Eden", "dev.eden.emulator", AppAttribution.USAGE_EVENTS)
        val launcher = SessionAppContext("Launcher", "com.launcher", AppAttribution.USAGE_EVENTS)
        recorder.offer(sample(0L, "50%"), eden)
        recorder.offer(sample(2_000L, "60%"), eden)
        recorder.offer(sample(4_000L, "10%"), launcher)
        recorder.closePartial()
        assertEquals(2, recorder.rowCount)
        val entries = recorder.snapshot()
        assertEquals("dev.eden.emulator", entries[0].packageName)
        assertEquals(AppAttribution.USAGE_EVENTS, entries[0].attribution)
        assertEquals(2, entries[0].sampleCount)
        assertEquals("com.launcher", entries[1].packageName)
        assertEquals(1, entries[1].sampleCount)
        val csv = MonitorCsv.formatHistory(entries)
        assertTrue(csv.contains("dev.eden.emulator"))
        assertTrue(csv.contains("com.launcher"))
    }

    @Test fun automaticAttributionWithoutPackageIsRejected() {
        val history = SessionHistory()
        try {
            history.offer(sample(0L), SessionAppContext("Eden", null, AppAttribution.PRIVILEGED_TASK))
            fail("Missing detected package should have thrown")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, history.rowCount)
        }
    }
}
