package com.odin2portal.control.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.odin2portal.control.monitor.*

/** The foreground monitoring notification exists in all modes, including floating. */
@Composable
fun HudControls(appearance: AppearanceSettings) {
    val context = LocalContext.current
    val settings = remember(context) { HudPreferences(context.applicationContext) }
    val session by GameplayRuntime.state.collectAsState()
    val lifecycle = context as? LifecycleOwner
    var overlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    fun applyChanges() {
        if (session.running) {
            context.startService(Intent(context, GameplayMonitorService::class.java)
                .setAction(GameplayMonitorService.ACTION_REFRESH_HUD))
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayPermission = Settings.canDrawOverlays(context)
                settings.refresh()
            }
        }
        lifecycle?.lifecycle?.addObserver(observer)
        onDispose { lifecycle?.lifecycle?.removeObserver(observer) }
    }

    O2PPanel(appearance) {
        Text("IN-GAME PERFORMANCE HUD", style = MaterialTheme.typography.titleMedium)
        Text("Visible above Eden and other apps. Drag the unlocked panel; lock it to let game touches pass through.",
            color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            O2PAction("Floating + notification", appearance, Modifier.weight(1f),
                primary = settings.mode == HudDisplayMode.FLOATING_WITH_NOTIFICATION) {
                settings.chooseMode(HudDisplayMode.FLOATING_WITH_NOTIFICATION)
                applyChanges()
            }
            O2PAction("Notification only", appearance, Modifier.weight(1f),
                primary = settings.mode == HudDisplayMode.NOTIFICATION_ONLY) {
                settings.chooseMode(HudDisplayMode.NOTIFICATION_ONLY)
                applyChanges()
            }
        }
        if (settings.mode == HudDisplayMode.FLOATING_WITH_NOTIFICATION) {
            if (!overlayPermission) {
                Text("Android permission required: Display over other apps. The recording itself can work without it.",
                    color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
                O2PAction("Allow floating overlay", appearance, Modifier.fillMaxWidth(), primary = true) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName))
                    runCatching { context.startActivity(intent) }
                }
            }
            Text("HUD layout", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HudPreset.entries.forEach { preset ->
                    O2PAction(preset.name.lowercase().replaceFirstChar { it.uppercase() },
                        appearance, Modifier.weight(1f), primary = settings.preset == preset) {
                        settings.choosePreset(preset)
                        applyChanges()
                    }
                }
            }
            if (settings.preset == HudPreset.CUSTOM) {
                Metric.entries.chunked(2).forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        line.forEach { metric ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = metric in settings.customMetrics,
                                    onCheckedChange = { chosen ->
                                        settings.toggleMetric(metric, chosen)
                                        applyChanges()
                                    })
                                Text(metric.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (line.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Text("Dock position", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                HudDock.entries.forEach { dock ->
                    O2PAction(dock.name.lowercase().replaceFirstChar { it.uppercase() },
                        appearance, Modifier.weight(1f), primary = settings.dock == dock) {
                        settings.chooseDock(dock)
                        applyChanges()
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lock HUD position", style = MaterialTheme.typography.labelLarge)
                    Text("Touch-through while locked · unlock in O2P",
                        color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.locked, onCheckedChange = {
                    settings.changeLocked(it)
                    applyChanges()
                })
            }
            Text("Transparency  ·  " + (settings.opacity * 100).toInt() + "%",
                style = MaterialTheme.typography.labelLarge)
            Slider(value = settings.opacity, onValueChange = {
                settings.changeOpacity(it)
            }, valueRange = .30f..0.90f, onValueChangeFinished = { applyChanges() })
            Text("HUD size  ·  " + (settings.scale * 100).toInt() + "%",
                style = MaterialTheme.typography.labelLarge)
            Slider(value = settings.scale, onValueChange = {
                settings.changeScale(it)
            }, valueRange = .8f..1.3f, onValueChangeFinished = { applyChanges() })
            O2PAction("Recenter HUD · reset to free positioning", appearance,
                Modifier.fillMaxWidth()) {
                settings.recenterBothOrientations()
                applyChanges()
            }
            if (session.running) {
                Text("HUD window state: " + (session.hudDiagnostic ?: "Waiting for a sample"),
                    style = MaterialTheme.typography.bodySmall,
                    color = appearance.colors.muted)
                Text(
                    "An attached window is not proof the game displays it. " +
                        "Android or the game can hide third-party overlays.",
                    style = MaterialTheme.typography.labelSmall,
                    color = appearance.colors.muted
                )
            }
            if (session.running) {
                O2PAction(if (settings.floatingVisible) "Hide floating HUD" else "Show floating HUD",
                    appearance, Modifier.fillMaxWidth()) {
                    settings.changeFloatingVisible(!settings.floatingVisible)
                    applyChanges()
                }
            }
        }
        Text("HUD data is provided by the existing monitor service; no second telemetry loop. FPS stays unavailable until validated.",
            color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
    }
}
