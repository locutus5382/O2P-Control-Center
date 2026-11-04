package com.odin2portal.control.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Clean live dashboard: one CPU, GPU, battery and memory surface. Raw nodes stay in diagnostics. */
@Composable
fun MonitorPanel(appearance: AppearanceSettings) {
    val model: MonitorViewModel = viewModel()
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val owner = context as? LifecycleOwner
    var advanced by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                val csv = model.exportCsv()
                if (csv != null) {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Cannot open destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                    notice = "CSV saved."
                } else notice = "No captured intervals available."
            } catch (e: Exception) { notice = "Export failed: " + (e.message ?: "unknown error") }
        }
    }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.stop()
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            model.stop()
        }
    }

    O2PColumns(left = {
        O2PPanel(appearance) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LIVE TELEMETRY", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.running) "Sampling active · read only" else "Stopped · start to see readings",
                        style = MaterialTheme.typography.bodySmall,
                        color = appearance.colors.muted
                    )
                }
                Switch(checked = state.running, onCheckedChange = {
                    if (it) model.start() else model.stop()
                })
            }
            val sample = state.sample
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricTile(
                    appearance, "CPU",
                    sample?.get(Metric.SOC_TEMP)?.display ?: "—",
                    "Load " + (sample?.get(Metric.CPU_LOAD)?.display ?: "—"),
                    Modifier.weight(1f)
                )
                MetricTile(
                    appearance, "GPU",
                    sample?.get(Metric.GPU_TEMP)?.display ?: "—",
                    (sample?.get(Metric.GPU_CLOCK)?.display ?: "—") +
                        " · " + (sample?.get(Metric.GPU_LOAD)?.display ?: "—"),
                    Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricTile(
                    appearance, "BATTERY",
                    sample?.get(Metric.BATTERY_TEMP)?.display ?: "—",
                    "Charge " + (sample?.get(Metric.BATTERY_LEVEL)?.display ?: "—"),
                    Modifier.weight(1f)
                )
                MetricTile(
                    appearance, "SYSTEM RAM",
                    sample?.get(Metric.RAM)?.display ?: "—",
                    "Estimated used memory",
                    Modifier.weight(1f)
                )
            }
            Text("CPU/GPU temperatures are the highest readable values within correctly named sensor groups, not calibrated die temperatures.",
                style = MaterialTheme.typography.bodySmall,
                color = appearance.colors.muted)
            sample?.let {
                Text("Updated " + DateFormat.getTimeInstance(DateFormat.MEDIUM)
                    .format(Date(it.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = appearance.colors.muted)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        O2PPanel(appearance) {
            Text("MONITORING MODES", style = MaterialTheme.typography.titleMedium)
            StatusLine("This screen", "Live preview only", appearance)
            StatusLine("While a game is open", "Use Sessions tab", appearance)
            StatusLine("FPS", "Not available yet", appearance)
            StatusLine("Fan speed", state.sample?.get(Metric.FAN_RPM)?.display ?: "—", appearance)
            Text("FPS requires a validated frame-timing source. A missing reading never becomes a fake 0.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }, right = {
        O2PPanel(appearance) {
            Text("SAMPLING", style = MaterialTheme.typography.titleMedium)
            Text("Live refresh", color = appearance.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(1000L to "1 sec", 2000L to "2 sec", 5000L to "5 sec").forEach { (ms, text) ->
                    O2PAction(text, appearance, Modifier.weight(1f),
                        primary = state.intervalMs == ms) { model.interval(ms) }
                }
            }
            Text("Session history interval", color = appearance.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5000L to "5 s", 10000L to "10 s",
                    30000L to "30 s", 120000L to "2 min").forEach { (ms, text) ->
                    O2PAction(text, appearance, Modifier.weight(1f),
                        primary = state.recordingIntervalMs == ms) {
                        model.setRecordingInterval(ms)
                    }
                }
            }
            Text("History is aggregated per interval and persisted when you use Sessions → Start gameplay recording.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        O2PPanel(appearance) {
            Text("VISIBLE METRICS", style = MaterialTheme.typography.titleMedium)
            Text("The overview stays concise. Choose the fields included in your detailed live inspection.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            O2PAction(if (advanced) "Hide metric options" else "Customize readings",
                appearance, Modifier.fillMaxWidth()) { advanced = !advanced }
            if (advanced) {
                val groups = listOf(
                    "CPU" to listOf(Metric.SOC_TEMP, Metric.CPU_LOAD, Metric.CPU_CLOCKS),
                    "GPU" to listOf(Metric.GPU_TEMP, Metric.GPU_LOAD, Metric.GPU_CLOCK),
                    "Battery" to listOf(Metric.BATTERY_TEMP, Metric.BATTERY_LEVEL),
                    "Other" to listOf(Metric.RAM, Metric.FAN_RPM, Metric.FPS)
                )
                groups.forEach { (title, metrics) ->
                    Text(title, style = MaterialTheme.typography.labelLarge,
                        color = appearance.colors.muted)
                    metrics.forEach { metric ->
                        Row(Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(metric.label, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall)
                            val reading = state.sample?.get(metric)
                            Text(reading?.display ?: "—",
                                color = appearance.colors.muted,
                                style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = metric in state.selected,
                                onCheckedChange = { enabled -> model.enable(metric, enabled) })
                        }
                    }
                }
            }
        }
        O2PPanel(appearance) {
            Text("QUICK DIAGNOSTIC CAPTURE", style = MaterialTheme.typography.titleMedium)
            Text("Optional in-app recording, separate from saved gameplay sessions.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Record short live test", Modifier.weight(1f))
                Switch(checked = state.recording, enabled = state.running,
                    onCheckedChange = model::setRecording)
            }
            StatusLine("Completed intervals", state.capturedSamples.toString(), appearance)
            O2PAction("Export short test CSV", appearance, Modifier.fillMaxWidth(),
                enabled = state.capturedSamples > 0) {
                val file = "O2P_Live_" +
                    SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv"
                csvPicker.launch(file)
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Read-only preview stops when this screen closes. Background gameplay recording is managed separately under Sessions.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
    })
}
