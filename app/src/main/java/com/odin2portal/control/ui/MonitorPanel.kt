package com.odin2portal.control.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import java.util.Locale

/**
 * On-screen read-only monitor. Stops on navigation away or when Activity stops.
 * Overlay and background notification are separate future milestones, not fake toggles.
 */
@Composable
fun MonitorPanel(appearance: AppearanceSettings) {
    val model: MonitorViewModel = viewModel()
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val csv = model.exportCsv()
                if (csv == null) {
                    exportMessage = "No recorded samples to save."
                } else {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Destination could not be opened")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                    exportMessage = "CSV saved successfully."
                }
            } catch (e: Exception) {
                exportMessage = "CSV export failed: " + (e.message ?: "unknown error")
            }
        }
    }
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

    GameplayPanel(
        appearance = appearance,
        onBegin = model::stop,
        sampleMs = state.intervalMs,
        recordMs = state.recordingIntervalMs,
        manualLabel = state.sessionLabel
    )
    Spacer(Modifier.height(12.dp))
    O2PPanel(appearance) {
        Text("ON-SCREEN LIVE MONITOR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Read-only sampler while this screen is visible · gameplay recorder above is independent",
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
        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
        Text("HISTORY RECORDING INTERVAL", fontWeight = FontWeight.SemiBold)
        Text("Independent of live sampling · stores averages and available min/max values per interval",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5000L to "5s", 10000L to "10s", 30000L to "30s", 120000L to "2m")
                .forEach { (ms, name) ->
                    O2PAction(
                        name, appearance, Modifier.weight(1f),
                        primary = state.recordingIntervalMs == ms
                    ) { model.setRecordingInterval(ms) }
                }
        }
        OutlinedTextField(
            value = state.sessionLabel,
            onValueChange = model::setSessionLabel,
            label = { Text("Session / app label (optional)") },
            placeholder = { Text("e.g. Eden — manually entered") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.recording,
            supportingText = { Text("Manual label only; automatic process detection is not active.") }
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Record this session", fontWeight = FontWeight.Medium)
                Text("Optional · up to 600 summarized intervals in memory; no background collection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            Switch(
                checked = state.recording,
                enabled = state.running,
                onCheckedChange = model::setRecording
            )
        }
        Text("${state.capturedSamples} intervals stored · ${state.pendingSamples} live samples in current interval",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelMedium)
        if (state.droppedRows > 0L) {
            Text("${state.droppedRows} oldest intervals discarded after reaching the 600-row limit.",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall)
        }
        Text("You cannot record gameplay after switching to Eden yet: this alpha stops monitoring when you leave this screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
        O2PAction(
            label = "Save interval report (CSV)",
            settings = appearance,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.capturedSamples > 0
        ) {
            val fileName = "O2P_Monitor_" +
                java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv"
            saveCsv.launch(fileName)
        }
        exportMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
        val lastSample = state.sample
        if (lastSample != null) {
            Text("Last sample: " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastSample.timestamp)),
                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (state.recentIntervals.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        O2PPanel(appearance) {
            Text("RECENT HISTORY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Last three completed intervals · source labels are explicit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            state.recentIntervals.asReversed().forEach { interval ->
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(interval.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(interval.endTimeMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
                Text(
                    (interval.packageName ?: interval.attribution.description) +
                        " · " + (interval.configuredIntervalMs / 1000) + "s interval" +
                        " · " + interval.sampleCount + " samples",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                val soc = interval.values[Metric.SOC_TEMP]?.average
                val cpu = interval.values[Metric.CPU_LOAD]?.average
                val gpu = interval.values[Metric.GPU_LOAD]?.average
                val ram = interval.values[Metric.RAM]?.average
                Text(
                    "SoC avg: " + (soc?.let { String.format(Locale.US, "%.1f °C", it) } ?: "Unavailable") +
                        "  · CPU: " + (cpu?.let { String.format(Locale.US, "%.0f%%", it) } ?: "Unavailable") +
                        "  · GPU: " + (gpu?.let { String.format(Locale.US, "%.0f%%", it) } ?: "Unavailable") +
                        "  · RAM: " + (ram?.let { String.format(Locale.US, "%.2f GiB", it) } ?: "Unavailable"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
        Text("A movable gameplay overlay and validated per-game FPS are planned for later releases. Background gameplay recording and its notification are available above.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
