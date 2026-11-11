package com.odin2portal.control

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.odin2portal.control.ui.O2P_ACCENTS
import com.odin2portal.control.ui.ThemeFamily
import com.odin2portal.control.ui.classicGlassReadableText
import com.odin2portal.control.ui.makeO2PColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The owner prefers the original translucent glass button and its diagonal
 * light sweep. Test selection of readable text without changing that design.
 */
class ClassicGlassContrastTest {
    private fun minimumContrast(fg: Color, stops: List<Color>, panel: Color, canvas: Color): Float {
        return stops.flatMap { stop ->
            listOf(stop.compositeOver(panel), stop.compositeOver(canvas))
        }.minOf { bg ->
            val a = fg.luminance()
            val b = bg.luminance()
            (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
        }
    }

    @Test fun primaryActionChoosesHighestWorstCaseContrastForEveryColorway() {
        for (family in ThemeFamily.entries) {
            val glass = family != ThemeFamily.DECKY
            for (accent in O2P_ACCENTS) {
                val colors = makeO2PColors(accent.id, family)
                val stops = if (glass) listOf(
                    lerp(colors.accent, Color.White, if (colors.light) .08f else .15f),
                    colors.accent.copy(alpha = .89f)
                ) else listOf(colors.accent, colors.accent)
                val foreground = classicGlassReadableText(stops, colors.panel, colors.canvas)
                val actual = minimumContrast(foreground, stops, colors.panel, colors.canvas)
                val best = maxOf(
                    minimumContrast(Color.White, stops, colors.panel, colors.canvas),
                    minimumContrast(Color.Black, stops, colors.panel, colors.canvas)
                )
                assertEquals("${family.name}/${accent.id}", best, actual, .001f)
            }
        }
    }
}
