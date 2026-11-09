package com.odin2portal.control.discovery

import com.odin2portal.control.domain.SensorSelection
import java.io.File

/** No privileged commands. Bounded candidates are used even if directory listing is partial. */
object SysfsInventory {
    fun indices(root: String, prefix: String, fallbackMax: Int): List<Int> {
        val detected = runCatching {
            File(root).listFiles()?.mapNotNull {
                if (!it.name.startsWith(prefix)) null
                else it.name.removePrefix(prefix).toIntOrNull()
            }.orEmpty()
        }.getOrDefault(emptyList())
        return (detected.filter { it in 0..127 } + (0..fallbackMax.coerceIn(0, 127)))
            .distinct().sorted()
    }

    data class NamedThermal(val index: Int, val type: String, val temperaturePath: String)

    /**
     * Called ONCE per monitor collector. Only relevant, named CPU/GPU zones
     * are read every sampling tick; names are never hardcoded to zone indexes.
     */
    fun monitoredThermalZones(read: (String) -> String?,
                              root: String = "/sys/class/thermal"): List<NamedThermal> =
        indices(root, "thermal_zone", 127).mapNotNull { id ->
            val type = read("$root/thermal_zone$id/type")?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!SensorSelection.isCpu(type) && !SensorSelection.isGpu(type))
                return@mapNotNull null
            NamedThermal(id, type, "$root/thermal_zone$id/temp")
        }
}
