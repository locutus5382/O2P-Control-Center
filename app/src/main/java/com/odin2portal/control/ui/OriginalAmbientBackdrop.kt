package com.odin2portal.control.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Original hand-composed, STATIC background art for the Odin 2 Portal.
 *
 * Not sourced from an image library, not a blur shader, not a GIF and not an
 * animated wallpaper. Brushes and paths are allocated in drawWithCache rather
 * than on every draw. This is behind the MAIN Activity only, never the HUD.
 *
 * Designed for the original 720-ms diagonal Liquid Glass button sweep. No
 * strong center highlight: translucent cards and labels must remain legible.
 */
internal data class AmbientPalette(
    val glowTop: Color,
    val glowBottom: Color,
    val ribbon: Color,
    val line: Color,
    val light: Boolean,
    val tactical: Boolean
)

internal fun ambientPalette(family: ThemeFamily, accent: Color): AmbientPalette =
    when (family) {
        ThemeFamily.OBSIDIAN -> AmbientPalette(
            glowTop = lerp(accent, Color(0xFF2CC9C1), 0.54f),
            glowBottom = lerp(accent, Color(0xFF5561C4), 0.38f),
            ribbon = Color(0xFF5EAED5),
            line = Color(0xFF9ABCE2),
            light = false, tactical = false
        )
        ThemeFamily.AERO -> AmbientPalette(
            glowTop = lerp(accent, Color(0xFF96DCE8), 0.68f),
            glowBottom = lerp(accent, Color(0xFFAEAFE8), 0.70f),
            ribbon = Color(0xFF98C7DD),
            line = Color(0xFF7BB0C8),
            light = true, tactical = false
        )
        ThemeFamily.VIOLET -> AmbientPalette(
            glowTop = lerp(accent, Color(0xFFC05FD8), 0.59f),
            glowBottom = lerp(accent, Color(0xFF5969D1), 0.55f),
            ribbon = Color(0xFFDD83C5),
            line = Color(0xFFBDA2E8),
            light = false, tactical = false
        )
        ThemeFamily.DECKY -> AmbientPalette(
            glowTop = lerp(accent, Color(0xFF4C87A5), 0.69f),
            glowBottom = Color(0xFF3D5C70),
            ribbon = Color(0xFF263844),
            line = Color(0xFF88B3C6),
            light = false, tactical = true
        )
    }

@Composable
internal fun OriginalAmbientBackdrop(settings: AppearanceSettings) {
    val c = settings.colors
    val palette = ambientPalette(settings.family, c.accent)

    Box(
        Modifier.matchParentSize().drawWithCache {
            val w = size.width
            val h = size.height
            val radius = size.minDimension.coerceAtLeast(1f)

            // Light is concentrated around the OUTER edges and corners so
            // cards remain clear and the underlying gradient stays calm.
            val upper = Offset(w * 0.91f, h * 0.07f)
            val lower = Offset(w * 0.08f, h * 0.96f)
            val middle = Offset(w * 0.52f, h * 0.92f)
            val topBrush = Brush.radialGradient(
                listOf(
                    palette.glowTop.copy(alpha = if (palette.light) .30f else .31f),
                    palette.glowTop.copy(alpha = if (palette.light) .105f else .10f),
                    Color.Transparent
                ),
                center = upper, radius = radius * .84f
            )
            val bottomBrush = Brush.radialGradient(
                listOf(
                    palette.glowBottom.copy(alpha = if (palette.light) .24f else .26f),
                    palette.glowBottom.copy(alpha = .075f),
                    Color.Transparent
                ),
                center = lower, radius = radius * .72f
            )
            val centerBrush = Brush.radialGradient(
                listOf(
                    palette.ribbon.copy(alpha = if (palette.light) .055f else .045f),
                    Color.Transparent
                ),
                center = middle, radius = radius * .96f
            )
            // One understated lower-corner glass wave, entirely static.
            val wave = Path().apply {
                moveTo(0f, h * .80f)
                cubicTo(w * .20f, h * .59f, w * .42f, h * .91f, w * .63f, h * .73f)
                cubicTo(w * .84f, h * .55f, w * .96f, h * .69f, w, h * .60f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            val waveBrush = Brush.linearGradient(
                colors = listOf(
                    palette.ribbon.copy(alpha = if (palette.light) .08f else .115f),
                    palette.glowBottom.copy(alpha = if (palette.light) .03f else .085f),
                    Color.Transparent
                ),
                start = Offset(0f, h), end = Offset(w, 0f)
            )
            val grid = 54.dp.toPx()

            onDrawBehind {
                if (palette.tactical) {
                    // Tactical is deliberately matte: restrained hairline grid,
                    // soft corner lighting, and a single geometric accent.
                    drawCircle(brush = topBrush, center = upper, radius = radius * .84f)
                    drawCircle(brush = bottomBrush, center = lower, radius = radius * .72f)
                    var x = grid * .5f
                    while (x < w) {
                        drawLine(palette.line.copy(alpha = .065f),
                            Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                        x += grid
                    }
                    var y = grid * .5f
                    while (y < h) {
                        drawLine(palette.line.copy(alpha = .058f),
                            Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                        y += grid
                    }
                    drawLine(palette.line.copy(alpha = .16f),
                        Offset(w * .78f, 0f), Offset(w * .98f, h * .30f),
                        strokeWidth = 2.dp.toPx())
                    drawLine(palette.line.copy(alpha = .09f),
                        Offset(w * .82f, 0f), Offset(w, h * .28f),
                        strokeWidth = 1.dp.toPx())
                } else {
                    drawCircle(brush = topBrush, center = upper, radius = radius * .84f)
                    drawCircle(brush = bottomBrush, center = lower, radius = radius * .72f)
                    drawCircle(brush = centerBrush, center = middle, radius = radius * .96f)
                    drawPath(path = wave, brush = waveBrush)

                    // Hollow lens traces suggest depth without introducing
                    // distracting 3D spheres or expensive blur.
                    drawCircle(
                        color = palette.line.copy(alpha = if (palette.light) .10f else .12f),
                        radius = radius * .39f, center = upper,
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = palette.line.copy(alpha = if (palette.light) .07f else .075f),
                        radius = radius * .55f, center = upper,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }
    )
}
