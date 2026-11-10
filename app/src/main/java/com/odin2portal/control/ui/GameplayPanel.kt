package com.odin2portal.control.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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
import com.odin2portal.control.quick.QuickTileInstallActions
import com.odin2portal.control.monitor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Background recording is exclusively user-initiated and independent of the
 * old on-screen sampler. The UI never writes kernel settings or requires root.
 */
@Composable
fun GameplayPanel(appearance: AppearanceSettings) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val scope = rememberCoroutineScope()
    val db = remember(context) { GameSessionStore(context.applicationContext) }
    val detector = remember(context) { ForegroundAppDetector(context.applicationContext) }
    val status by GameplayRuntime.state.collectAsState()
    // Both the main app and Quick Settings use these saved intervals.
    // Changing configuration never starts another sensor collector.
    val sessionPrefs = remember(context) {
        context.applicationContext.getSharedPreferences("monitor", 0)
    }
    var sampleMs by remember { mutableLongStateOf(
        sessionPrefs.getLong("interval", 2000L)
            .takeIf { it in listOf(1000L, 2000L, 5000L) } ?: 2000L
    ) }
    var recordMs by remember { mutableLongStateOf(
        sessionPrefs.getLong("history_interval", RecordingIntervals.defaultMs)
            .takeIf(RecordingIntervals::valid) ?: RecordingIntervals.defaultMs
    ) }
    var manualLabel by remember {
        mutableStateOf(sessionPrefs.getString("session_label", "")?.take(64) ?: "")
    }

    var permissionGranted by remember { mutableStateOf(detector.hasUsageAccess()) }
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var uiMessage by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf(emptyList<SavedGameSession>()) }
    var selectedSessionId by remember { mutableStateOf<Long?>(null) }
    var selectedRows by remember { mutableStateOf(emptyList<RecordedInterval>()) }
    var loading by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<Long?>(null) }

    val requestNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { approved ->
        notificationGranted = approved
        if (!approved) uiMessage = "Notification permission is required for a visible gameplay recording and Stop action."
    }

    fun refreshHistory() {
        scope.launch {
            try {
                sessions = withContext(Dispatchers.IO) { db.list(12) }
            } catch (e: Exception) {
                uiMessage = "Cannot load sessions: " + (e.message ?: "storage error")
            }
        }
    }

    val createReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val id = selectedSessionId
        if (uri != null && id != null) {
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        val rows = db.read(id)
                        val csv = MonitorCsv.formatHistory(rows)
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("Unable to open selected file")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(csv) }
                        rows.size
                    }
                    uiMessage = "Exported " + count + " history intervals."
                } catch (e: Exception) {
                    uiMessage = "Export failed: " + (e.message ?: "storage error")
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, db) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = detector.hasUsageAccess()
                notificationGranted = Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                refreshHistory()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            db.close()
        }
    }
    LaunchedEffect(status.running, status.starting, status.intervalsSaved) { refreshHistory() }

    O2PColumns(left = {
        O2PPanel(appearance) {
            Text("SESSION CONFIGURATION", style = MaterialTheme.typography.titleMedium)
            Text("One collector runs only after Start Session. No live telemetry polling while idle.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            Text("Telemetry sample interval", color = appearance.colors.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1000L to "1s", 2000L to "2s", 5000L to "5s").forEach { (ms, label) ->
                    O2PAction(label, appearance, Modifier.weight(1f),
                        enabled = !status.running && !status.starting, primary = sampleMs == ms) {
                        sampleMs = ms
                        sessionPrefs.edit().putLong("interval", ms).apply()
                    }
                }
            }
            Text("Saved history aggregation", color = appearance.colors.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5000L to "5s", 10000L to "10s",
                    30000L to "30s", 120000L to "2m").forEach { (ms, label) ->
                    O2PAction(label, appearance, Modifier.weight(1f),
                        enabled = !status.running && !status.starting, primary = recordMs == ms) {
                        recordMs = ms
                        sessionPrefs.edit().putLong("history_interval", ms).apply()
                    }
                }
            }
            OutlinedTextField(
                value = manualLabel, onValueChange = {
                    if (!status.running && !status.starting) {
                        manualLabel = it.take(64)
                        sessionPrefs.edit().putString("session_label", manualLabel).apply()
                    }
                },
                enabled = !status.running && !status.starting,
                singleLine = true, label = { Text("Session label (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        O2PPanel(appearance) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GAMEPLAY RECORDER", style = MaterialTheme.typography.titleMedium)
                    Text("Read-only measurements while Eden or another game is open",
                        style = MaterialTheme.typography.bodySmall,
                        color = appearance.colors.muted)
                }
                Text(if (status.running) "●  REC" else if (status.starting) "Starting..." else "●  Idle",
                    color = if (status.running) Color(0xFF35BA76) else appearance.colors.muted,
                    style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricTile(appearance, "CPU",
                    status.latestSample?.get(Metric.SOC_TEMP)?.display ?: status.cpuDisplay ?: "—",
                    "Peak reported sensor", Modifier.weight(1f))
                MetricTile(appearance, "GPU",
                    status.latestSample?.get(Metric.GPU_TEMP)?.display ?: status.gpuDisplay ?: "—",
                    "Peak reported sensor", Modifier.weight(1f))
            }
            StatusLine("Active app",
                if (status.running) status.foregroundLabel else "None", appearance)
            StatusLine("App identification", if (status.running) status.attribution else "Inactive",
                appearance)
            StatusLine("Saved history intervals", status.intervalsSaved.toString(), appearance)

            if (status.running || status.starting) {
                O2PAction("Stop recording", appearance, Modifier.fillMaxWidth(), primary = true) {
                    context.startService(Intent(context, GameplayMonitorService::class.java)
                        .setAction(GameplayMonitorService.ACTION_STOP))
                }
            } else {
                O2PAction("Start gameplay session", appearance, Modifier.fillMaxWidth(),
                    primary = true, enabled = notificationGranted) {
                    try {
                        val intent = Intent(context, GameplayMonitorService::class.java)
                            .setAction(GameplayMonitorService.ACTION_START)
                            .putExtra(GameplayMonitorService.EXTRA_SAMPLE_MS, sampleMs)
                            .putExtra(GameplayMonitorService.EXTRA_HISTORY_MS, recordMs)
                            .putExtra(GameplayMonitorService.EXTRA_LABEL, manualLabel.trim().take(64))
                        context.startForegroundService(intent)
                        uiMessage = "Start requested. Check the notification before opening the game."
                    } catch (e: Exception) {
                        uiMessage = "Unable to start: " + (e.message ?: "foreground service error")
                    }
                }
            }
            QuickTileInstallActions(appearance)
            Text("The Quick Settings tile starts or stops the same recorder using the saved session configuration.",
                style = MaterialTheme.typography.bodySmall,
                color = appearance.colors.muted)
            val journal = remember { SessionReliabilityJournal(context.applicationContext) }
            Text("Service lifecycle: " + (status.lifecycleStatus ?: journal.status()),
                style = MaterialTheme.typography.bodySmall,
                color = appearance.colors.muted)
            status.monitorProcessId?.let { processId ->
                Text("Recorder process: " + processId + " (separate from app UI)",
                    style = MaterialTheme.typography.bodySmall, color = appearance.colors.muted)
            }
            (status.lifecycleEvents.ifEmpty { journal.events() }).takeLast(5).reversed()
                .forEach { logLine ->
                    Text(logLine, style = MaterialTheme.typography.labelSmall,
                        color = appearance.colors.muted)
                }
            O2PAction("Share recorder lifecycle report", appearance, Modifier.fillMaxWidth()) {
                val report = RecordingStatusBridge.diagnosticReport(context.applicationContext, status)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "O2P recording lifecycle report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(share, "Share recording report"))
            }
            status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            uiMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        HudControls(appearance)
        O2PPanel(appearance) {
            Text("PRIVACY & ACCESS", style = MaterialTheme.typography.titleMedium)
            StatusLine("Android Usage Access",
                if (permissionGranted) "Granted" else "Not granted", appearance)
            StatusLine("Notifications",
                if (notificationGranted) "Allowed" else "Required", appearance)
            val power = context.getSystemService(PowerManager::class.java)
            val unrestricted = runCatching {
                power?.isIgnoringBatteryOptimizations(context.packageName) == true
            }.getOrDefault(false)
            StatusLine("Battery optimization",
                if (unrestricted) "Unrestricted/exempt" else "May restrict background recording",
                appearance)
            if (!unrestricted) {
                Text("AYN game-launch behavior may terminate non-exempt apps. " +
                    "Review O2P's battery/background restrictions if interruptions continue; " +
                    "the app does not change this setting automatically.",
                    style = MaterialTheme.typography.bodySmall, color = appearance.colors.muted)
                O2PAction("Open O2P system app settings", appearance, Modifier.fillMaxWidth()) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName)))
                }
            }
            if (!permissionGranted) {
                Text("Grant Usage Access to identify the foreground app. Without it, labels are manual or unknown.",
                    color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
                O2PAction("Open Usage Access settings", appearance, Modifier.fillMaxWidth()) {
                    try { context.startActivity(detector.usageAccessSettingsIntent()) }
                    catch (e: Exception) { uiMessage = "Could not open Usage Access settings" }
                }
            }
            if (!notificationGranted) {
                Text("A visible notification with Stop action is required.",
                    color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
                O2PAction("Allow notifications", appearance, Modifier.fillMaxWidth()) {
                    if (Build.VERSION.SDK_INT >= 33)
                        requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            Text("Nothing uploads automatically. History stays on this device. No CPU/GPU tuning or fan changes.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }, right = {
        O2PPanel(appearance) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("SESSION HISTORY", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                O2PAction("Refresh", appearance) { refreshHistory() }
            }
            Text("Last 20 sessions stored locally. Choose one to see the identified apps and export its data.",
                style = MaterialTheme.typography.bodySmall,
                color = appearance.colors.muted)
            if (sessions.isEmpty()) Text("No sessions recorded yet.",
                color = appearance.colors.muted)
            sessions.take(12).forEach { session ->
                HorizontalDivider(color = appearance.colors.outline)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(session.manualLabel.ifBlank { "Gameplay session" },
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(session.startedAt)) + "  ·  " + session.intervalCount +
                                " intervals  ·  " + session.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = appearance.colors.muted
                        )
                    }
                    O2PAction("Open", appearance, enabled = !loading) {
                        loading = true
                        scope.launch {
                            try {
                                selectedRows = withContext(Dispatchers.IO) { db.read(session.id) }
                                selectedSessionId = session.id
                            } catch (e: Exception) {
                                uiMessage = "Unable to read session: " +
                                    (e.message ?: "storage error")
                            } finally { loading = false }
                        }
                    }
                }
            }
        }
        val selected = selectedSessionId
        if (selected != null) {
            O2PPanel(appearance) {
                Text("SELECTED SESSION  /  " + selected, style = MaterialTheme.typography.titleMedium)
                Text(selectedRows.size.toString() + " aggregated intervals",
                    color = appearance.colors.muted)
                val packages = selectedRows.mapNotNull { row ->
                    row.packageName?.let { row.label + " (" + it + ")" }
                }.distinct().take(4)
                Text(
                    if (packages.isEmpty()) "Foreground app: not automatically identified"
                    else "Detected apps: " + packages.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall
                )
                val soc = selectedRows.mapNotNull {
                    it.values[Metric.SOC_TEMP]?.average
                }
                val peak = selectedRows.mapNotNull {
                    it.values[Metric.SOC_TEMP]?.maximum
                }.maxOrNull()
                if (soc.isNotEmpty()) {
                    StatusLine("CPU sensor average",
                        String.format(Locale.US, "%.1f °C", soc.average()), appearance)
                    StatusLine("Peak CPU sensor",
                        if (peak == null) "—" else
                            String.format(Locale.US, "%.1f °C", peak), appearance)
                }
                O2PAction("Export CSV", appearance, Modifier.fillMaxWidth(),
                    primary = true, enabled = selectedRows.isNotEmpty()) {
                    val file = "O2P_Gameplay_" + selected + "_" +
                        SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv"
                    createReport.launch(file)
                }
                O2PAction("Delete session", appearance, Modifier.fillMaxWidth(),
                    enabled = !status.running) { pendingDeletion = selected }
            }
        }
    })
    val toDelete = pendingDeletion
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete recorded session?") },
            text = { Text("This permanently deletes session " + toDelete +
                " and its private telemetry history. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletion = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { db.delete(toDelete) }
                            if (selectedSessionId == toDelete) {
                                selectedSessionId = null
                                selectedRows = emptyList()
                            }
                            refreshHistory()
                        } catch (e: Exception) {
                            uiMessage = "Cannot delete session: " + (e.message ?: "storage error")
                        }
                    }
                }) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
            }
        )
    }

}
