package com.odin2portal.control

import com.odin2portal.control.domain.SensorSelection
import org.junit.Assert.*
import org.junit.Test

class SensorSelectionTest {
    @Test fun recognizesOnlyActualCpuOrGpuLabels() {
        assertTrue(SensorSelection.isCpu("cpuss-0"))
        assertTrue(SensorSelection.isCpu("cpu-1-5"))
        assertTrue(SensorSelection.isGpu("gpuss-6"))
        assertFalse(SensorSelection.isCpu("socd"))
        assertFalse(SensorSelection.isGpu("pm8550b_tz"))
        assertFalse(SensorSelection.isCpu("battery"))
    }

    @Test fun selectsMaximumAmongCorrectGroup() {
        val readings = listOf(
            "cpuss-0" to 31.3,
            "cpu-1-5" to 32.9,
            "gpuss-0" to 27.4,
            "pm8550b_lite_tz" to 59.3,
            "cpu-1-1" to null
        )
        val cpu = SensorSelection.hottest(readings, SensorSelection::isCpu)!!
        assertEquals(32.9, cpu.celsius, 0.0001)
        assertEquals("cpu-1-5", cpu.sensor)
        assertEquals(2, cpu.matchingCount)
        val gpu = SensorSelection.hottest(readings, SensorSelection::isGpu)!!
        assertEquals(27.4, gpu.celsius, 0.0001)
        assertNull(SensorSelection.hottest(readings, { it == "not-real" }))
    }
}
