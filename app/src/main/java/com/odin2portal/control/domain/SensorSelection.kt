package com.odin2portal.control.domain

import java.util.Locale

/**
 * Only named CPU/GPU thermal sensors are eligible for the two consolidated
 * dashboard values. Battery/PMIC/modem/socd/USB sensors are not CPU readings.
 * A maximum is deliberately called "peak reported sensor", NOT "die temp".
 */
object SensorSelection {
    fun isCpu(type: String): Boolean {
        val n = type.trim().lowercase(Locale.ROOT)
        return n == "cpu" || n.startsWith("cpu-") || n.startsWith("cpu_") ||
            n == "cpuss" || n.startsWith("cpuss-") || n.startsWith("cpuss_")
    }

    fun isGpu(type: String): Boolean {
        val n = type.trim().lowercase(Locale.ROOT)
        return n == "gpu" || n.startsWith("gpu-") || n.startsWith("gpu_") ||
            n == "gpuss" || n.startsWith("gpuss-") || n.startsWith("gpuss_")
    }

    data class Peak(val celsius: Double, val sensor: String, val matchingCount: Int)

    fun hottest(sensors: List<Pair<String, Double?>>, matches: (String) -> Boolean): Peak? {
        val valid = sensors.filter { matches(it.first) && it.second != null &&
            it.second!! in -10.0..125.0 }
        val top = valid.maxByOrNull { it.second!! } ?: return null
        return Peak(top.second!!, top.first, valid.size)
    }

    fun describe(peak: Peak?): String = peak?.let {
        String.format(Locale.US, "%.1f°C", it.celsius)
    } ?: "—"
}
