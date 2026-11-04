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

    /**
     * Per-interval summary export. "App label" is user input; it is not claimed
     * to be an automatically detected foreground process. Mean/min/max cover
     * only parsable numeric sensor values; unavailable values stay unavailable.
     */
    fun formatHistory(rows: List<RecordedInterval>): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val fields = listOf(
            "Window start UTC", "Window end UTC", "Configured interval seconds",
            "App/session label", "Attribution", "Live samples in window"
        ) + Metric.entries.flatMap { metric ->
            listOf(
                "${metric.label} (latest)",
                "${metric.label} (average)",
                "${metric.label} (minimum)",
                "${metric.label} (maximum)",
                "${metric.label} (unit)"
            )
        }
        val header = fields.joinToString(",") { cell(it) }
        val data = rows.joinToString("\r\n") { row ->
            val values = listOf(
                formatter.format(Date(row.startTimeMs)),
                formatter.format(Date(row.endTimeMs)),
                (row.configuredIntervalMs / 1000).toString(),
                row.label, row.attribution.description, row.sampleCount.toString()
            ) + Metric.entries.flatMap { metric ->
                val value = row.values[metric]
                listOf(
                    value?.latest ?: "Unavailable",
                    value?.average?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    value?.minimum?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    value?.maximum?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    value?.unit ?: ""
                )
            }
            values.joinToString(",") { cell(it) }
        }
        return if (data.isEmpty()) header + "\r\n" else header + "\r\n" + data + "\r\n"
    }
}
