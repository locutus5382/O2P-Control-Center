package com.odin2portal.control.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Colorway applies to the ENTIRE application: background, surfaces, navigation,
 * borders, primary buttons and text. No fixed blue panels or black text.
 * Glass/Material share contrast-aware tokens but differ in material treatment.
 */
enum class VisualStyle { GLASS, MATERIAL }

/** Four interchangeable full-surface design families; all retain the 18 colorways. */
enum class ThemeFamily(val label: String, val shortLabel: String, val description: String) {
    OBSIDIAN("Obsidian Liquid Glass", "Obsidian Glass", "Midnight glass · OLED-first"),
    AERO("Aero Frost Light Glass", "Aero Frost", "Pearl and ice-blue frosted glass"),
    VIOLET("Violet Nebula Glass", "Violet Nebula", "Deep violet ambient glass"),
    DECKY("Decky Tactical Cockpit", "Decky Cockpit", "Matte slate · controller-first")
}
data class AccentOption(val id: String, val label: String, val color: Color)

val O2P_ACCENTS = listOf(
    AccentOption("white", "White", Color(0xFFF6F7F9)),
    AccentOption("black", "Black", Color(0xFF161B25)),
    AccentOption("dark_gray", "Dark gray", Color(0xFF4B5563)),
    AccentOption("light_gray", "Light gray", Color(0xFFCDD3DC)),
    AccentOption("dark_blue", "Dark blue", Color(0xFF274E9E)),
    AccentOption("light_blue", "Light blue", Color(0xFF86CCF4)),
    AccentOption("red", "Red", Color(0xFFCB354B)),
    AccentOption("light_red", "Light red", Color(0xFFFFA1AE)),
    AccentOption("light_yellow", "Light yellow", Color(0xFFFFEFAC)),
    AccentOption("yellow", "Yellow", Color(0xFFF4CE4E)),
    AccentOption("bright_yellow", "Bright yellow", Color(0xFFFDE31D)),
    AccentOption("light_green", "Light green", Color(0xFFB0E5AA)),
    AccentOption("green", "Green", Color(0xFF39B96A)),
    AccentOption("dark_green", "Dark green", Color(0xFF1F7855)),
    AccentOption("light_purple", "Light purple", Color(0xFFD4B5FA)),
    AccentOption("dark_purple", "Dark purple", Color(0xFF7353A9)),
    AccentOption("light_orange", "Light orange", Color(0xFFFFBC88)),
    AccentOption("dark_orange", "Dark orange", Color(0xFFBC612B))
)

data class O2PColors(
    val accent: Color,
    val ink: Color,
    val muted: Color,
    val canvas: Color,
    val canvasEnd: Color,
    val panel: Color,
    val panelAlt: Color,
    val outline: Color,
    val onAccent: Color,
    val light: Boolean
)

private val brightThemes = setOf(
    "white", "light_gray", "light_blue", "light_red", "light_yellow",
    "yellow", "bright_yellow", "light_green", "light_purple", "light_orange"
)

fun makeO2PColors(id: String, family: ThemeFamily = ThemeFamily.OBSIDIAN): O2PColors {
    val swatch = O2P_ACCENTS.firstOrNull { it.id == id } ?: O2P_ACCENTS[5]
    val accent = swatch.color
    val light = family == ThemeFamily.AERO
    val baseCanvas = when (family) {
        ThemeFamily.OBSIDIAN -> Color(0xFF080C14)
        ThemeFamily.AERO -> Color(0xFFEAF4FC)
        ThemeFamily.VIOLET -> Color(0xFF170E29)
        ThemeFamily.DECKY -> Color(0xFF111820)
    }
    val basePanel = when (family) {
        ThemeFamily.OBSIDIAN -> Color(0xFF1B2638)
        ThemeFamily.AERO -> Color(0xFFF8FBFF)
        ThemeFamily.VIOLET -> Color(0xFF2A1C40)
        ThemeFamily.DECKY -> Color(0xFF202B34)
    }
    // Quiet neutral canvas, colorful highlights. Saturating every surface
    // equally makes important actions impossible to spot.
    val tint = when (family) {
        ThemeFamily.AERO -> 0.045f
        ThemeFamily.DECKY -> 0.020f
        else -> 0.052f
    }
    val canvas = lerp(baseCanvas, accent, tint)
    val panel = lerp(basePanel, accent,
        if (light) 0.045f else if (family == ThemeFamily.DECKY) 0.035f else 0.08f)
    return O2PColors(
        accent = accent,
        ink = if (light) Color(0xFF142133) else Color(0xFFF4F6FA),
        muted = if (light) Color(0xFF485A6D) else Color(0xFFBDC9D8),
        canvas = canvas,
        canvasEnd = when (family) {
            ThemeFamily.AERO -> Color(0xFFF3F8FF)
            ThemeFamily.VIOLET -> Color(0xFF0C091C)
            ThemeFamily.DECKY -> Color(0xFF0D131A)
            ThemeFamily.OBSIDIAN -> Color(0xFF030710)
        },
        panel = panel,
        panelAlt = lerp(panel, accent,
            if (light) 0.08f else if (family == ThemeFamily.DECKY) 0.055f else 0.13f),
        outline = if (light) Color(0xFF26374D).copy(alpha = 0.37f)
                  else if (family == ThemeFamily.DECKY) Color.White.copy(alpha = 0.34f)
                  else Color.White.copy(alpha = 0.40f),
        onAccent = if (accent.luminance() >= 0.46f) Color(0xFF111827) else Color.White,
        light = light
    )
}

class AppearanceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("o2p_appearance", Context.MODE_PRIVATE)
    private val defaultFamily = if (preferences.getString("style", "glass") == "material")
        ThemeFamily.DECKY else ThemeFamily.OBSIDIAN
    var family by mutableStateOf(
        ThemeFamily.entries.firstOrNull {
            it.name == preferences.getString("theme_family", defaultFamily.name)
        } ?: defaultFamily
    )
        private set
    val style: VisualStyle get() = if (family == ThemeFamily.DECKY)
        VisualStyle.MATERIAL else VisualStyle.GLASS
    var glassAccentId by mutableStateOf(preferences.getString("glass_accent", "dark_blue") ?: "dark_blue")
        private set
    var materialAccentId by mutableStateOf(preferences.getString("material_accent", "light_gray") ?: "light_gray")
        private set
    var glassOpacity by mutableFloatStateOf(preferences.getFloat("glass_opacity", 0.81f).coerceIn(0.58f, 0.96f))
        private set
    var lightSweep by mutableStateOf(preferences.getBoolean("light_sweep", true))
        private set

    val accent: AccentOption get() = O2P_ACCENTS.firstOrNull {
        it.id == if (style == VisualStyle.GLASS) glassAccentId else materialAccentId
    } ?: O2P_ACCENTS[4]

    val colors: O2PColors get() = makeO2PColors(accent.id, family)

    fun selectFamily(value: ThemeFamily) {
        family = value
        preferences.edit().putString("theme_family", value.name).apply()
    }

    /** Compatibility with legacy two-style callers and stored preferences. */
    fun selectStyle(value: VisualStyle) {
        selectFamily(if (value == VisualStyle.MATERIAL) ThemeFamily.DECKY else ThemeFamily.OBSIDIAN)
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
        glassOpacity = value.coerceIn(0.58f, 0.96f)
        preferences.edit().putFloat("glass_opacity", glassOpacity).apply()
    }
    fun setSweep(value: Boolean) {
        lightSweep = value
        preferences.edit().putBoolean("light_sweep", value).apply()
    }
}

@Composable
fun rememberAppearanceSettings(): AppearanceSettings {
    val context = LocalContext.current
    return remember(context) { AppearanceSettings(context.applicationContext) }
}

private val O2PTypography = Typography(
    displaySmall = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun O2PTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val c = settings.colors
    val scheme = if (c.light) lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        secondary = c.muted, onSecondary = c.ink,
        background = c.canvas, onBackground = c.ink,
        surface = c.panel, onSurface = c.ink,
        surfaceVariant = c.panelAlt, onSurfaceVariant = c.muted,
        outline = c.outline
    ) else darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        secondary = c.muted, onSecondary = c.ink,
        background = c.canvas, onBackground = c.ink,
        surface = c.panel, onSurface = c.ink,
        surfaceVariant = c.panelAlt, onSurfaceVariant = c.muted,
        outline = c.outline
    )
    MaterialTheme(colorScheme = scheme, typography = O2PTypography) {
        CompositionLocalProvider(LocalContentColor provides c.ink) {
            ProvideTextStyle(O2PTypography.bodyMedium, content)
        }
    }
}

