package com.odin2portal.control

import androidx.compose.ui.graphics.Color
import com.odin2portal.control.ui.O2P_ACCENTS
import com.odin2portal.control.ui.ThemeFamily
import com.odin2portal.control.ui.actionFillStops
import com.odin2portal.control.ui.contrastRatio
import com.odin2portal.control.ui.effectiveActionBackgrounds
import com.odin2portal.control.ui.makeO2PColors
import com.odin2portal.control.ui.readableActionText
import org.junit.Assert.assertTrue
import org.junit.Test

/** All existing palette choices: readable even over the light/dark gradient stops. */
class ActionContrastTest {
    @Test fun primaryAccentTextMaintainsAtLeastWcagAAContrast() {
        for (family in ThemeFamily.entries) {
            for (accent in O2P_ACCENTS) {
                val colors = makeO2PColors(accent.id, family)
                val glass = family != ThemeFamily.DECKY
                val stops = actionFillStops(colors.accent, colors, glass, true, false)
                val rendered = effectiveActionBackgrounds(stops, colors)
                val foreground = readableActionText(rendered)
                val minimum = rendered.minOf { contrastRatio(foreground, it) }
                assertTrue(
                    "${family.name} / ${accent.id} primary button: minimum ${minimum}:1",
                    minimum >= 4.5f
                )
            }
        }
    }

    @Test fun destructiveButtonRetainsAaContrastInAllThemes() {
        val danger = Color(0xFFB52B40)
        for (family in ThemeFamily.entries) {
            val colors = makeO2PColors("red", family)
            val glass = family != ThemeFamily.DECKY
            val stops = actionFillStops(danger, colors, glass, true, false)
            val rendered = effectiveActionBackgrounds(stops, colors)
            val foreground = readableActionText(rendered)
            val minimum = rendered.minOf { contrastRatio(foreground, it) }
            assertTrue("${family.name} Stop action: ${minimum}:1", minimum >= 4.5f)
        }
    }

    @Test fun readableTextPrefersDarkOnPastelsAndLightOnDarkBlue() {
        val redPastel = Color(0xFFFFA1AE)
        val darkBlue = Color(0xFF274E9E)
        assertTrue(contrastRatio(readableActionText(listOf(redPastel)), redPastel) >= 4.5f)
        assertTrue(contrastRatio(readableActionText(listOf(darkBlue)), darkBlue) >= 4.5f)
    }
}
