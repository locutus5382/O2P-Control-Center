package com.odin2portal.control.monitor

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure CSV formatting for user-initiated export. The app keeps samples in memory;
 * this class does no device or filesystem I/O.
 */
object MonitorCsv {
    private fun cell(value: String): String {
        // Keep exported fields safe when users open CSV in spreadsheet applications.
        val trimmed = value.trimStart()
        val safe = if (trimmed.startsWith("=") || trimmed.startsWith("+") ||
            trimmed.startsWith("-") || trimmed.startsWith("@") ||
            trimmed.startsWith("\t") || trimmed.startsWith("\r")) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    fun format(samples: List<MonitorSample>): String {
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val header = (listOf("Timestamp (UTC)") + Metric.entries.map { it.label })
            .joinToString(",") { cell(it) }
        val rows = samples.joinToString("\r\n") { sample ->
            (listOf(date.format(Date(sample.timestamp))) +
                Metric.entries.map { sample.get(it).value ?: "Unavailable" })
                .joinToString(",") { cell(it) }
        }
        return if (rows.isEmpty()) header + "\r\n" else header + "\r\n" + rows + "\r\n"
    }
}
