package com.odin2portal.control.domain

/**
 * Read-only marketing/reference topology for Odin 2 Portal Base, not a set of
 * verified cpufreq governor nodes, allowable OPPs, or safe write frequencies.
 */
data class CoreFamily(val cores: Int, val name: String, val advertisedMaxMHz: Int)

object Odin2PortalReference {
    const val soc = "Qualcomm Snapdragon 8 Gen 2 (SM8550)"
    const val gpu = "Adreno 740"
    const val screen = "7-inch 1920×1080 OLED · up to 120 Hz"
    const val baseRamGb = 8
    const val baseStorageGb = 128
    const val batteryMah = 8000

    val cpuFamilies = listOf(
        CoreFamily(1, "Cortex-X3", 3200),
        CoreFamily(2, "Cortex-A715", 2800),
        CoreFamily(2, "Cortex-A710", 2800),
        CoreFamily(3, "Cortex-A510", 2000)
    )
    val totalCores: Int get() = cpuFamilies.sumOf { it.cores }

    /** Hypothesized reference only. Always consult real kernel gpu OPPs. */
    const val publishedGpuMHz = 680

    val importantWarning =
        "Published peak clocks are not firmware-supported tuning steps. " +
        "Available CPU policies and GPU operating points must be read from the Portal."
}
