package com.odin2portal.control.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Persistent visual preferences, deliberately independent of privileged device access. */
enum class VisualStyle { GLASS, MATERIAL }

data class AccentOption(val id: String, val label: String, val color: Color)

val O2P_ACCENTS = listOf(
    AccentOption("white", "White", Color(0xFFFFFFFF)),
    AccentOption("black", "Black", Color(0xFF17191F)),
    AccentOption("dark_gray", "Dark gray", Color(0xFF4B5563)),
    AccentOption("light_gray", "Light gray", Color(0xFFCDD3DC)),
    AccentOption("dark_blue", "Dark blue", Color(0xFF224E9D)),
    AccentOption("light_blue", "Light blue", Color(0xFF8BCCF8)),
    AccentOption("red", "Red", Color(0xFFCE283D)),
    AccentOption("light_red", "Light red", Color(0xFFFF9EAA)),
    AccentOption("light_yellow", "Light yellow", Color(0xFFFFF0A8)),
    AccentOption("yellow", "Yellow", Color(0xFFF3CA35)),
    AccentOption("bright_yellow", "Bright yellow", Color(0xFFFFEA00)),
    AccentOption("light_green", "Light green", Color(0xFFB1E9A8)),
    AccentOption("green", "Green", Color(0xFF39B96A)),
    AccentOption("dark_green", "Dark green", Color(0xFF176847)),
    AccentOption("light_purple", "Light purple", Color(0xFFD1B0FF)),
    AccentOption("dark_purple", "Dark purple", Color(0xFF6842A5)),
    AccentOption("light_orange", "Light orange", Color(0xFFFFBF8C)),
    AccentOption("dark_orange", "Dark orange", Color(0xFFC76320))
)

class AppearanceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("o2p_appearance", Context.MODE_PRIVATE)
    var style by mutableStateOf(
        if (preferences.getString("style", "glass") == "material") VisualStyle.MATERIAL
        else VisualStyle.GLASS
    )
        private set
    var glassAccentId by mutableStateOf(preferences.getString("glass_accent", "light_blue") ?: "light_blue")
        private set
    var materialAccentId by mutableStateOf(preferences.getString("material_accent", "dark_blue") ?: "dark_blue")
        private set
    var glassOpacity by mutableFloatStateOf(preferences.getFloat("glass_opacity", 0.74f).coerceIn(0.55f, 0.94f))
        private set
    var lightSweep by mutableStateOf(preferences.getBoolean("light_sweep", true))
        private set

    val accent: AccentOption
        get() {
            val id = if (style == VisualStyle.GLASS) glassAccentId else materialAccentId
            return O2P_ACCENTS.firstOrNull { it.id == id } ?: O2P_ACCENTS[5]
        }

    fun selectStyle(value: VisualStyle) {
        style = value
        preferences.edit().putString("style", if (value == VisualStyle.GLASS) "glass" else "material").apply()
    }

    fun selectAccent(id: String) {
        if (O2P_ACCENTS.none { it.id == id }) return
        if (style == VisualStyle.GLASS) {
            glassAccentId = id
            preferences.edit().putString("glass_accent", id).apply()
        } else {
            materialAccentId = id
            preferences.edit().putString("material_accent", id).apply()
        }
    }

    fun setOpacity(value: Float) {
        glassOpacity = value.coerceIn(0.55f, 0.94f)
        preferences.edit().putFloat("glass_opacity", glassOpacity).apply()
    }

    fun setSweep(enabled: Boolean) {
        lightSweep = enabled
        preferences.edit().putBoolean("light_sweep", enabled).apply()
    }
}

@Composable
fun rememberAppearanceSettings(): AppearanceSettings {
    val context = LocalContext.current
    return remember(context) { AppearanceSettings(context.applicationContext) }
}

