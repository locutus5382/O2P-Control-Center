package com.odin2portal.control

import com.odin2portal.control.monitor.Metric
import com.odin2portal.control.monitor.MonitorCsv
import com.odin2portal.control.monitor.MonitorSample
import com.odin2portal.control.monitor.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorCsvTest {
    @Test fun emptyReportContainsOnlyColumnNames() {
        val csv = MonitorCsv.format(emptyList())
        assertTrue(csv.startsWith("\"Timestamp (UTC)\","))
        assertEquals(1, csv.trimEnd('\r', '\n').split("\r\n").size)
    }

    @Test fun preservesMissingReadingsAndUtcTimestamps() {
        val sample = MonitorSample(0L, mapOf(Metric.FPS to Reading("30")))
        val csv = MonitorCsv.format(listOf(sample))
        assertTrue(csv.contains("\"1970-01-01T00:00:00.000Z\""))
        assertTrue(csv.contains("\"30\""))
        assertTrue(csv.contains("\"Unavailable\""))
    }

    @Test fun escapesCsvFieldsAndDisarmsSpreadsheetFormulas() {
        val sample = MonitorSample(0L, mapOf(
            Metric.FPS to Reading("61\" frames"),
            Metric.RAM to Reading("=HYPERLINK(\"http://bad.test\")")
        ))
        val csv = MonitorCsv.format(listOf(sample))
        assertTrue(csv.contains("\"61\"\" frames\""))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://bad.test\"\")\""))
    }
}
