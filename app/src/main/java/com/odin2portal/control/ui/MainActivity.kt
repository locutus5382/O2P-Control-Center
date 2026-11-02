package com.odin2portal.control.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odin2portal.control.domain.DeviceSnapshot
import com.odin2portal.control.domain.Parsing
import com.odin2portal.control.hardware.AccessMode
import com.odin2portal.control.hardware.HardwareRead
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
                    ControlCenter(
                        state = state,
                        appearance = appearance,
                        refresh = model::refresh,
                        export = { snapshot ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "O2P hardware diagnostic")
                                putExtra(Intent.EXTRA_TEXT, snapshot.diagnosticText())
                            }
                            startActivity(Intent.createChooser(intent, "Share diagnostic"))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlCenter(
    state: DashboardState,
    appearance: AppearanceSettings,
    refresh: (AccessMode) -> Unit,
    export: (DeviceSnapshot) -> Unit
) {
    var page by remember { mutableStateOf("Overview") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("O2P", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
        Text("CONTROL CENTER  /  ALPHA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            O2PAction("Overview", appearance, Modifier.weight(1f), primary = page == "Overview") { page = "Overview" }
            O2PAction("Appearance", appearance, Modifier.weight(1f), primary = page == "Appearance") { page = "Appearance" }
        }
        if (page == "Appearance") {
            AppearancePage(appearance)
        } else {
            O2PPanel(appearance) {
                Text("HARDWARE EXPLORER", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("SAFE READ-ONLY MODE", color = Color(0xFFB4EACF), fontWeight = FontWeight.Bold)
                Text("This release reads the hardware only. No tuning, fan or boot settings are modified.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    O2PAction("Direct", appearance, Modifier.weight(1f), enabled = !state.busy) { refresh(AccessMode.DIRECT_ONLY) }
                    O2PAction("PServer", appearance, Modifier.weight(1f), enabled = !state.busy) { refresh(AccessMode.PSERVER) }
                    O2PAction("Root", appearance, Modifier.weight(1f), enabled = !state.busy) { refresh(AccessMode.ROOT) }
                }
                Text("Privileged probes are only attempted when selected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            state.result?.let { snapshot ->
                O2PPanel(appearance) {
                    Text(snapshot.model, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Detail("Android", snapshot.androidVersion)
                    Detail("Transport", snapshot.privilege.transport.name)
                    Detail("Privilege", if (snapshot.privilege.available) "Verified" else "Unavailable")
                    Detail("UID", snapshot.privilege.reportedUid?.toString() ?: "Not verified")
                    Text(snapshot.privilege.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        O2PAction("Refresh", appearance, Modifier.weight(1f), enabled = !state.busy) { refresh(state.mode) }
                        O2PAction("Share report", appearance, Modifier.weight(1f), primary = true) { export(snapshot) }
                    }
                }
                O2PPanel(appearance) {
                    Text("CPU CLUSTERS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (snapshot.cpus.isEmpty()) Text("No verified CPU policies")
                    snapshot.cpus.forEach { policy ->
                        HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                        Text(policy.name, fontWeight = FontWeight.SemiBold)
                        Detail("CPUs", policy.relatedCpus.value ?: "Unavailable")
                        Detail("Clock", frequency(policy.currentKHz.value))
                        Detail("Min", frequency(policy.minKHz.value))
                        Detail("Max", frequency(policy.maxKHz.value))
                        Detail("Hardware max", frequency(policy.hardwareMaxKHz.value))
                        Detail("Available OPPs", Parsing.frequencyList(policy.availableKHz.value).size.toString())
                    }
                }
                O2PPanel(appearance) {
                    Text("GPU / ADRENO", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    snapshot.gpuNodes.forEach { reading ->
                        Detail(reading.path.substringAfterLast('/'), reading.value?.take(100) ?: reading.error ?: "Unavailable")
                    }
                }
                O2PPanel(appearance) {
                    Text("THERMAL ZONES", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    snapshot.thermals.forEach { zone ->
                        Detail(zone.type.value ?: "Zone ${zone.index}", zone.readableTemperature())
                    }
                    if (snapshot.thermals.isEmpty()) Text("No sensors readable")
                }
                O2PPanel(appearance) {
                    Text("MEMORY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val mem = snapshot.memory.value
                    Detail("Total", memory(Parsing.memoryKb(mem, "MemTotal")))
                    Detail("Available", memory(Parsing.memoryKb(mem, "MemAvailable")))
                    Detail("Swap", memory(Parsing.memoryKb(mem, "SwapTotal")))
                    Detail("Memory PSI", snapshot.pressure.value?.lineSequence()?.firstOrNull() ?: snapshot.pressure.error ?: "Unavailable")
                }
                O2PPanel(appearance) {
                    Text("DIAGNOSTICS", fontWeight = FontWeight.Bold)
                    snapshot.diagnostics.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text("O2PKernelTurb stays unchanged · Live adaptive tuning is not enabled.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun AppearancePage(settings: AppearanceSettings) {
    O2PPanel(settings) {
        Text("APPEARANCE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Choose a visual language. Each has its own saved accent color.",
            color = MaterialTheme.colorScheme.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            O2PAction("Liquid Glass", settings, Modifier.weight(1f), primary = settings.style == VisualStyle.GLASS) {
                settings.selectStyle(VisualStyle.GLASS)
            }
            O2PAction("Material", settings, Modifier.weight(1f), primary = settings.style == VisualStyle.MATERIAL) {
                settings.selectStyle(VisualStyle.MATERIAL)
            }
        }
        Text("ACCENT COLOR", fontWeight = FontWeight.SemiBold)
        O2P_ACCENTS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { accent ->
                    val selected = settings.accent.id == accent.id
                    Column(
                        Modifier.weight(1f).clickable { settings.selectAccent(accent.id) }.padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            color = accent.color,
                            shape = RoundedCornerShape(if (selected) 10.dp else 20.dp),
                            modifier = Modifier.size(38.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                if (selected) 3.dp else 1.dp,
                                if (selected) Color.White else Color.White.copy(alpha = 0.35f)
                            )
                        ) {}
                        Text(accent.label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (settings.style == VisualStyle.GLASS) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.13f))
            Text("GLASS OPACITY  ${(settings.glassOpacity * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
            Slider(
                value = settings.glassOpacity, onValueChange = settings::setOpacity,
                valueRange = 0.55f..0.94f
            )
            Text("Higher opacity improves legibility and reduces background interference.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Light sweep on press", modifier = Modifier.weight(1f))
                Switch(checked = settings.lightSweep, onCheckedChange = settings::setSweep)
            }
            O2PAction("Try the glass highlight", settings, Modifier.fillMaxWidth(), primary = true) {}
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(0.43f), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(0.57f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun frequency(raw: String?): String = raw?.toDoubleOrNull()?.let {
    String.format(Locale.US, "%.2f GHz", it / 1_000_000.0)
} ?: "Unavailable"

private fun memory(kb: Long?): String = kb?.let {
    String.format(Locale.US, "%.2f GiB", it / (1024.0 * 1024.0))
} ?: "Unavailable"
