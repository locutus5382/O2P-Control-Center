package com.odin2portal.control.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odin2portal.control.monitor.Metric
import com.odin2portal.control.monitor.MonitorViewModel
import java.text.DateFormat
import java.util.Date

/**
 * On-screen read-only monitor. Stops on navigation away or when Activity stops.
 * Overlay and background notification are separate future milestones, not fake toggles.
 */
@Composable
fun MonitorPanel(appearance: AppearanceSettings) {
    val model: MonitorViewModel = viewModel()
    val state by model.state.collectAsState()
    val lifecycleOwner = LocalContext.current as? LifecycleOwner
    DisposableEffect(model, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.stop()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            model.stop()
        }
    }

    O2PPanel(appearance) {
        Text("PERFORMANCE MONITOR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Read-only alpha · No hardware tuning, overlay, or background service",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (state.running) "Live readings" else "Monitoring is off", fontWeight = FontWeight.SemiBold)
                Text("Only samples while this screen is open and active",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Switch(checked = state.running, onCheckedChange = { if (it) model.start() else model.stop() })
        }
        Text("Sampling interval", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1000L to "1 s", 2000L to "2 s", 5000L to "5 s").forEach { (ms, label) ->
                O2PAction(label, appearance, Modifier.weight(1f), primary = ms == state.intervalMs) {
                    model.interval(ms)
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val lastSample = state.sample
        if (lastSample != null) {
            Text("Last sample: " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastSample.timestamp)),
                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(12.dp))
    O2PPanel(appearance) {
        Text("VISIBLE METRICS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Choose which readings appear below. Unavailable is not zero.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Metric.entries.forEach { metric ->
            HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(metric.label, fontWeight = FontWeight.Medium)
                    Text(metric.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Switch(
                    checked = metric in state.selected,
                    onCheckedChange = { enabled -> model.enable(metric, enabled) }
                )
            }
            if (metric in state.selected) {
                val reading = state.sample?.get(metric)
                Text(
                    if (reading == null) "Not sampled yet" else reading.display,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                reading?.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    O2PPanel(appearance) {
        Text("IN-GAME HUD", fontWeight = FontWeight.SemiBold)
        Text("Movable overlay, persistent notification and validated per-game FPS are planned for later releases. They are not active in this build.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
