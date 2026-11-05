package com.odin2portal.control.studio

import com.odin2portal.control.domain.DeviceSnapshot
import com.odin2portal.control.hardware.HardwareRead
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Discovery-only tuning domain. No method in this module can write sysfs,
 * execute shell commands, or invoke the firmware's privileged PServer.
 */
enum class DomainType { CPU_KHZ, GPU_HZ }

data class FrequencyDomain(
    val id: String,
    val label: String,
    val type: DomainType,
    val supported: List<Long>,
    val observedCeiling: Long,
    val currentClock: Long?,
    val maxHardwareReported: Long?,
    val source: String
) {
    init {
        require(supported == supported.sorted().distinct() && supported.all { it > 0 })
        require(observedCeiling in supported)
    }
    fun mhz(value: Long) = String.format(Locale.US, "%.1f MHz",
        if (type == DomainType.CPU_KHZ) value / 1000.0 else value / 1_000_000.0)
    fun percentOfObserved(value: Long) = ((value.toDouble() / observedCeiling) * 100.0).roundToInt()
    fun atOrBelow(percent: Int): Long {
        val target = observedCeiling.toDouble() * percent.coerceIn(1, 100) / 100.0
        return supported.lastOrNull { it <= target } ?: supported.first()
    }
}

/** The observed ceiling is NOT called "Stock" until OEM mode is verified on hardware. */
data class StudioCatalogue(
    val device: String,
    val capturedAt: Long,
    val fingerprint: String,
    val domains: List<FrequencyDomain>,
    val isReadOnly: Boolean = true
) {
    val hasVerifiedStockBaseline: Boolean = false
    fun hardwareNote() = if (domains.isEmpty())
        "No complete CPU/GPU frequency tables readable. Export diagnostics and test PServer first."
    else "Read-only: these are discovered frequencies and current ceiling candidates; OEM Stock has NOT been verified."
}

object StudioDiscovery {
    private fun candidates(raw: String?, min: Long, max: Long): List<Long> =
        raw?.trim()?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() }
            ?.filter { it in min..max }
            ?.distinct()?.sorted().orEmpty()

    private fun from(nodes: List<HardwareRead>, suffix: String): HardwareRead? =
        nodes.firstOrNull { it.path.endsWith("/" + suffix) && it.isValid }

    /**
     * Strict: omit a policy when no real supported-frequency table is exposed.
     * Never fabricate stepping by interpolating between two limits.
     */
    fun fromSnapshot(snapshot: DeviceSnapshot, firmwareFingerprint: String): StudioCatalogue {
        val found = mutableListOf<FrequencyDomain>()
        for (policy in snapshot.cpus) {
            val id = policy.name
            if (!id.matches(Regex("policy\\d+"))) continue
            val table = candidates(policy.availableKHz.value, 50_000, 5_000_000)
            val observed = policy.maxKHz.value?.trim()?.toLongOrNull() ?: continue
            val hwMax = policy.hardwareMaxKHz.value?.trim()?.toLongOrNull()
            val allowed = table.filter { it <= observed && (hwMax == null || it <= hwMax) }
            if (allowed.size < 2 || observed !in allowed) continue
            val cpuIds = policy.relatedCpus.value.orEmpty().trim()
            found += FrequencyDomain(
                id = "cpu:" + id,
                label = "CPU " + id + (if (cpuIds.isBlank()) "" else " · cores " + cpuIds),
                type = DomainType.CPU_KHZ,
                supported = allowed,
                observedCeiling = observed,
                currentClock = policy.currentKHz.value?.trim()?.toLongOrNull(),
                maxHardwareReported = hwMax,
                source = policy.availableKHz.path
            )
        }

        val nodes = snapshot.gpuNodes
        val gpuTable = candidates(
            from(nodes, "gpu_available_frequencies")?.value
                ?: from(nodes, "devfreq/available_frequencies")?.value,
            25_000_000, 2_000_000_000
        )
        // max_gpuclk is a cap in Hz, not the current operating frequency.
        val currentMax = from(nodes, "max_gpuclk")?.value?.trim()?.toLongOrNull()
            ?: from(nodes, "devfreq/max_freq")?.value?.trim()?.toLongOrNull()
        if (gpuTable.size >= 2 && currentMax != null) {
            val allowed = gpuTable.filter { it <= currentMax }
            if (allowed.size >= 2 && currentMax in allowed) {
                found += FrequencyDomain(
                    id = "gpu:kgsl",
                    label = "GPU · Adreno",
                    type = DomainType.GPU_HZ,
                    supported = allowed,
                    observedCeiling = currentMax,
                    currentClock = from(nodes, "gpuclk")?.value?.trim()?.toLongOrNull()
                        ?: from(nodes, "devfreq/cur_freq")?.value?.trim()?.toLongOrNull(),
                    maxHardwareReported = gpuTable.maxOrNull(),
                    source = from(nodes, "gpu_available_frequencies")?.path
                        ?: from(nodes, "devfreq/available_frequencies")?.path.orEmpty()
                )
            }
        }

        return StudioCatalogue(
            device = snapshot.model,
            capturedAt = snapshot.capturedAtUtc,
            fingerprint = firmwareFingerprint,
            domains = found,
        )
    }
}

/** Preset names describe degree of capping, never a promised FPS or wattage. */
enum class StudioPreset(val description: String, val previewPercent: Int) {
    STOCK("OEM limits, after hardware verification", 100),
    LIGHT("Small reduction · preview only", 95),
    MODERATE("Moderate reduction · preview only", 85),
    STRONG("Larger reduction · preview only", 75);

    fun values(catalogue: StudioCatalogue): Map<String, Long> =
        catalogue.domains.associate { it.id to it.atOrBelow(previewPercent) }
}

/** Pure validation, shared by editor, future JSON import and potential PServer backend. */
object ProfileValidator {
    fun error(catalogue: StudioCatalogue, selections: Map<String, Long>): String? {
        if (catalogue.domains.isEmpty()) return "No supported frequency tables discovered"
        val ids = catalogue.domains.map { it.id }.toSet()
        if (!ids.containsAll(selections.keys)) return "Profile contains an unknown domain"
        if (selections.keys != ids) return "Profile must specify every discovered domain"
        for (domain in catalogue.domains) {
            val mhz = selections[domain.id] ?: return "Missing " + domain.label
            if (mhz !in domain.supported) return "Unsupported frequency for " + domain.label
            if (mhz > domain.observedCeiling) return "Requested ceiling exceeds observed limit"
        }
        return null
    }
}
