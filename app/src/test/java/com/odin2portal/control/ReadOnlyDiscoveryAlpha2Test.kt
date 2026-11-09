package com.odin2portal.control

import com.odin2portal.control.discovery.SysfsInventory
import com.odin2portal.control.hardware.HostNodeRead
import com.odin2portal.control.hardware.HostNodeStatus
import com.odin2portal.control.hardware.ReadOnlyHostResult
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReadOnlyDiscoveryAlpha2Test {
    @Test fun thermalFallbackCoversSensorsAboveZone96EvenWhenDirectoryListingSucceeds() {
        val root = Files.createTempDirectory("o2p-thermal-fake").toFile()
        try {
            File(root, "thermal_zone5").mkdir()
            File(root, "thermal_zone110").mkdir()
            val ids = SysfsInventory.indices(root.path, "thermal_zone", 127)
            assertEquals(128, ids.size)
            assertTrue(110 in ids)
            assertTrue(127 in ids)
            assertTrue(0 in ids)
        } finally { root.deleteRecursively() }
    }

    @Test fun namedThermalMappingDoesNotAssumeAZoneNumber() {
        val root = Files.createTempDirectory("o2p-thermal-name-fake").toFile()
        try {
            val types = mapOf(
                2 to "battery",
                48 to "gpuss-0-usr",
                73 to "cpu-1-0-usr",
                94 to "camera-therm"
            )
            val zones = SysfsInventory.monitoredThermalZones(
                read = { path ->
                    val i = path.substringAfter("thermal_zone").substringBefore("/")
                        .toIntOrNull()
                    if (path.endsWith("/type")) types[i] else null
                },
                root = root.path
            )
            assertEquals(listOf(48,73), zones.map { it.index }.sorted())
            assertEquals(setOf("gpuss-0-usr", "cpu-1-0-usr"),
                zones.map { it.type }.toSet())
            assertTrue(zones.all { it.temperaturePath.endsWith("/temp") })
        } finally { root.deleteRecursively() }
    }

    @Test fun typedHostErrorsAreNotDisplayedAsObservedValues() {
        val report = ReadOnlyHostResult(true, 1000, listOf(
            HostNodeRead("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
                HostNodeStatus.PERMISSION_DENIED, null, 13, "Linux errno 13"),
            HostNodeRead("/sys/devices/system/cpu/cpufreq/policy3/scaling_max_freq",
                HostNodeStatus.OBSERVED, "2803200", null, null)
        ), "Verified host identity; individual node failures remain failures")
        assertTrue(report.authenticated)
        assertTrue(report.fields.getValue(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")
            .contains("UNAVAILABLE"))
        assertTrue(report.export().contains("PERMISSION_DENIED"))
        assertTrue(report.export().contains("errno=13"))
        assertTrue(report.export().contains("2803200"))
    }
}
