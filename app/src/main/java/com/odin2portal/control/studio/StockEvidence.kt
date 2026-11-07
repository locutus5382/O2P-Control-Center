package com.odin2portal.control.studio

import java.util.Locale

/** Observational evidence, never authority for a kernel write or an OEM Stock assertion. */
data class StockObservation(
    val capturedAt: Long,
    val firmwareFingerprint: String,
    val device: String,
    val oemModeLabel: String,
    val domains: List<StockDomainObservation>,
    val accessNote: String
)

data class StockDomainObservation(
    val id: String,
    val type: DomainType,
    val observedCeiling: Long,
    val supported: List<Long>,
    val currentClock: Long?,
    val maxHardwareReported: Long?,
    val reportedRuntimeCeiling: Long? = null,
    val reportedRuntimeMinimum: Long? = null
)

enum class EvidenceStatus { NO_CAPTURES, SINGLE_CAPTURE, TOO_SOON, INCOMPLETE, CHANGED, STABLE_OBSERVED }

data class StockEvidenceReview(
    val status: EvidenceStatus,
    val summary: String,
    val details: List<String>,
) {
    /** Remains false until independently verified OEM Stock calibration exists. */
    val isStockVerified: Boolean get() = false
}

object StockEvidence {
    const val MIN_SEPARATION_MS = 20_000L

    fun capture(catalogue: StudioCatalogue, mode: String, accessNote: String): StockObservation? {
        val label = mode.trim().take(48)
        if (label.isEmpty() || catalogue.domains.isEmpty()) return null
        return StockObservation(
            capturedAt = catalogue.capturedAt,
            firmwareFingerprint = catalogue.fingerprint,
            device = catalogue.device,
            oemModeLabel = label,
            accessNote = accessNote.take(160),
            domains = catalogue.domains.map {
                StockDomainObservation(it.id, it.type, it.observedCeiling, it.supported,
                    it.currentClock, it.maxHardwareReported,
                    it.reportedRuntimeCeiling, it.reportedRuntimeMinimum)
            }
        )
    }

