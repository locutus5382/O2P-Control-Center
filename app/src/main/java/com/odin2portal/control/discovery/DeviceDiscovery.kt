package com.odin2portal.control.discovery

import android.os.Build
import com.odin2portal.control.domain.CpuPolicy
import com.odin2portal.control.domain.DeviceSnapshot
import com.odin2portal.control.domain.ThermalZone
import com.odin2portal.control.hardware.AccessMode
import com.odin2portal.control.hardware.HardwareReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Read-only discovery; never assumes policy0/3/7 exist or thermal_zone0 is the SoC. */
class DeviceDiscovery(private val reader: HardwareReader = HardwareReader()) {
    companion object {
        private const val CPU_ROOT = "/sys/devices/system/cpu/cpufreq"
        private const val GPU_ROOT = "/sys/class/kgsl/kgsl-3d0"
        private const val THERMAL_ROOT = "/sys/class/thermal"
    }

    private fun directoryIndices(root: String, prefix: String, maxFallback: Int): List<Int> {
        val indexed = File(root).listFiles()
            ?.mapNotNull { item ->
                if (item.name.startsWith(prefix)) item.name.removePrefix(prefix).toIntOrNull()
                else null
            }
            ?.filter { it in 0..127 }
            ?.distinct()?.sorted()
            .orEmpty()
        return if (indexed.isNotEmpty()) indexed else (0..maxFallback).toList()
    }

    suspend fun capture(mode: AccessMode): DeviceSnapshot = withContext(Dispatchers.IO) {
        val privilege = reader.probe(mode)
        val cpuPolicies = mutableListOf<CpuPolicy>()
        for (id in directoryIndices(CPU_ROOT, "policy", 11)) {
            val base = "$CPU_ROOT/policy$id"
            val current = reader.read("$base/scaling_cur_freq", mode)
            val related = reader.read("$base/related_cpus", mode)
            if (!current.isValid && !related.isValid) continue
            cpuPolicies += CpuPolicy(
                name = "policy$id",
                relatedCpus = related,
                currentKHz = current,
                minKHz = reader.read("$base/scaling_min_freq", mode),
                maxKHz = reader.read("$base/scaling_max_freq", mode),
                hardwareMaxKHz = reader.read("$base/cpuinfo_max_freq", mode),
                availableKHz = reader.read("$base/scaling_available_frequencies", mode)
            )
        }
        val gpuNames = listOf(
            "gpu_busy_percentage", "gpubusy", "gpuclk", "max_gpuclk", "min_gpuclk",
            "devfreq/cur_freq", "devfreq/min_freq", "devfreq/max_freq",
            "devfreq/available_frequencies", "devfreq/governor"
        )
        val gpu = gpuNames.map { reader.read("$GPU_ROOT/$it", mode) }
        val zones = mutableListOf<ThermalZone>()
        for (id in directoryIndices(THERMAL_ROOT, "thermal_zone", 23)) {
            val type = reader.read("$THERMAL_ROOT/thermal_zone$id/type", mode)
            val temp = reader.read("$THERMAL_ROOT/thermal_zone$id/temp", mode)
            if (type.isValid || temp.isValid) zones += ThermalZone(id, type, temp)
        }
        val memory = reader.read("/proc/meminfo", mode)
        val pressure = reader.read("/proc/pressure/memory", mode)
        val notes = buildList {
            add("READ-ONLY ALPHA: no CPU, GPU, VM, fan or boot settings are modified.")
            add("A Binder existing is not proof of root; UID is reported only from a verified command.")
            add("Some sensors may be inaccessible under Android SELinux, even with a visible path.")
            add("GPU frequency formats/units depend on firmware; raw values are displayed.")
            add("Temperature zones are listed by sensor type; no zone is presumed to be the SoC.")
            add("Disable conflicting tuning apps before future tuning experiments.")
            if (cpuPolicies.isEmpty()) add("No CPU frequency policies readable with selected access.")
            if (zones.isEmpty()) add("No thermal zones readable with selected access.")
            if (mode != AccessMode.DIRECT_ONLY && !privilege.available) {
                add("Privileged probe failed: ${privilege.reason}")
            }
        }
        DeviceSnapshot(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            accessMode = mode,
            privilege = privilege,
            capturedAtUtc = System.currentTimeMillis(),
            cpus = cpuPolicies,
            gpuNodes = gpu,
            thermals = zones,
            memory = memory,
            pressure = pressure,
            diagnostics = notes
        )
    }
}