@Composable
fun O2PBackdrop(settings: AppearanceSettings, content: @Composable BoxScope.() -> Unit) {
    val c = settings.colors
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(c.canvas, c.canvasEnd, c.canvas))
        )
    ) {
        // Lightweight color-scattered glow; no realtime blur, GPU-heavy shaders,
        // or floating overlay during gameplay.
        if (settings.style == VisualStyle.GLASS) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(c.accent.copy(alpha = if (c.light) 0.15f else 0.23f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.16f),
                        radius = size.minDimension * 1.03f
                    ),
                    radius = size.minDimension * 1.03f,
                    center = Offset(size.width * 0.78f, size.height * 0.16f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(c.panelAlt.copy(alpha = if (c.light) 0.26f else 0.34f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.92f),
                        radius = size.minDimension * 0.68f
                    ),
                    radius = size.minDimension * 0.68f,
                    center = Offset(size.width * 0.18f, size.height * 0.92f)
                )
            }
        }
        CompositionLocalProvider(LocalContentColor provides c.ink, content = { content() })
    }
}

@Composable
fun O2PPanel(
    settings: AppearanceSettings,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = settings.colors
    val glass = settings.style == VisualStyle.GLASS
    val shape = RoundedCornerShape(if (glass) 20.dp else 14.dp)
    val fill = if (glass) Brush.linearGradient(
        listOf(
            c.panel.copy(alpha = settings.glassOpacity),
            c.panelAlt.copy(alpha = (settings.glassOpacity + 0.06f).coerceAtMost(1f))
        )
    ) else Brush.linearGradient(listOf(c.panel, c.panel))
    val rim = if (glass) Brush.linearGradient(
        listOf(
            if (c.light) Color.White.copy(alpha = 0.97f) else Color.White.copy(alpha = 0.59f),
            c.accent.copy(alpha = if (c.light) .35f else .42f),
            c.outline
        )
    ) else Brush.linearGradient(listOf(
        if (c.light) Color.White.copy(alpha = .55f) else Color.White.copy(alpha = .23f),
        c.outline
    ))
    Column(
        modifier = modifier
            .shadow(
                elevation = if (glass) 11.dp else 5.dp,
                shape = shape,
                clip = false,
                ambientColor = c.accent.copy(alpha = if (c.light) 0.16f else 0.22f),
                spotColor = Color.Black.copy(alpha = 0.24f)
            )
            .clip(shape)
            .background(fill)
            .border(if (glass) 1.25.dp else 1.05.dp, rim, shape)
            .padding(if (glass) 15.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides c.ink) {
            ProvideTextStyle(O2PTypography.bodyMedium) {
                content()
            }
        }
    }
}

/**
 * High-legibility action control shared across all 18 colorways.
 *
 * Glass: translucent layered fill + specular edge.
 * Decky/material: tactile, raised/pressed soft surface with a visible outline.
 *
 * Press once => a brief highlight travels clockwise along the button's ACTUAL
 * rounded-rectangle perimeter. This is NOT a spinning full-screen effect, does
 * not use background blur or per-frame telemetry, and honors Android's Compose
 * system animation duration scale. Controller focus remains clear at rest.
 */
