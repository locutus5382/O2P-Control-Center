package com.odin2portal.control.monitor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Optional floating HUD; an ongoing recording notification is always retained. */
enum class HudDisplayMode { FLOATING_WITH_NOTIFICATION, NOTIFICATION_ONLY }
enum class HudDock { FREE, TOP, BOTTOM, LEFT, RIGHT }
enum class HudPreset { MINIMAL, STANDARD, CUSTOM }

class HudPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("o2p_game_hud", Context.MODE_PRIVATE)
    var mode by mutableStateOf(readMode())
        private set
    var dock by mutableStateOf(readDock())
        private set
    var preset by mutableStateOf(readPreset())
        private set
    var locked by mutableStateOf(prefs.getBoolean("locked", false))
        private set
    var opacity by mutableFloatStateOf(prefs.getFloat("opacity", .70f).coerceIn(.30f, .90f))
        private set
    var scale by mutableFloatStateOf(prefs.getFloat("scale", 1f).coerceIn(.8f, 1.3f))
        private set
    var customMetrics by mutableStateOf(parseMetrics(prefs.getString("metrics", null)))
        private set
    var floatingVisible by mutableStateOf(prefs.getBoolean("visible", true))
        private set

    fun refresh() {
        mode = readMode()
        dock = readDock()
        preset = readPreset()
        locked = prefs.getBoolean("locked", false)
        opacity = prefs.getFloat("opacity", .70f).coerceIn(.30f, .90f)
        scale = prefs.getFloat("scale", 1f).coerceIn(.8f, 1.3f)
        customMetrics = parseMetrics(prefs.getString("metrics", null))
        floatingVisible = prefs.getBoolean("visible", true)
    }
    private fun readMode() = enumValue(prefs.getString("mode", null), HudDisplayMode.FLOATING_WITH_NOTIFICATION)
    private fun readDock() = enumValue(prefs.getString("dock", null), HudDock.FREE)
    private fun readPreset() = enumValue(prefs.getString("preset", null), HudPreset.STANDARD)
    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    fun chooseMode(value: HudDisplayMode) { mode = value; prefs.edit().putString("mode", value.name).apply() }
    fun chooseDock(value: HudDock) { dock = value; prefs.edit().putString("dock", value.name).apply() }
    fun choosePreset(value: HudPreset) { preset = value; prefs.edit().putString("preset", value.name).apply() }
    fun changeLocked(value: Boolean) { locked = value; prefs.edit().putBoolean("locked", value).apply() }
    fun changeOpacity(value: Float) {
        opacity = value.coerceIn(.30f, .90f)
        prefs.edit().putFloat("opacity", opacity).apply()
    }
    fun changeScale(value: Float) {
        scale = value.coerceIn(.8f, 1.3f)
        prefs.edit().putFloat("scale", scale).apply()
    }
    fun toggleMetric(metric: Metric, selected: Boolean) {
        val changed = if (selected) customMetrics + metric else customMetrics - metric
        customMetrics = changed
        prefs.edit().putString("metrics", changed.joinToString(",") { it.name }).apply()
    }
    fun changeFloatingVisible(value: Boolean) {
        floatingVisible = value
        prefs.edit().putBoolean("visible", value).apply()
    }
    fun position(landscape: Boolean): Pair<Float, Float> {
        val prefix = if (landscape) "landscape" else "portrait"
        return prefs.getFloat(prefix + "_x", .75f).coerceIn(0f, 1f) to
            prefs.getFloat(prefix + "_y", .10f).coerceIn(0f, 1f)
    }
    fun savePosition(landscape: Boolean, x: Float, y: Float) {
        val prefix = if (landscape) "landscape" else "portrait"
        prefs.edit().putFloat(prefix + "_x", x.coerceIn(0f, 1f))
            .putFloat(prefix + "_y", y.coerceIn(0f, 1f)).apply()
    }
    fun selectedMetrics(): List<Metric> = when (preset) {
        HudPreset.MINIMAL -> listOf(Metric.SOC_TEMP, Metric.GPU_TEMP)
        HudPreset.STANDARD -> listOf(Metric.SOC_TEMP, Metric.CPU_LOAD, Metric.GPU_TEMP,
            Metric.GPU_LOAD, Metric.BATTERY_TEMP, Metric.RAM)
        HudPreset.CUSTOM -> Metric.entries.filter { it in customMetrics }
    }
    companion object {
        private val DEFAULT_FIELDS = setOf(Metric.SOC_TEMP, Metric.GPU_TEMP,
            Metric.BATTERY_TEMP, Metric.RAM)
        fun parseMetrics(raw: String?): Set<Metric> {
            if (raw == null) return DEFAULT_FIELDS
            return raw.split(",").mapNotNull { name ->
                Metric.entries.firstOrNull { it.name == name }
            }.toSet()
        }
    }
}

object HudGeometry {
    data class Point(val x: Int, val y: Int)
    fun position(dock: HudDock, x: Int, y: Int,
                 windowWidth: Int, windowHeight: Int,
                 hudWidth: Int, hudHeight: Int,
                 margin: Int): Point {
        val maxX = (windowWidth - hudWidth - margin).coerceAtLeast(0)
        val maxY = (windowHeight - hudHeight - margin).coerceAtLeast(0)
        val boundedX = x.coerceIn(margin.coerceAtMost(maxX), maxX)
        val boundedY = y.coerceIn(margin.coerceAtMost(maxY), maxY)
        return when (dock) {
            HudDock.FREE -> Point(boundedX, boundedY)
            HudDock.TOP -> Point(boundedX, margin.coerceAtMost(maxY))
            HudDock.BOTTOM -> Point(boundedX, maxY)
            HudDock.LEFT -> Point(margin.coerceAtMost(maxX), boundedY)
            HudDock.RIGHT -> Point(maxX, boundedY)
        }
    }
}
