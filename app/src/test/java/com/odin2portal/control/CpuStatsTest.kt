package com.odin2portal.control

import com.odin2portal.control.monitor.CpuStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuStatsTest {
    @Test fun parsesLinuxCpuLine() {
        val time = CpuStats.parse("cpu  20 10 30 100 15 5 0 0 0 0")
        assertEquals(115L, time?.idle)
        assertEquals(180L, time?.total)
    }

    @Test fun derivesBusyFromTwoSamples() {
        val first = CpuStats.Times(80L, 100L)
        val second = CpuStats.Times(155L, 200L)
        assertEquals(25, CpuStats.busy(first, second))
    }

    @Test fun refusesMissingOrReversedSamples() {
        assertNull(CpuStats.busy(null, CpuStats.Times(20L, 30L)))
        assertNull(CpuStats.busy(CpuStats.Times(20L, 30L), CpuStats.Times(10L, 25L)))
        assertNull(CpuStats.parse("cpu not-a-number 5 8 10"))
    }
}