    fun review(catalogue: StudioCatalogue, observations: List<StockObservation>,
               mode: String): StockEvidenceReview {
        val label = mode.trim()
        val matched = observations.filter {
            it.device == catalogue.device &&
                it.firmwareFingerprint == catalogue.fingerprint &&
                it.oemModeLabel == label
        }.sortedBy { it.capturedAt }
        if (matched.isEmpty())
            return StockEvidenceReview(EvidenceStatus.NO_CAPTURES,
                "No observations for this firmware and user-labelled AYN mode.", emptyList())
        if (matched.size < 2)
            return StockEvidenceReview(EvidenceStatus.SINGLE_CAPTURE,
                "One observation saved. Refresh sensors and capture again after 20 seconds.",
                listOf("First capture: " + matched.first().capturedAt))
        val second = matched.last()
        val first = matched.dropLast(1).last()
        if (second.capturedAt - first.capturedAt < MIN_SEPARATION_MS)
            return StockEvidenceReview(EvidenceStatus.TOO_SOON,
                "Readings are too close; capture at least 20 seconds apart.",
                listOf("Separation: " + (second.capturedAt - first.capturedAt) / 1000 + " s"))

        val before = first.domains.associateBy { it.id }
        val after = second.domains.associateBy { it.id }
        val observed = catalogue.domains.associateBy { it.id }
        val changes = mutableListOf<String>()
        val ids = before.keys + after.keys + observed.keys
        for (id in ids.sorted()) {
            val a = before[id]
            val b = after[id]
            val c = observed[id]
            if (a == null || b == null || c == null) {
                changes += id + ": domain missing in at least one capture"
            } else {
                if (a.type != b.type || a.type != c.type || a.supported != b.supported ||
                    b.supported != c.supported)
                    changes += id + ": supported frequency table changed"
                if (a.observedCeiling != b.observedCeiling ||
                    b.observedCeiling != c.observedCeiling)
                    changes += id + ": observed ceiling changed: " +
                        a.observedCeiling + " -> " + b.observedCeiling + " -> " + c.observedCeiling
                if (a.maxHardwareReported != b.maxHardwareReported ||
                    b.maxHardwareReported != c.maxHardwareReported)
                    changes += id + ": reported hardware maximum changed"
                if (a.reportedRuntimeCeiling != b.reportedRuntimeCeiling ||
                    b.reportedRuntimeCeiling != c.reportedRuntimeCeiling)
                    changes += id + ": effective runtime maximum changed"
                if (a.reportedRuntimeMinimum != b.reportedRuntimeMinimum ||
                    b.reportedRuntimeMinimum != c.reportedRuntimeMinimum)
                    changes += id + ": effective runtime minimum changed"
            }
        }
        if (changes.isNotEmpty())
            return StockEvidenceReview(EvidenceStatus.CHANGED,
                "Different observed limits detected. Do not treat these captures as Stock.", changes)

        val cpuCount = after.values.count { it.type == DomainType.CPU_KHZ }
        val gpuCount = after.values.count { it.type == DomainType.GPU_HZ }
        val missingLimits = after.values.filter {
            it.reportedRuntimeCeiling == null
        }.map { it.id }
        if (cpuCount != 3 || gpuCount != 1 || missingLimits.isNotEmpty())
            return StockEvidenceReview(EvidenceStatus.INCOMPLETE,
                "Stable listed OPPs, but only " + cpuCount + "/3 CPU policies and " +
                    gpuCount + "/1 GPU domains are covered, and " +
                    missingLimits.size + " runtime maximum(s) are unreadable.",
                listOf(
                    "Missing runtime maxima: " +
                        (if (missingLimits.isEmpty()) "none" else missingLimits.joinToString(", ")),
                    "No OEM Stock baseline verified. No writes allowed."
                ))

        return StockEvidenceReview(EvidenceStatus.STABLE_OBSERVED,
            "Matching reported maxima and listed OPPs observed twice. OEM Stock is still UNVERIFIED.",
            after.values.sortedBy { it.id }.map {
                val divisor = if (it.type == DomainType.CPU_KHZ) 1000.0 else 1_000_000.0
                it.id + ": " + String.format(Locale.US, "%.1f MHz", it.observedCeiling / divisor)
            } + "User-entered OEM mode label is not device-authenticated.")
    }

    fun report(catalogue: StudioCatalogue, observations: List<StockObservation>,
               mode: String): String {
        val state = review(catalogue, observations, mode)
        return buildString {
            appendLine("O2P READ-ONLY OEM STOCK CANDIDATE REVIEW")
            appendLine("No kernel writes performed. Safe writable frequencies NOT verified.")
            appendLine("Device: " + catalogue.device)
            appendLine("Firmware: " + catalogue.fingerprint)
            appendLine("User-entered OEM mode: " + mode)
            appendLine("Review: " + state.status + " - " + state.summary)
            appendLine("Stock verified: NO")
            state.details.forEach { appendLine("- " + it) }
            observations.filter {
                it.firmwareFingerprint == catalogue.fingerprint && it.device == catalogue.device
            }.sortedBy { it.capturedAt }.forEach { row ->
                appendLine()
                appendLine("Capture at epoch ms: " + row.capturedAt)
                appendLine("User label: " + row.oemModeLabel)
                appendLine("Access: " + row.accessNote)
                row.domains.forEach { domain ->
                    appendLine(domain.id + " | type=" + domain.type +
                        " | ceiling=" + domain.observedCeiling +
                        " | current=" + (domain.currentClock?.toString() ?: "unavailable") +
                        " | hardwareMax=" +
                        (domain.maxHardwareReported?.toString() ?: "unavailable") +
                        " | runtimeMax=" +
                        (domain.reportedRuntimeCeiling?.toString() ?: "unavailable") +
                        " | runtimeMin=" +
                        (domain.reportedRuntimeMinimum?.toString() ?: "unavailable") +
                        " | highestListedOPP=" + domain.observedCeiling +
                        " | OPPs=" + domain.supported.joinToString(","))
                }
            }
        }
    }
}
