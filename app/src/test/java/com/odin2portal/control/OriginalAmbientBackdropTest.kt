package com.odin2portal.control

import androidx.compose.ui.graphics.Color
import com.odin2portal.control.ui.ThemeFamily
import com.odin2portal.control.ui.ambientPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure palette regressions; visual approval still requires Portal screenshots. */
class OriginalAmbientBackdropTest {
    @Test fun allFourThemesHaveDistinctOriginalPalettes() {
        val themes = ThemeFamily.entries
        assertEquals(4, themes.size)
        val palettes = themes.map { ambientPalette(it, Color(0xFF274E9E)) }
        assertEquals(themes.size, palettes.map { it.glowTop }.distinct().size)
    }

    @Test fun aeroIsLightAndDeckyIsMatte() {
        assertTrue(ambientPalette(ThemeFamily.AERO, Color.Blue).light)
        assertTrue(ambientPalette(ThemeFamily.DECKY, Color.Blue).tactical)
        assertFalse(ambientPalette(ThemeFamily.OBSIDIAN, Color.Blue).tactical)
        assertFalse(ambientPalette(ThemeFamily.VIOLET, Color.Blue).light)
    }
}
