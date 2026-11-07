package com.odin2portal.control

import com.odin2portal.control.domain.CpuPolicy
import com.odin2portal.control.domain.DeviceSnapshot
import com.odin2portal.control.hardware.AccessMode
import com.odin2portal.control.hardware.HardwareRead
import com.odin2portal.control.hardware.PrivilegeStatus
import com.odin2portal.control.hardware.ReadSource
import com.odin2portal.control.studio.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression values transcribed from physical AYN Odin 2 Portal diagnostics.
 * Policy0's scaling_max_freq is unreadable; policy3/7 reported maxima are not
 * among scaling_available_frequencies. All THREE real policy tables must show.
 */
class PortalOppRegressionTest {
    private fun read(path: String, value: String?): HardwareRead =
        HardwareRead(path, value, if (value == null) ReadSource.NONE else ReadSource.DIRECT)

    private fun cpu(id: Int, cores: String, values: String, current: Long,
                    runtimeMax: Long?, hwMax: Long) : CpuPolicy {
        val prefix = "/sys/devices/system/cpu/cpufreq/policy" + id + "/"
        return CpuPolicy(
            name = "policy" + id,
            relatedCpus = read(prefix + "related_cpus", cores),
            currentKHz = read(prefix + "scaling_cur_freq", current.toString()),
            minKHz = read(prefix + "scaling_min_freq", null),
            maxKHz = read(prefix + "scaling_max_freq", runtimeMax?.toString()),
            hardwareMaxKHz = read(prefix + "cpuinfo_max_freq", hwMax.toString()),
            availableKHz = read(prefix + "scaling_available_frequencies", values)
        )
    }

    private fun portal(): DeviceSnapshot {
        val gpuPath = "/sys/class/kgsl/kgsl-3d0/"
        return DeviceSnapshot(
            model = "AYN Odin2 Portal", androidVersion = "13",
            accessMode = AccessMode.PSERVER,
            privilege = PrivilegeStatus(ReadSource.PSERVER, false, null,
                "PServerBinder alive, legacy protocol unverified"),
            capturedAtUtc = 1794080548010L,
            cpus = listOf(
                cpu(0, "0 1 2",
                    "307200 441600 556800 672000 787200 902400 1017600 1113600 " +
                    "1228800 1344000 1459200 1555200 1670400 1785600 1900800 2016000",
                    2016000L, null, 2016000L),
                cpu(3, "3 4 5 6",
                    "499200 614400 729600 844800 940800 1056000 1171200 1286400 " +
                    "1401600 1536000 1651200 1785600 1920000 2054400 2188800 " +
                    "2323200 2457600 2592000 2707200",
                    1651200L, 2803200L, 2803200L),
                cpu(7, "7",
                    "595200 729600 864000 998400 1132800 1248000 1363200 1478400 " +
                    "1593600 1708800 1843200 1977600 2092800 2227200 2342400 " +
                    "2476800 2592000 2726400 2841600 2956800",
                    1843200L, 3187200L, 3187200L)
            ),
            gpuNodes = listOf(
                read(gpuPath + "gpu_available_frequencies",
                    "680000000 615000000 550000000 475000000 401000000 " +
                    "348000000 295000000 220000000 124800000"),
                read(gpuPath + "max_gpuclk", "680000000"),
                read(gpuPath + "gpuclk", "401000000"),
                read(gpuPath + "min_pwrlevel", "4"),
                read(gpuPath + "max_pwrlevel", "0"),
                read(gpuPath + "num_pwrlevels", "9")
            ),
            thermals = emptyList(),
            memory = read("/proc/meminfo", null),
            pressure = read("/proc/pressure/memory", null),
            diagnostics = emptyList()
        )
    }

    @Test fun preservesAllFourFrequencyGroupsFromPortalDiagnostics() {
        val table = StudioDiscovery.fromSnapshot(portal(), "qti/kalama/odin2-portal")
        assertEquals(4, table.domains.size)
        assertEquals(listOf("cpu:policy0", "cpu:policy3", "cpu:policy7", "gpu:kgsl"),
            table.domains.map { it.id })
        assertEquals(listOf(16, 19, 20, 9), table.domains.map { it.supported.size })
        assertFalse(table.hasVerifiedStockBaseline)

        val efficiency = table.domains[0]
        assertEquals(2016000L, efficiency.observedCeiling)
        assertNull(efficiency.reportedRuntimeCeiling)
        assertTrue(efficiency.referenceWarning!!.contains("unreadable"))

        val performance = table.domains[1]
        assertEquals(2707200L, performance.observedCeiling)
        assertEquals(2803200L, performance.reportedRuntimeCeiling)
        assertTrue(performance.referenceWarning!!.contains("not in the exposed OPP"))

        val prime = table.domains[2]
        assertEquals(2956800L, prime.observedCeiling)
        assertEquals(3187200L, prime.reportedRuntimeCeiling)

        val gpu = table.domains[3]
        assertEquals(680000000L, gpu.observedCeiling)
        assertEquals(9, gpu.supported.size)
        assertEquals("680.0 MHz", gpu.mhz(gpu.observedCeiling))
    }

    @Test fun cannotClaimStockWhenCpuMinimumAndOneMaximumAreUnknown() {
        val a = StudioDiscovery.fromSnapshot(portal(), "firmware")
        val b = a.copy(capturedAt = a.capturedAt + 30000L)
        val report = StockEvidence.review(b, listOf(
            StockEvidence.capture(a, "Standard", "Direct")!!,
            StockEvidence.capture(b, "Standard", "Direct")!!
        ), "Standard")
        assertEquals(EvidenceStatus.INCOMPLETE, report.status)
        assertFalse(report.isStockVerified)
        assertTrue(report.summary.contains("unreadable"))
    }
}
