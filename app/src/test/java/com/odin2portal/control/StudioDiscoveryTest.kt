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

class StudioDiscoveryTest {
    private fun read(path: String, value: String?) =
        HardwareRead(path, value, if (value == null) ReadSource.NONE else ReadSource.DIRECT)

    private fun snapshot(
        freqs: String = "1100000 500000 900000 700000 1100000",
        gpuMax: String? = "600000000"
    ): DeviceSnapshot {
        val prefix = "/sys/devices/system/cpu/cpufreq/policy0/"
        val gpu = "/sys/class/kgsl/kgsl-3d0/"
        return DeviceSnapshot(
            model = "AYN Odin Portal test", androidVersion = "13",
            accessMode = AccessMode.DIRECT_ONLY,
            privilege = PrivilegeStatus(ReadSource.DIRECT, true, null, "read only"),
            capturedAtUtc = 123L,
            cpus = listOf(
                CpuPolicy(
                    name = "policy0",
                    relatedCpus = read(prefix + "related_cpus", "0 1 2"),
                    currentKHz = read(prefix + "scaling_cur_freq", "700000"),
                    minKHz = read(prefix + "scaling_min_freq", "500000"),
                    maxKHz = read(prefix + "scaling_max_freq", "1100000"),
                    hardwareMaxKHz = read(prefix + "cpuinfo_max_freq", "1100000"),
                    availableKHz = read(prefix + "scaling_available_frequencies", freqs)
                )
            ),
            gpuNodes = listOf(
                read(gpu + "gpu_available_frequencies",
                    "600000000 300000000 100000000"),
                read(gpu + "max_gpuclk", gpuMax),
                read(gpu + "gpuclk", "300000000")
            ),
            thermals = emptyList(),
            memory = read("/proc/meminfo", null),
            pressure = read("/proc/pressure/memory", null),
            diagnostics = emptyList()
        )
    }

    @Test fun buildsCpuAndGpuTablesFromRealSteps() {
        val table = StudioDiscovery.fromSnapshot(snapshot(), "firmware-test")
        assertEquals(2, table.domains.size)
        assertFalse(table.hasVerifiedStockBaseline)
        val cpu = table.domains.first()
        assertEquals(DomainType.CPU_KHZ, cpu.type)
        assertEquals(listOf(500000L, 700000L, 900000L, 1100000L), cpu.supported)
        assertEquals(900000L, cpu.atOrBelow(85))
        assertEquals(1100000L, cpu.atOrBelow(100))
        assertEquals("900.0 MHz", cpu.mhz(900000L))
        val gpu = table.domains.last()
        assertEquals(DomainType.GPU_HZ, gpu.type)
        assertEquals(listOf(100000000L, 300000000L, 600000000L), gpu.supported)
        assertEquals(300000000L, gpu.atOrBelow(85))
    }

    @Test fun neverCreatesInventedFrequencySteps() {
        val c = StudioDiscovery.fromSnapshot(snapshot(freqs = "500000 1100000"), "fw")
        assertEquals(listOf(500000L, 1100000L), c.domains.first().supported)
        assertEquals(500000L, c.domains.first().atOrBelow(80))
        assertFalse(c.hasVerifiedStockBaseline)
    }

    @Test fun rejectsUnknownStepsAndMismatchedDomains() {
        val c = StudioDiscovery.fromSnapshot(snapshot(), "fw")
        val good = StudioPreset.MODERATE.values(c)
        assertNull(ProfileValidator.error(c, good))
        assertNotNull(ProfileValidator.error(c, good + ("cpu:policy0" to 812345L)))
        assertNotNull(ProfileValidator.error(c, good + ("foreign" to 700000L)))
        assertNotNull(ProfileValidator.error(c, good - "gpu:kgsl"))
    }

    @Test fun doesNotManufactureGpuOrCpuTables() {
        val withoutGpu = StudioDiscovery.fromSnapshot(snapshot(gpuMax = null), "fw")
        assertEquals(1, withoutGpu.domains.size)
        val withoutCpu = StudioDiscovery.fromSnapshot(snapshot(freqs = ""), "fw")
        assertEquals(1, withoutCpu.domains.size)
    }

    @Test fun doesNotTreatHardwareMaxAboveObservedAsAllowedStep() {
        val c = StudioDiscovery.fromSnapshot(snapshot(), "fw")
        val selected = c.domains.first()
        assertEquals(1100000L, selected.observedCeiling)
        assertFalse(selected.supported.contains(1200000L))
    }
}
