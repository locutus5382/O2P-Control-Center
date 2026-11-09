package com.odin2portal.control.domain

import com.odin2portal.control.discovery.AndroidThermalEvidence
import com.odin2portal.control.hardware.AccessMode
import com.odin2portal.control.hardware.HardwareRead
import com.odin2portal.control.hardware.PrivilegeStatus
import java.util.Locale

data class CpuPolicy(
    val name: String,
    val relatedCpus: HardwareRead,
    val currentKHz: HardwareRead,
    val minKHz: HardwareRead,
    val maxKHz: HardwareRead,
    val hardwareMaxKHz: HardwareRead,
    val availableKHz: HardwareRead
)

data class ThermalZone(
    val index: Int,
    val type: HardwareRead,
    val temperature: HardwareRead
) {
    fun readableTemperature(): String = Parsing.temperatureC(temperature.value)?.let {
        String.format(Locale.US, "%.1f °C", it)
    } ?: "Unavailable"
}

data class DeviceSnapshot(
    val model: String,
    val androidVersion: String,
    val accessMode: AccessMode,
    val privilege: PrivilegeStatus,
    val capturedAtUtc: Long,
    val cpus: List<CpuPolicy>,
    val gpuNodes: List<HardwareRead>,
    val thermals: List<ThermalZone>,
    val memory: HardwareRead,
    val pressure: HardwareRead,
    val diagnostics: List<String>,
    val fanNodes: List<HardwareRead> = emptyList(),
    val androidThermal: AndroidThermalEvidence? = null
) {
    fun diagnosticText(): String = buildString {
        appendLine("O2P Control Center — Read-only diagnostic capture")
        appendLine("Date UTC epoch ms: $capturedAtUtc")
        appendLine("Device: $model, Android: $androidVersion")
        appendLine("Mode: $accessMode")
        appendLine("Privilege status: ${privilege.available}, ${privilege.transport}, UID=${privilege.reportedUid}, ${privilege.reason}")
        appendLine("\nCPU policies:")
        for (cpu in cpus) {
            appendLine(cpu.name)
            listOf(cpu.relatedCpus, cpu.currentKHz, cpu.minKHz, cpu.maxKHz,
                cpu.hardwareMaxKHz, cpu.availableKHz).forEach { appendLine(it.report()) }
        }
        appendLine("\nGPU:")
        gpuNodes.forEach { appendLine(it.report()) }
        appendLine("\nThermal zones:")
        thermals.forEach { zone ->
            appendLine("zone${zone.index}: ${zone.type.value ?: "unknown"} / ${zone.readableTemperature()}")
            appendLine(zone.temperature.report())
        }
        appendLine("\nFan / hwmon (READ ONLY):")
        if (fanNodes.isEmpty()) appendLine("No readable fan tachometer/PWM interfaces found")
        fanNodes.forEach { appendLine(it.report()) }
        appendLine("\nAndroid thermal:")
        appendLine("Status: " + (androidThermal?.statusLabel ?: "UNAVAILABLE") +
            " (" + (androidThermal?.statusReason ?: "no API capture") + ")")
        appendLine("Headroom: " + (androidThermal?.headroomLabel ?: "UNAVAILABLE") +
            " (" + (androidThermal?.headroomReason ?: "no API capture") + ")")
        appendLine("\nMemory:")
        appendLine(memory.report())
        appendLine("PSI: ${pressure.value ?: pressure.error ?: "unavailable"}")
        appendLine("\nNotes:")
        diagnostics.forEach { appendLine("- $it") }
        appendLine("This diagnostic contains no kernel writes or tuning actions.")
    }
}

private fun HardwareRead.report() =
    "$path = ${value ?: "UNAVAILABLE"} (${source.name}; ${error ?: "read confirmed"})"
