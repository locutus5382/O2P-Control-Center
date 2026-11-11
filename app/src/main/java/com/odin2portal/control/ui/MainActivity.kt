package com.odin2portal.control.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.odin2portal.control.hardware.PServerReadOnlyHostClient
import com.odin2portal.control.discovery.DiagnosticJsonExporter
import com.odin2portal.control.domain.DeviceSnapshot
import com.odin2portal.control.domain.Parsing
import com.odin2portal.control.domain.Odin2PortalReference
import com.odin2portal.control.domain.SensorSelection
import com.odin2portal.control.hardware.AccessMode
import com.odin2portal.control.quick.QuickTileUpdates
import com.odin2portal.control.monitor.GameplayRuntime
import com.odin2portal.control.monitor.RecordingStatusBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Read status from :monitor instead of a stale local singleton.
            // Query only while this Activity's composition is alive.
            LaunchedEffect(Unit) {
                // The UI activity can stay alive beneath Eden. IPC polling must
                // stop at ON_STOP; only the user-started :monitor FGS continues.
                this@MainActivity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        val latest = withContext(Dispatchers.IO) {
                            RecordingStatusBridge.read(this@MainActivity)
                        }
                        GameplayRuntime.update(latest)
                        delay(1_500L)
                    }
                }
            }
            val appearance = rememberAppearanceSettings()
            val model: DashboardViewModel = viewModel()
            val state by model.state.collectAsState()
            val initialSection = intent?.getStringExtra("o2p_open_section") ?: "Underclock"
            O2PTheme(appearance) {
                O2PBackdrop(appearance) {
                    ControlCenter(initialSection, state, appearance, model::refresh) { snapshot ->
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

private val sections = listOf("Underclock", "Telemetry", "Advanced", "Diagnostics")

/** Keep legacy Activity/Quick Settings intents working after the navigation migration. */
internal fun normalizeO2PSection(value: String?): String = when (value) {
    "Overview", "Studio", "Underclock" -> "Underclock"
    "Monitor", "Sessions", "Telemetry" -> "Telemetry"
    "Appearance", "Advanced" -> "Advanced"
    "Diagnostics" -> "Diagnostics"
    else -> "Underclock"
}

internal fun stepO2PSection(current: String, delta: Int): String {
    val index = sections.indexOf(normalizeO2PSection(current))
    return sections[Math.floorMod(index + delta, sections.size)]
}

@Composable
private fun ControlCenter(
    initialSection: String,
    state: DashboardState,
    appearance: AppearanceSettings,
    refresh: (AccessMode) -> Unit,
    export: (DeviceSnapshot) -> Unit
) {
    var section by rememberSaveable { mutableStateOf(normalizeO2PSection(initialSection)) }
    BackHandler(enabled = section != "Underclock") { section = "Underclock" }
    // No UI-only telemetry sampler. The dedicated recording foreground service
    // is the sole collector, and only exists after a user-started session.
    val gameplay by GameplayRuntime.state.collectAsState()
    BoxWithConstraints(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            // Only handles controller bumpers while this Activity has keyboard focus.
            val native = event.nativeKeyEvent
            if (native.action != KeyEvent.ACTION_DOWN || native.repeatCount != 0) {
                false
            } else when (native.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1 -> { section = stepO2PSection(section, -1); true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { section = stepO2PSection(section, 1); true }
                else -> false
            }
        }
    ) {
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
                    Text("Odin 2 Portal  ·  v0.9 alpha", style = MaterialTheme.typography.labelSmall,
                        color = appearance.colors.muted)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Header(section, appearance, gameplay.running)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ScreenContent(section, state, appearance, refresh, export)
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
                    ScreenContent(section, state, appearance, refresh, export)
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
    refresh: (AccessMode) -> Unit,
    export: (DeviceSnapshot) -> Unit
) {
    when (section) {
        "Underclock" -> PerformanceStudioPage(appearance, state, refresh)
        "Telemetry" -> TelemetryDestination(appearance)
        "Advanced" -> AdvancedDestination(appearance)
        "Diagnostics" -> DiagnosticsGate(state, appearance, refresh, export)
    }
}

/** One telemetry destination. No independent live-preview sampler. */
@Composable
private fun TelemetryDestination(appearance: AppearanceSettings) {
    GameplayPanel(appearance = appearance)
}

/** Device settings only link to native Android; no privileged display writes. */
@Composable
private fun AdvancedDestination(settings: AppearanceSettings) {
    val context = LocalContext.current
    AppearancePage(settings)
    O2PColumns(left = {
        O2PPanel(settings) {
            Text("DEVICE", style = MaterialTheme.typography.titleMedium)
            Text("Display Colors are owned by Android and AYN. No unverified saturation or color-temperature changes.",
                color = settings.colors.muted)
            O2PAction("Open system display settings", settings, Modifier.fillMaxWidth()) {
                try {
                    context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context,
                        "Display settings are unavailable on this firmware",
                        android.widget.Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    android.widget.Toast.makeText(context,
                        "Android blocked access to display settings",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            Text("Charging separation, fan, controller and trigger controls remain in AYN's built-in tools.",
                color = settings.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }, right = {
        O2PPanel(settings) {
            Text("QUICK ACTIONS", style = MaterialTheme.typography.titleMedium)
            Text("One Quick Settings tile starts or stops a telemetry session. Configure monitoring, HUD and history in the main app.",
                color = settings.colors.muted)
            com.odin2portal.control.quick.QuickTileInstallActions(settings)
            Text("Tuning and recording do not start automatically at boot.",
                color = settings.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
    })
}

/** Verbose diagnostics are opt-in; mandatory safety/readback gates stay active. */
@Composable
private fun DiagnosticsGate(
    state: DashboardState,
    appearance: AppearanceSettings,
    refresh: (AccessMode) -> Unit,
    export: (DeviceSnapshot) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("o2p_diagnostic_ui", Context.MODE_PRIVATE)
    }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    O2PPanel(appearance) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DIAGNOSTIC MODE", style = MaterialTheme.typography.titleMedium)
                Text("Enables detailed read-only hardware inventories, host probes and exports.",
                    color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                prefs.edit().putBoolean("enabled", it).apply()
            })
        }
        Text("Safety checks, effective readbacks and session recovery are always on.",
            color = appearance.colors.muted, style = MaterialTheme.typography.labelSmall)
        if (!enabled) {
            StatusLine("Hardware tuning", "Disabled — read-only alpha", appearance)
            StatusLine("Privileged write access", "Not validated", appearance)
            StatusLine("Current capture", if (state.result == null) "Unavailable" else "Ready", appearance)
        }
    }
    if (enabled) {
        DiagnosticsPage(state, appearance, refresh, export)
        StockVerificationPanel(appearance, state)
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
            StatusLine("PServer identity", if (snapshot?.accessMode == AccessMode.PSERVER && snapshot?.privilege?.available == true) "UID 0 read verified" else "Not verified", appearance)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hostBusy by remember { mutableStateOf(false) }
    var hostTestMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticFeedback by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    O2PColumns(left = {
        O2PPanel(appearance) {
            Text("READ-ONLY DEVICE DISCOVERY", style = MaterialTheme.typography.titleMedium)
            Text("Direct is a passive scan. PServer button checks Binder presence only. " +
                "Use the read-only host verification button below for fixed-node checks. " +
                "No root/su mode or frequency writes.",
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
            O2PAction(if (hostBusy) "PServer host verification running…"
                else "Verify PServer host (read-only)", appearance, Modifier.fillMaxWidth(),
                enabled = !hostBusy) {
                hostBusy = true
                scope.launch {
                    try {
                        val result = PServerReadOnlyHostClient(context.applicationContext)
                            .verifyReadOnly()
                        hostTestMessage = if (result.authenticated)
                            "Host UID " + result.verifiedHostUid +
                                " authenticated; individual node errors are shown in JSON."
                        else "Host not authenticated: " + result.diagnostic.take(220)
                    } finally {
                        hostBusy = false
                    }
                }
            }
            hostTestMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = appearance.colors.muted)
            }
            Text("M1 READ-ONLY VALIDATION", style = MaterialTheme.typography.titleSmall)
            snapshot?.let { captured ->
                DiagnosticJsonExporter.gates(captured, context).forEach { gate ->
                    StatusLine(gate.name, gate.state, appearance)
                    if (gate.state == "BLOCKED" || gate.state == "PARTIAL") {
                        Text(gate.detail, style = MaterialTheme.typography.labelSmall,
                            color = appearance.colors.muted)
                    }
                }
                val android = captured.androidThermal
                StatusLine("Android thermal status",
                    android?.statusLabel ?: "UNAVAILABLE", appearance)
                StatusLine("Android thermal headroom",
                    android?.headroomLabel ?: "UNAVAILABLE", appearance)
                Text(android?.headroomReason ?: "No Android thermal API capture",
                    style = MaterialTheme.typography.labelSmall,
                    color = appearance.colors.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                O2PAction("Copy JSON", appearance, Modifier.weight(1f),
                    enabled = snapshot != null) {
                    snapshot?.let {
                        val json = DiagnosticJsonExporter.export(it, context)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("O2P Read-only Diagnostics", json))
                        diagnosticFeedback = "Redacted JSON copied; no private device ID included."
                    }
                }
                O2PAction("Share JSON", appearance, Modifier.weight(1f),
                    enabled = snapshot != null) {
                    snapshot?.let {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT,
                                "O2P Read-only Device Capabilities JSON")
                            putExtra(Intent.EXTRA_TEXT, DiagnosticJsonExporter.export(it))
                        }
                        context.startActivity(Intent.createChooser(intent,
                            "Share read-only hardware JSON"))
                    }
                }
            }
            diagnosticFeedback?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = appearance.colors.muted)
            }
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
            Text(zones.size.toString() + " named thermal zones enumerated; " +
                (snapshot?.fanNodes?.count { it.isValid } ?: 0).toString() +
                " readable fan/hwmon nodes · raw entries hidden by default",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            O2PAction(if (showRaw) "Hide raw sensors" else "View raw sensors",
                appearance, Modifier.fillMaxWidth()) { showRaw = !showRaw }
            if (showRaw) {
                zones.forEach { zone ->
                    StatusLine(zone.type.value ?: "Zone " + zone.index,
                        zone.readableTemperature(), appearance)
                }
                Text("FAN / HWMON (READ-ONLY)",
                    style = MaterialTheme.typography.titleSmall)
                snapshot?.fanNodes?.forEach { node ->
                    StatusLine(node.path.substringAfterLast("/").take(36),
                        node.value?.take(28) ?: "UNAVAILABLE", appearance)
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
            Text("Choose one of four complete visual families. All 18 colorways recolor the entire interface.",
                color = settings.colors.muted)
            ThemeFamily.entries.chunked(2).forEach { families ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    families.forEach { family ->
                        O2PAction(family.shortLabel, settings, Modifier.weight(1f),
                            primary = settings.family == family) {
                            settings.selectFamily(family)
                        }
                    }
                }
            }
            Text(settings.family.description, color = settings.colors.muted,
                style = MaterialTheme.typography.bodySmall)
            Text("COLORWAY  ·  " + settings.accent.label.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall, color = settings.colors.muted)
            O2P_ACCENTS.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { swatch ->
                        Column(
                            modifier = Modifier.weight(1f)
                                .selectable(
                                    selected = settings.accent.id == swatch.id,
                                    role = Role.RadioButton,
                                    onClick = { settings.selectAccent(swatch.id) }
                                )
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
            HorizontalDivider(color = settings.colors.outline)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Original ambient backgrounds", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = settings.ambientBackdrop,
                    onCheckedChange = settings::setAmbientBackdrop
                )
            }
            Text("Optional static light-mesh artwork for Glass; precision grid for Decky. " +
                "No extra sensor sampling or animations. Turn off to restore the classic background.",
                style = MaterialTheme.typography.labelSmall,
                color = settings.colors.muted)
            if (settings.style == VisualStyle.GLASS) {
                HorizontalDivider(color = settings.colors.outline)
                Text("Glass clarity  ·  " + (settings.glassOpacity * 100).toInt() + "%",
                    style = MaterialTheme.typography.labelLarge)
                Slider(value = settings.glassOpacity, onValueChange = settings::setOpacity,
                    onValueChangeFinished = settings::persistOpacity,
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
            Text(settings.family.label + " · Entire background, surfaces, buttons and focus borders share the selected colorway.",
                color = settings.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(settings, "CPU", "—", "Visual preview", Modifier.weight(1f))
                MetricTile(settings, "GPU", "—", "Visual preview", Modifier.weight(1f))
            }
            O2PAction("Tap to see the light travel", settings,
                Modifier.fillMaxWidth(), primary = true) {}
            Text("Preview uses placeholders—not live device measurements.",
                color = settings.colors.muted, style = MaterialTheme.typography.labelSmall)
        }
    })
}

private fun frequency(khz: String?): String {
    val clock = khz?.trim()?.toDoubleOrNull() ?: return "Unavailable"
    return String.format(Locale.US, "%.2f GHz", clock / 1_000_000.0)
}