@Composable
fun O2PTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val accent = settings.accent.color
    // Light colors are accents, not guarantees of accessible on-surface contrast.
    // The underlying content palette stays contrast-aware in both visual styles.
    val scheme = darkColorScheme(
        primary = if (accent.luminance() > 0.78f) Color(0xFFE5EDF7) else accent,
        onPrimary = if (accent.luminance() > 0.4f) Color(0xFF0C1423) else Color.White,
        secondary = Color(0xFFB6C6DC),
        onSecondary = Color(0xFF0C1423),
        background = Color(0xFF081321),
        onBackground = Color(0xFFF5F7FB),
        surface = Color(0xFF172639),
        onSurface = Color(0xFFF5F7FB),
        surfaceVariant = Color(0xFF273950),
        onSurfaceVariant = Color(0xFFC8D4E3),
        outline = Color(0xFF6C7B92)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun O2PBackdrop(settings: AppearanceSettings, content: @Composable BoxScope.() -> Unit) {
    val accent = settings.accent.color
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(0xFF07111E), Color(0xFF10243B), Color(0xFF080F1C))
            )
        )
    ) {
        if (settings.style == VisualStyle.GLASS) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(size.width * 0.82f, size.height * 0.10f),
                        radius = size.minDimension * 0.85f
                    ),
                    radius = size.minDimension * 0.85f,
                    center = Offset(size.width * 0.82f, size.height * 0.10f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF4E79B7).copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.85f),
                        radius = size.minDimension * 0.60f
                    ),
                    radius = size.minDimension * 0.60f,
                    center = Offset(size.width * 0.12f, size.height * 0.85f)
                )
            }
        }
        content()
    }
}

/** A simulated frosted-glass surface: translucency + gradient + highlight border.
 * This deliberately does not pretend to implement Apple's proprietary refraction.
 * All text remains on top of a sufficiently opaque backing.
 */
@Composable
fun O2PPanel(
    settings: AppearanceSettings,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    val glass = settings.style == VisualStyle.GLASS
    val accent = settings.accent.color
    val fill = if (glass) {
        Brush.linearGradient(
            listOf(
                Color(0xFF34465D).copy(alpha = settings.glassOpacity),
                Color(0xFF15253A).copy(alpha = (settings.glassOpacity + 0.13f).coerceAtMost(1f))
            )
        )
    } else {
        Brush.verticalGradient(listOf(Color(0xFF1A2839), Color(0xFF1A2839)))
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(
                if (glass) 1.dp else 0.5.dp,
                if (glass) Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.42f), accent.copy(alpha = 0.25f), Color.White.copy(alpha = 0.08f))
                ) else Brush.linearGradient(listOf(Color(0xFF38495E), Color(0xFF38495E))),
                shape
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/** Replays a diagonal light sweep on each press, with no perpetual animations or timers. */
@Composable
fun O2PAction(
    label: String,
    settings: AppearanceSettings,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val progress = remember { Animatable(-1f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(18.dp)
    val color = settings.accent.color
    val foreground = if (primary && color.luminance() > 0.54f) Color(0xFF111827) else Color.White
    val base = when {
        !enabled -> Color(0xFF3A4656)
        primary -> color
        settings.style == VisualStyle.GLASS -> Color(0xFF53677F).copy(alpha = settings.glassOpacity)
        else -> Color(0xFF26394E)
    }
    Box(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(shape)
            .background(
                if (settings.style == VisualStyle.GLASS) {
                    Brush.linearGradient(listOf(base.copy(alpha = 0.94f), base.copy(alpha = 0.62f)))
                } else Brush.linearGradient(listOf(base, base))
            )
            .border(
                1.dp,
                if (settings.style == VisualStyle.GLASS) Color.White.copy(alpha = 0.34f)
                else Color.White.copy(alpha = 0.12f),
                shape
            )
            .clickable(enabled = enabled) {
                if (settings.lightSweep && settings.style == VisualStyle.GLASS) {
                    scope.launch {
                        progress.stop()
                        progress.snapTo(-1f)
                        progress.animateTo(2f, animationSpec = tween(650, easing = FastOutSlowInEasing))
                    }
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        if (settings.style == VisualStyle.GLASS && settings.lightSweep) {
            Canvas(Modifier.matchParentSize()) {
                val x = progress.value * size.width
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.42f), Color.Transparent),
                        start = Offset(x - 100f, 0f),
                        end = Offset(x + 100f, size.height)
                    )
                )
            }
        }
    }
}
