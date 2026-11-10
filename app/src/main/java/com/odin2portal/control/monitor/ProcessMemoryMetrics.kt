package com.odin2portal.control.monitor

import android.os.Bundle
import android.os.Debug
import android.os.Process
import java.io.File
import java.util.Locale

/**
 * On-demand RAM footprint of THIS Android process, not global used RAM.
 * Collect only when the user exports a report: smaps/PSS scans can be costly,
 * and should not run every telemetry tick during an emulator launch.
 */
object ProcessMemoryMetrics {
    fun capture(): Bundle {
        val result = Bundle()
        result.putInt("pid", Process.myPid())
        try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            result.putInt("pssKB", info.totalPss)
            result.putInt("privateDirtyKB", info.totalPrivateDirty)
            result.putInt("swapPssKB", info.totalSwappedOutPss)
        } catch (e: Exception) {
            result.putString("error", e.javaClass.simpleName + ": " + (e.message ?: "memory unavailable"))
        }
        // RSS includes shared pages in full and is not additive across processes.
        val status = try {
            File("/proc/self/status").useLines { lines ->
                lines.filter {
                    it.startsWith("VmRSS:") || it.startsWith("VmHWM:")
                }.toList()
            }
        } catch (_: Exception) { emptyList() }
        for (line in status) {
            val value = line.substringAfter(':').trim().substringBefore(' ')
                .toLongOrNull() ?: continue
            if (line.startsWith("VmRSS:")) result.putLong("rssKB", value)
            if (line.startsWith("VmHWM:")) result.putLong("peakRssKB", value)
        }
        return result
    }

    fun describe(label: String, metrics: Bundle?): String = buildString {
        appendLine(label)
        if (metrics == null) {
            appendLine("Unavailable (process inaccessible or stopped)")
            return@buildString
        }
        appendLine("PID: " + metrics.getInt("pid", -1))
        for ((key, title) in listOf(
            "pssKB" to "PSS (proportional physical memory)",
            "privateDirtyKB" to "Private dirty memory",
            "swapPssKB" to "Proportional swap memory",
            "rssKB" to "RSS (shared memory double-counted)",
            "peakRssKB" to "Peak RSS since process creation"
        )) {
            if (metrics.containsKey(key)) {
                val kb = if (key.endsWith("KB") && key != "pssKB" && key != "privateDirtyKB" && key != "swapPssKB")
                    metrics.getLong(key) else metrics.getInt(key).toLong()
                appendLine(title + ": " +
                    String.format(Locale.US, "%.1f MiB", kb / 1024.0))
            }
        }
        metrics.getString("error")?.let { appendLine("Error: " + it) }
    }
}
