package com.odin2portal.control.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odin2portal.control.domain.DeviceSnapshot
import com.odin2portal.control.domain.Parsing
import com.odin2portal.control.domain.Odin2PortalReference
import com.odin2portal.control.domain.SensorSelection
import com.odin2portal.control.hardware.AccessMode
import com.odin2portal.control.monitor.GameplayRuntime
import com.odin2portal.control.monitor.MonitorViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance = rememberAppearanceSettings()
            val model: DashboardViewModel = viewModel()
            val state by model.state.collectAsState()
            O2PTheme(appearance) {
                O2PBackdrop(appearance) {
                    ControlCenter(state, appearance, model::refresh) { snapshot ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "O2P hardware diagnostics")
                            putExtra(Intent.EXTRA_TEXT, snapshot.diagnosticText())
                        }
                        startActivity(Intent.createChooser(intent, "Share diagnostic"))
                    }
                }
            }
        }
    }
}

private val sections = listOf("Overview", "Monitor", "Sessions", "Studio", "Appearance", "Diagnostics")

@Composable
private fun ControlCenter(
    state: DashboardState,
    appearance: AppearanceSettings,
    refresh: (AccessMode) -> Unit,
    export: (DeviceSnapshot) -> Unit
) {
    var section by rememberSaveable { mutableStateOf("Overview") }
    val monitor: MonitorViewModel = viewModel()
    val live by monitor.state.collectAsState()
    val gameplay by GameplayRuntime.state.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth >= 700.dp
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                O2PPanel(appearance, Modifier.width(178.dp).fillMaxHeight()) {
                    Text("O2P", style = MaterialTheme.typography.displaySmall, color = appearance.colors.ink)
                    Text("CONTROL CENTER", style = MaterialTheme.typography.labelSmall,
                        color = appearance.colors.muted)
                    Spacer(Modifier.height(10.dp))
                    sections.forEach { name ->
                        O2PAction(name, appearance, Modifier.fillMaxWidth(),
                            primary = section == name) { section = name }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (gameplay.running) "●  SESSION ACTIVE" else "●  READ ONLY",
                        style = MaterialTheme.typography.labelSmall, color = appearance.colors.muted)
                    Text("Odin 2 Portal  ·  v0.6", style = MaterialTheme.typography.labelSmall,
                        color = appearance.colors.muted)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Header(section, appearance, gameplay.running)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ScreenContent(section, state, appearance, live, monitor, refresh, export)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Header(section, appearance, gameplay.running)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sections.forEach { name ->
                        O2PAction(name, appearance, primary = section == name) { section = name }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ScreenContent(section, state, appearance, live, monitor, refresh, export)
                }
            }
        }
    }
}