@Composable
fun O2PAction(
    label: String,
    settings: AppearanceSettings,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val c = settings.colors
    val glass = settings.style == VisualStyle.GLASS
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val orbit = remember { Animatable(0f) }
    var orbitVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.968f else 1f,
        animationSpec = tween(durationMillis = 135),
        label = "o2pPressDepth"
    )
    val shape = RoundedCornerShape(if (glass) 14.dp else 11.dp)
    // Information hierarchy: only primary/selected actions carry a solid
    // accent. Ordinary actions keep legible, lightly textured surfaces.
    val danger = Color(0xFFC74956)
    val solid = when {
        destructive -> danger
        primary -> c.accent
        else -> c.panelAlt
    }
    val textColor = when {
        destructive -> Color.White
        primary -> c.onAccent
        else -> c.ink
    }
    val rim = when {
        !enabled -> c.outline
        focused -> if (c.light) Color(0xFF17539D) else Color(0xFFFFE4A0)
        destructive -> if (c.light) Color(0xFF76222B) else Color(0xFFFFB5BE)
        primary && solid.luminance() > 0.48f -> Color(0xFF17263B)
        primary -> Color(0xFFE3EDFF)
        c.light -> Color(0xFF40536B)
        else -> Color(0xFFBBCDE6)
    }
    val fill: Brush = if (glass) {
        Brush.linearGradient(
            listOf(
                lerp(solid, Color.White, if (c.light) 0.18f else 0.14f)
                    .copy(alpha = if (primary || destructive) .99f else .91f),
                solid.copy(alpha = if (primary || destructive) .94f else .84f),
                lerp(solid, c.canvas, 0.13f).copy(alpha = .96f)
            )
        )
    } else {
        // A modest highlight and lower shadow rather than low-contrast
        // monochrome neumorphism, which obscures button boundaries.
        Brush.verticalGradient(
            if (pressed) listOf(lerp(solid, c.canvas, .11f), solid)
            else listOf(lerp(solid, Color.White, if (c.light) .10f else .09f),
                lerp(solid, c.canvas, .065f))
        )
    }

    Box(
        modifier = modifier
            .heightIn(min = 46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.52f
            }
            .shadow(
                elevation = if (!enabled) 0.dp else if (pressed) 1.dp
                    else if (primary || destructive) 7.dp else if (glass) 4.dp else 3.dp,
                shape = shape,
                ambientColor = if (primary || destructive) solid.copy(alpha = .32f)
                    else Color.Black.copy(alpha = .18f),
                spotColor = Color.Black.copy(alpha = .26f)
            )
            .clip(shape)
            .background(fill)
            .border(
                when {
                    focused -> 2.8.dp
                    primary || destructive -> 1.9.dp
                    else -> 1.5.dp
                },
                rim,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                if (settings.lightSweep) {
                    scope.launch {
                        orbit.stop()
                        orbit.snapTo(0f)
                        orbitVisible = true
                        try {
                            orbit.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
                        } finally {
                            orbitVisible = false
                        }
                    }
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (glass) {
            // Glass without GPU-intensive real-time backdrop blur: a static
            // interior edge reflection over a semitransparent surface.
            Box(
                Modifier.matchParentSize().padding(3.dp)
                    .border(
                        .8.dp,
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = if (c.light) .78f else .35f),
                                Color.Transparent,
                                c.accent.copy(alpha = .13f))
                        ),
                        RoundedCornerShape(11.dp)
                    )
            )
        }

        if (orbitVisible && enabled) {
            Canvas(Modifier.matchParentSize()) {
                val inset = 3.dp.toPx()
                val width = size.width - 2f * inset
                val height = size.height - 2f * inset
                if (width > 0f && height > 0f) {
                    // Android's PathMeasure traces the *actual* round-rect
                    // path; a short bright arc travels around it exactly once.
                    val path = android.graphics.Path().apply {
                        addRoundRect(
                            inset, inset, size.width - inset, size.height - inset,
                            if (glass) 14.dp.toPx() else 11.dp.toPx(),
                            if (glass) 14.dp.toPx() else 11.dp.toPx(),
                            android.graphics.Path.Direction.CW
                        )
                    }
                    val measure = android.graphics.PathMeasure(path, true)
                    val length = measure.length
                    if (length > 0f) {
                        val arc = android.graphics.Path()
                        val head = orbit.value * length
                        val trail = length * 0.17f
                        val start = (head - trail + length) % length
                        val end = head % length
                        if (start <= end) measure.getSegment(start, end, arc, true)
                        else {
                            measure.getSegment(start, length, arc, true)
                            measure.getSegment(0f, end, arc, true)
                        }
                        val paint = android.graphics.Paint(
                            android.graphics.Paint.ANTI_ALIAS_FLAG
                        ).apply {
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 3.0.dp.toPx()
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            color = if (c.light) Color(0xFF163B70).toArgb()
                                else Color(0xFFFFF5DA).toArgb()
                            alpha = (255f * if (orbit.value < .78f) 1f
                                else ((1f - orbit.value) / .22f).coerceIn(0f, 1f))
                                .toInt().coerceIn(0, 255)
                        }
                        drawIntoCanvas { it.nativeCanvas.drawPath(arc, paint) }
                    }
                }
            }
        }
        Text(
            label, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = O2PTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
        )
    }
}

/** Two independent panes instead of stretching a portrait screen across landscape. */
@Composable
fun O2PColumns(
    modifier: Modifier = Modifier,
    breakpoint: Int = 700,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= breakpoint.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { left() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { right() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                left()
                right()
            }
        }
    }
}
