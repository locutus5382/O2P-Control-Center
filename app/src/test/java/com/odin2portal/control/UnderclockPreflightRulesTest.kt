package com.odin2portal.control

import com.odin2portal.control.studio.*
import org.junit.Assert.*
import org.junit.Test

class UnderclockPreflightRulesTest {
    private fun host(): VerifiedHostLimits = VerifiedHostLimits(
        hostAuthenticated = true,
        firmwareMatches = true,
        deviceMatches = true,
        domains = listOf(
            VerifiedHostDomain("cpu:policy0", DomainType.CPU_KHZ,
                2016000L, 307200L, listOf(307200L, 1459200L, 2016000L),
                true, true),
            VerifiedHostDomain("cpu:policy3", DomainType.CPU_KHZ,
                2803200L, 499200L, listOf(499200L, 1785600L, 2323200L, 2707200L),
                true, true),
            VerifiedHostDomain("cpu:policy7", DomainType.CPU_KHZ,
                3187200L, 595200L, listOf(595200L, 1843200L, 2476800L, 2956800L),
                true, true),
            VerifiedHostDomain("gpu:kgsl", DomainType.GPU_HZ,
                680000000L, 124800000L,
                listOf(124800000L, 401000000L, 550000000L, 680000000L), true, true)
        )
    )
    private fun targets() = mapOf(
        "cpu:policy0" to 1459200L,
        "cpu:policy3" to 2323200L,
        "cpu:policy7" to 2476800L,
        "gpu:kgsl" to 550000000L
    )

    @Test fun noHostIsNeverApproved() {
        assertFalse(UnderclockPreflightRules.check(null, targets()).approvedForFutureWriter)
    }
    @Test fun authenticatedValidCeilingPreviewPassesPureValidationOnly() {
        val x = UnderclockPreflightRules.check(host(), targets())
        assertTrue(x.blockers.toString(), x.approvedForFutureWriter)
    }
    @Test fun rejectsUnknownCpuMinimumOnOdinPortal() {
        val h = host().copy(domains = host().domains.map {
            if (it.id == "cpu:policy0") it.copy(effectiveMinimum = 0) else it
        })
        assertFalse(UnderclockPreflightRules.check(h, targets()).approvedForFutureWriter)
    }
    @Test fun rejectsUnverifiedStockAndGpuIndexMapping() {
        val h = host().copy(domains = host().domains.map {
            if (it.id == "gpu:kgsl") it.copy(hardwareMappingAuthenticated = false)
            else it.copy(stockAuthenticated = false)
        })
        assertFalse(UnderclockPreflightRules.check(h, targets()).approvedForFutureWriter)
    }
    @Test fun rejectsUnsupportedOrAboveStockFrequencies() {
        assertFalse(UnderclockPreflightRules.check(host(),
            targets() + ("cpu:policy3" to 2803200L)).approvedForFutureWriter)
        assertFalse(UnderclockPreflightRules.check(host(),
            targets() + ("gpu:kgsl" to 900000000L)).approvedForFutureWriter)
    }
    @Test fun rejectsMinAboveRequestedCeilingRatherThanWritingTheMin() {
        val h = host().copy(domains = host().domains.map {
            if (it.id == "cpu:policy7") it.copy(effectiveMinimum = 2956800L) else it
        })
        val x = UnderclockPreflightRules.check(h, targets())
        assertFalse(x.approvedForFutureWriter)
        assertTrue(x.blockers.any { it.contains("below current minimum") })
    }
    @Test fun rejectsChangedFirmwareOrMissingDomain() {
        assertFalse(UnderclockPreflightRules.check(
            host().copy(firmwareMatches = false), targets()).approvedForFutureWriter)
        assertFalse(UnderclockPreflightRules.check(
            host(), targets() - "gpu:kgsl").approvedForFutureWriter)
    }
}
