package com.odin2portal.control.discovery

import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Android's OS-level throttling indicators are not SoC die temperatures. */
data class AndroidThermalEvidence(
    val status: Int?,
    val headroom: Float?,
    val statusReason: String,
    val headroomReason: String
) {
    val statusLabel: String get() = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNAVAILABLE"
    }
    val headroomLabel: String get() =
        headroom?.let { "%.3f".format(java.util.Locale.US, it) } ?: "UNAVAILABLE"
}

object AndroidThermalProbe {
    fun capture(context: Context?): AndroidThermalEvidence {
        if (context == null) return AndroidThermalEvidence(
            null, null, "Application context not provided", "Application context not provided")
        val power = context.getSystemService(PowerManager::class.java)
            ?: return AndroidThermalEvidence(null, null, "PowerManager unavailable",
                "PowerManager unavailable")
        val status = if (Build.VERSION.SDK_INT >= 29)
            runCatching { power.currentThermalStatus }.getOrNull()
        else null
        val statusReason = when {
            Build.VERSION.SDK_INT < 29 -> "Requires Android 10 (API 29)"
            status == null -> "Thermal status query failed"
            else -> "Android PowerManager status; not a temperature"
        }
        val headroom = if (Build.VERSION.SDK_INT >= 30)
            runCatching { power.getThermalHeadroom(0) }.getOrNull()
        else null
        val valid = headroom?.takeIf { it.isFinite() && it >= 0f }
        val headroomReason = when {
            Build.VERSION.SDK_INT < 30 -> "Requires Android 11 (API 30)"
            headroom == null -> "Headroom query failed or unsupported"
            valid == null -> "Headroom unavailable (NaN, infinity or invalid)"
            else -> "Android thermal headroom; not degrees Celsius"
        }
        return AndroidThermalEvidence(status, valid, statusReason, headroomReason)
    }
}
