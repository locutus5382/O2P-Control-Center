package com.odin2portal.control

import com.odin2portal.control.ui.normalizeO2PSection
import com.odin2portal.control.ui.stepO2PSection
import com.odin2portal.control.ui.ThemeFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class PremiumNavigationTest {
    @Test fun legacyTilesAndDestinationsStillResolve() {
        assertEquals("Underclock", normalizeO2PSection("Overview"))
        assertEquals("Underclock", normalizeO2PSection("Studio"))
        assertEquals("Telemetry", normalizeO2PSection("Monitor"))
        assertEquals("Telemetry", normalizeO2PSection("Sessions"))
        assertEquals("Advanced", normalizeO2PSection("Appearance"))
        assertEquals("Diagnostics", normalizeO2PSection("Diagnostics"))
        assertEquals("Underclock", normalizeO2PSection(null))
        assertEquals("Underclock", normalizeO2PSection("unknown"))
    }

    @Test fun controllerBumperCycleWraps() {
        assertEquals("Telemetry", stepO2PSection("Underclock", 1))
        assertEquals("Diagnostics", stepO2PSection("Underclock", -1))
        assertEquals("Underclock", stepO2PSection("Diagnostics", 1))
        assertEquals("Advanced", stepO2PSection("Telemetry", 1))
    }

    @Test fun fourDistinctThemeFamilies() {
        assertEquals(4, ThemeFamily.entries.size)
        assertEquals("Decky Cockpit", ThemeFamily.DECKY.shortLabel)
        assertEquals("Obsidian Glass", ThemeFamily.OBSIDIAN.shortLabel)
    }
}
