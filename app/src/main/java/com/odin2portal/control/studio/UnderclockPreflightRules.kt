package com.odin2portal.control.studio

/**
 * Pure no-write safety gate for the future PServer underclock writer.
 * Current read-only snapshots cannot authenticate a safe Stock baseline.
 * Only a separately authenticated host can supply VerifiedHostLimits.
 */
data class VerifiedHostDomain(
    val id: String,
    val kind: DomainType,
    val stockMaximum: Long,
    val effectiveMinimum: Long,
    val supportedSteps: List<Long>,
    val stockAuthenticated: Boolean,
    val hardwareMappingAuthenticated: Boolean
)

data class VerifiedHostLimits(
    val hostAuthenticated: Boolean,
    val firmwareMatches: Boolean,
    val deviceMatches: Boolean,
    val domains: List<VerifiedHostDomain>
)

data class UnderclockPreflight(
    val approvedForFutureWriter: Boolean,
    val blockers: List<String>
)

/**
 * No sysfs/PServer access. Intended to become a host-side preflight guard
 * in a separately audited mutation milestone. Never authorizes code here.
 */
object UnderclockPreflightRules {
    fun check(host: VerifiedHostLimits?, targets: Map<String, Long>): UnderclockPreflight {
        val errors = mutableListOf<String>()
        if (host == null) return UnderclockPreflight(false, listOf("Authenticated host not available"))
        if (!host.hostAuthenticated) errors += "Privileged host identity was not authenticated"
        if (!host.firmwareMatches) errors += "Firmware fingerprint changed"
        if (!host.deviceMatches) errors += "Device identity changed"
        if (host.domains.isEmpty()) errors += "No verified CPU/GPU controls discovered"
        val duplicateIds = host.domains.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) errors += "Duplicate device domains: " + duplicateIds
        if (targets.keys != host.domains.map { it.id }.toSet())
            errors += "Profile domain set differs from host capability snapshot"

        for (domain in host.domains) {
            if (!domain.stockAuthenticated)
                errors += domain.id + ": OEM Stock maximum is not authenticated"
            if (!domain.hardwareMappingAuthenticated)
                errors += domain.id + ": hardware frequency/index mapping is not authenticated"
            if (domain.stockMaximum <= 0L || domain.effectiveMinimum <= 0L ||
                domain.effectiveMinimum > domain.stockMaximum)
                errors += domain.id + ": invalid effective minimum or Stock ceiling"
            if (domain.supportedSteps.size < 2 ||
                domain.supportedSteps.any { it <= 0L } ||
                domain.supportedSteps != domain.supportedSteps.sorted().distinct())
                errors += domain.id + ": supported frequency steps missing or invalid"

            val target = targets[domain.id] ?: continue
            if (target !in domain.supportedSteps)
                errors += domain.id + ": chosen value is not a supported frequency step"
            if (target > domain.stockMaximum)
                errors += domain.id + ": requested overclock is forbidden"
            if (target < domain.effectiveMinimum)
                errors += domain.id + ": requested maximum would be below current minimum"
        }
        return UnderclockPreflight(errors.isEmpty(), errors)
    }
}