@Composable
private fun Header(section: String, appearance: AppearanceSettings, recording: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(section, style = MaterialTheme.typography.headlineMedium,
                color = appearance.colors.ink)
            Text("AYN ODIN 2 PORTAL  /  CONTROL CENTER",
                style = MaterialTheme.typography.labelSmall,
                color = appearance.colors.muted)
        }
        Box(Modifier.clip(RoundedCornerShape(50))
            .background(appearance.colors.panelAlt.copy(alpha = 0.68f))
            .border(1.dp, appearance.colors.outline, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(if (recording) "●  Recording" else "●  Read-only alpha",
                color = appearance.colors.ink, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ScreenContent(
    section: String,
    state: DashboardState,
    appearance: AppearanceSettings,
    live: com.odin2portal.control.monitor.MonitorState,
    monitor: MonitorViewModel,
    refresh: (AccessMode) -> Unit,
    export: (DeviceSnapshot) -> Unit
) {
    when (section) {
        "Overview" -> OverviewPage(state, appearance, refresh, export)
        "Monitor" -> MonitorPanel(appearance)
        "Sessions" -> {
            com.odin2portal.control.ui.GameplayPanel(
                appearance = appearance,
                onBegin = monitor::stop,
                sampleMs = live.intervalMs,
                recordMs = live.recordingIntervalMs,
                manualLabel = live.sessionLabel
            )
        }
        "Studio" -> PerformanceStudioPage(appearance, state, refresh)
        "Appearance" -> AppearancePage(appearance)
        "Diagnostics" -> DiagnosticsPage(state, appearance, refresh, export)
    }
}

@Composable
private fun OverviewPage(
    state: DashboardState,
    appearance: AppearanceSettings,
    refresh: (AccessMode) -> Unit,
    export: (DeviceSnapshot) -> Unit
) {
    val snapshot = state.result
    O2PColumns(left = {
        O2PPanel(appearance) {
            Text("YOUR DEVICE", style = MaterialTheme.typography.labelSmall,
                color = appearance.colors.muted)
            Text(snapshot?.model ?: "AYN Odin 2 Portal",
                style = MaterialTheme.typography.headlineMedium)
            Text("Designed for effortless monitoring and safe, deliberate control.",
                color = appearance.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                O2PAction("Refresh sensors", appearance, Modifier.weight(1f),
                    primary = true, enabled = !state.busy) { refresh(AccessMode.DIRECT_ONLY) }
                O2PAction("Export diagnostics", appearance, Modifier.weight(1f),
                    enabled = snapshot != null) { snapshot?.let(export) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        O2PPanel(appearance) {
            Text("DEVICE STATUS", style = MaterialTheme.typography.titleMedium)
            StatusLine("System", "Android " + (snapshot?.androidVersion ?: "—"), appearance)
            StatusLine("Sensor access", "Direct / read only", appearance)
            StatusLine("Elevated access", if (snapshot?.privilege?.available == true) "Verified" else "Not verified", appearance)
            StatusLine("Clock control", "Disabled for safety", appearance)
            Text("Monitoring and session history work independently of tuning.",
                style = MaterialTheme.typography.bodySmall,
                color = appearance.colors.muted)
        }
    }, right = {
        O2PPanel(appearance) {
            Text("SYSTEM AT A GLANCE", style = MaterialTheme.typography.titleMedium)
            val cpu = snapshot?.let { thermal(it, SensorSelection::isCpu) }
            val gpu = snapshot?.let { thermal(it, SensorSelection::isGpu) }
            val gpuClock = snapshot?.gpuNodes?.firstOrNull {
                it.path.endsWith("/gpuclk") || it.path.endsWith("/devfreq/cur_freq")
            }?.value?.trim()?.toLongOrNull()?.let {
                String.format(Locale.US, "%.0f MHz", it / 1_000_000.0)
            } ?: "—"
            val load = snapshot?.gpuNodes?.firstOrNull { it.path.endsWith("/gpu_busy_percentage") }
                ?.value?.trim()?.let { it.substringBefore(" ").trim().removeSuffix("%").trim() }
                ?.toIntOrNull()?.takeIf { it in 0..100 }?.let { it.toString() + "%" } ?: "—"
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricTile(appearance, "CPU", SensorSelection.describe(cpu),
                    "Peak named CPU sensor", Modifier.weight(1f))
                MetricTile(appearance, "GPU", SensorSelection.describe(gpu),
                    "Peak named GPU sensor", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricTile(appearance, "GPU CLOCK", gpuClock,
                    "Load " + load, Modifier.weight(1f))
                val mem = snapshot?.memory?.value
                val available = Parsing.memoryKb(mem, "MemAvailable")
                MetricTile(appearance, "RAM AVAILABLE",
                    if (available == null) "—" else String.format(Locale.US, "%.1f GiB",
                        available / 1048576.0),
                    "System memory", Modifier.weight(1f))
            }
            Text("Summary values are selected from real sensors; individual readings remain under Diagnostics.",
                style = MaterialTheme.typography.bodySmall,
                color = appearance.colors.muted)
        }
    })
}

@Composable
fun MetricTile(
    appearance: AppearanceSettings,
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val colors = appearance.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(colors.panelAlt.copy(alpha =
                if (appearance.style == VisualStyle.GLASS) 0.76f else 1f))
            .border(0.8.dp, colors.outline, RoundedCornerShape(15.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall,
            color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.headlineMedium,
            color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun thermal(snapshot: DeviceSnapshot, matcher: (String) -> Boolean) =
    SensorSelection.hottest(
        snapshot.thermals.map { zone ->
            (zone.type.value ?: "") to Parsing.temperatureC(zone.temperature.value)
        },
        matcher
    )

@Composable
fun StatusLine(label: String, value: String, appearance: AppearanceSettings) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = appearance.colors.ink,
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DiagnosticsPage(
    state: DashboardState,
    appearance: AppearanceSettings,
    refresh: (AccessMode) -> Unit,
    export: (DeviceSnapshot) -> Unit
) {
    val snapshot = state.result
    var showRaw by remember { mutableStateOf(false) }
    O2PColumns(left = {
        O2PPanel(appearance) {
            Text("READ-ONLY DEVICE DISCOVERY", style = MaterialTheme.typography.titleMedium)
            Text("Choose access manually. PServer access is only available when a command is actually verified. No root or su option.",
                color = appearance.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                O2PAction("Direct", appearance, Modifier.weight(1f),
                    primary = state.mode == AccessMode.DIRECT_ONLY, enabled = !state.busy) {
                    refresh(AccessMode.DIRECT_ONLY)
                }
                O2PAction("PServer", appearance, Modifier.weight(1f),
                    primary = state.mode == AccessMode.PSERVER, enabled = !state.busy) {
                    refresh(AccessMode.PSERVER)
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(snapshot?.privilege?.reason ?: "No diagnostic capture yet",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            O2PAction("Share full diagnostic report", appearance, Modifier.fillMaxWidth(),
                enabled = snapshot != null) { snapshot?.let(export) }
        }
        O2PPanel(appearance) {
            Text("CPU POLICIES  ·  ADVANCED", style = MaterialTheme.typography.titleMedium)
            snapshot?.cpus?.forEach { policy ->
                StatusLine(policy.name + "   " + (policy.relatedCpus.value ?: ""),
                    frequency(policy.currentKHz.value), appearance)
            } ?: Text("No capture", color = appearance.colors.muted)
            Text("Full clock ranges and supported OPPs are included in the exported report.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }, right = {
        O2PPanel(appearance) {
            Text("ODIN 2 PORTAL BASE · REFERENCE", style = MaterialTheme.typography.titleMedium)
            Text(Odin2PortalReference.soc, color = appearance.colors.ink)
            Odin2PortalReference.cpuFamilies.forEach { family ->
                StatusLine(
                    family.cores.toString() + " × " + family.name,
                    "up to " + String.format(Locale.US, "%.1f GHz", family.advertisedMaxMHz / 1000.0),
                    appearance
                )
            }
            StatusLine("GPU", Odin2PortalReference.gpu + " · nominal 680 MHz", appearance)
            StatusLine("Memory", Odin2PortalReference.baseRamGb.toString() + " GB LPDDR5X", appearance)
            StatusLine("Screen", Odin2PortalReference.screen, appearance)
            Text(Odin2PortalReference.importantWarning,
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        O2PPanel(appearance) {
            Text("SENSOR LIBRARY", style = MaterialTheme.typography.titleMedium)
            val zones = snapshot?.thermals.orEmpty()
            Text(zones.size.toString() + " thermal zones enumerated · raw entries are deliberately hidden by default",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            O2PAction(if (showRaw) "Hide raw sensors" else "View raw sensors",
                appearance, Modifier.fillMaxWidth()) { showRaw = !showRaw }
            if (showRaw) {
                zones.forEach { zone ->
                    StatusLine(zone.type.value ?: "Zone " + zone.index,
                        zone.readableTemperature(), appearance)
                }
                val gpuNodes = snapshot?.gpuNodes.orEmpty()
                Text("GPU NODES", style = MaterialTheme.typography.titleSmall)
                gpuNodes.forEach { node ->
                    StatusLine(node.path.substringAfterLast("/"),
                        node.value?.take(30) ?: "Unavailable", appearance)
                }
            } else {
                val cpu = snapshot?.let { thermal(it, SensorSelection::isCpu) }
                val gpu = snapshot?.let { thermal(it, SensorSelection::isGpu) }
                StatusLine("CPU peak", SensorSelection.describe(cpu), appearance)
                StatusLine("GPU peak", SensorSelection.describe(gpu), appearance)
                Text("Select View raw sensors only when troubleshooting or comparing kernel values.",
                    style = MaterialTheme.typography.bodySmall,
                    color = appearance.colors.muted)
            }
        }
    })
}

@Composable
private fun AppearancePage(settings: AppearanceSettings) {
    O2PColumns(left = {
        O2PPanel(settings) {
            Text("VISUAL SYSTEM", style = MaterialTheme.typography.titleMedium)
            Text("Choose the material. Both styles recolor the entire interface.",
                color = settings.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                O2PAction("Liquid Glass", settings, Modifier.weight(1f),
                    primary = settings.style == VisualStyle.GLASS) {
                    settings.selectStyle(VisualStyle.GLASS)
                }
                O2PAction("Material", settings, Modifier.weight(1f),
                    primary = settings.style == VisualStyle.MATERIAL) {
                    settings.selectStyle(VisualStyle.MATERIAL)
                }
            }
            Text("COLORWAY  ·  " + settings.accent.label.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall, color = settings.colors.muted)
            O2P_ACCENTS.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { swatch ->
                        Column(
                            modifier = Modifier.weight(1f)
                                .clickable { settings.selectAccent(swatch.id) }
                                .padding(vertical = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.size(30.dp).clip(CircleShape)
                                .background(swatch.color)
                                .border(if (settings.accent.id == swatch.id) 3.dp else 1.dp,
                                    if (settings.accent.id == swatch.id)
                                        settings.colors.ink else settings.colors.outline,
                                    CircleShape))
                            Text(swatch.label, color = settings.colors.muted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (settings.style == VisualStyle.GLASS) {
                HorizontalDivider(color = settings.colors.outline)
                Text("Glass clarity  ·  " + (settings.glassOpacity * 100).toInt() + "%",
                    style = MaterialTheme.typography.labelLarge)
                Slider(value = settings.glassOpacity, onValueChange = settings::setOpacity,
                    valueRange = 0.58f..0.96f)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Light sweep on touch", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = settings.lightSweep, onCheckedChange = settings::setSweep)
                }
            }
        }
    }, right = {
        O2PPanel(settings) {
            Text("LIVE PREVIEW", style = MaterialTheme.typography.titleMedium)
            Text("Glass reacts to your touch; the whole background and surfaces shift with your colorway.",
                color = settings.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(settings, "CPU", "32.9°C", "Preview only", Modifier.weight(1f))
                MetricTile(settings, "GPU", "30.6°C", "Preview only", Modifier.weight(1f))
            }
            O2PAction("Tap to see the light travel", settings,
                Modifier.fillMaxWidth(), primary = true) {}
            Text("Appearance preview values are illustrative—not device measurements.",
                color = settings.colors.muted, style = MaterialTheme.typography.labelSmall)
        }
    })
}

private fun frequency(khz: String?): String {
    val clock = khz?.trim()?.toDoubleOrNull() ?: return "Unavailable"
    return String.format(Locale.US, "%.2f GHz", clock / 1_000_000.0)
}
