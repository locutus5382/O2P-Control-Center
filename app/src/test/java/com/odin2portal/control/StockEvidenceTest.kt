package com.odin2portal.control

import com.odin2portal.control.studio.*
import org.junit.Assert.*
import org.junit.Test

class StockEvidenceTest {
    private fun catalog(time: Long = 100000L, cpuLimit: Long = 1000000L,
                        includeGpu: Boolean = true): StudioCatalogue {
        val cpus = listOf(0, 3, 7).map { index ->
            FrequencyDomain("cpu:policy" + index, "CPU " + index, DomainType.CPU_KHZ,
                listOf(500000L, 750000L, cpuLimit).distinct().sorted(), cpuLimit,
                750000L, cpuLimit, "test CPU sysfs")
        }
        val gpu = if (includeGpu) listOf(
            FrequencyDomain("gpu:kgsl", "Adreno", DomainType.GPU_HZ,
                listOf(200000000L, 400000000L, 600000000L), 600000000L,
                400000000L, 600000000L, "test GPU sysfs")) else emptyList()
        return StudioCatalogue("Portal", time, "fingerprint-1", cpus + gpu)
    }

    private fun capture(c: StudioCatalogue, mode: String = "Standard") =
        StockEvidence.capture(c, mode, "Direct read-only")!!

    @Test fun doesNotCallOneSampleStock() {
        val c = catalog()
        val a = capture(c)
        val review = StockEvidence.review(c, listOf(a), "Standard")
        assertEquals(EvidenceStatus.SINGLE_CAPTURE, review.status)
        assertFalse(review.isStockVerified)
    }

    @Test fun cannotClaimStockEvenWhenTwoMatchingCapturesAreStable() {
        val c = catalog(time = 100000L)
        val a = capture(c)
        val b = capture(c.copy(capturedAt = 130000L))
        val outcome = StockEvidence.review(c.copy(capturedAt = 130000L), listOf(a, b),
            "Standard")
        assertEquals(EvidenceStatus.STABLE_OBSERVED, outcome.status)
        assertFalse(outcome.isStockVerified)
        assertTrue(outcome.summary.contains("UNVERIFIED"))
    }

    @Test fun detectsChangedEffectiveLimits() {
        val c = catalog()
        val a = capture(c)
        val newer = catalog(time = 135000L, cpuLimit = 750000L)
        val b = capture(newer)
        val result = StockEvidence.review(newer, listOf(a, b), "Standard")
        assertEquals(EvidenceStatus.CHANGED, result.status)
        assertTrue(result.details.any { it.contains("changed") })
    }

    @Test fun failsWhenDomainMissing() {
        val first = catalog()
        val second = catalog(time = 130000L, includeGpu = false)
        val review = StockEvidence.review(second, listOf(capture(first), capture(second)),
            "Standard")
        assertEquals(EvidenceStatus.CHANGED, review.status)
        assertFalse(review.isStockVerified)
    }

    @Test fun requiresGpuAndThreeCpuPolicies() {
        val first = catalog(includeGpu = false)
        val second = first.copy(capturedAt = 130000L)
        val review = StockEvidence.review(second, listOf(capture(first), capture(second)),
            "Standard")
        assertEquals(EvidenceStatus.INCOMPLETE, review.status)
    }

    @Test fun requiresTwentySecondsAndMatchingModeAndFirmware() {
        val initial = catalog()
        val tooSoon = initial.copy(capturedAt = 105000L)
        assertEquals(EvidenceStatus.TOO_SOON,
            StockEvidence.review(tooSoon, listOf(capture(initial), capture(tooSoon)),
                "Standard").status)
        assertEquals(EvidenceStatus.NO_CAPTURES,
            StockEvidence.review(initial, listOf(capture(initial)), "High Performance").status)
        assertEquals(EvidenceStatus.NO_CAPTURES,
            StockEvidence.review(initial.copy(fingerprint = "new-firmware"),
                listOf(capture(initial)), "Standard").status)
    }

    @Test fun reportNeverClaimsVerificationOrWrites() {
        val initial = catalog()
        val report = StockEvidence.report(initial, listOf(capture(initial)), "Standard")
        assertTrue(report.contains("Stock verified: NO"))
        assertTrue(report.contains("No kernel writes performed"))
    }

    @Test fun noModeOrEmptyTableCannotBeCaptured() {
        assertNull(StockEvidence.capture(catalog(), "", "Direct"))
        assertNull(StockEvidence.capture(catalog().copy(domains = emptyList()),
            "Standard", "Direct"))
    }
}
