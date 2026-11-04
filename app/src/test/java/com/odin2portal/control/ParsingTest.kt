package com.odin2portal.control

import com.odin2portal.control.domain.Parsing
import org.junit.Assert.*
import org.junit.Test

class ParsingTest {
    @Test fun cpusRangeAndCommaParsing() {
        assertEquals(listOf(0,1,2,3,5,7), Parsing.cpuList("0-3,5 7"))
    }
    @Test fun rejectAbsurdCpuRange() {
        assertEquals(emptyList<Int>(), Parsing.cpuList("0-999"))
    }
    @Test fun parseFrequencies() {
        assertEquals(listOf(200L,500L), Parsing.frequencyList("500 200 500 bad"))
    }
    @Test fun parseMemAvailable() {
        assertEquals(48231L, Parsing.memoryKb("MemTotal: 8012048 kB\nMemAvailable: 48231 kB", "MemAvailable"))
    }
    @Test fun millidegrees() {
        assertEquals(62.4, Parsing.temperatureC("62400")!!, 0.001)
    }
    @Test fun implausibleTempRejected() {
        assertNull(Parsing.temperatureC("230000"))
    }
}
