package com.odin2portal.control.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
fun GameplayPanel(
    appearance: AppearanceSettings,
    onBegin: () -> Unit,
    sampleMs: Long,
    recordMs: Long,
    manualLabel: String
) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val scope = rememberCoroutineScope()
    val db = remember(context) { GameSessionStore(context.applicationContext) }
    val detector = remember(context) { ForegroundAppDetector(context.applicationContext) }
    val status by GameplayRuntime.state.collectAsState()

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

    O2PPanel(appearance) {
        Text("GAMEPLAY RECORDING", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Text(
            "Start here, switch to Eden or another game, and stop from the notification or this screen. The service collects read-only device measurements.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Background monitor", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    status.starting -> "Starting"
                    status.running -> "RECORDING"
                    else -> "Off"
                },
                color = if (status.running) Color(0xFF9DE1B0) else MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (!permissionGranted) {
            Text(
                "Usage Access is not enabled. Recording is still possible, but app labels will be manually entered or unknown, not automatically detected.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
            )
            O2PAction("Grant Usage Access in Android Settings", appearance, Modifier.fillMaxWidth()) {
                try { context.startActivity(detector.usageAccessSettingsIntent()) }
                catch (e: Exception) { uiMessage = "Cannot open Usage Access settings: " + e.message }
            }
        } else {
            Text("Foreground app detection: Android Usage Access enabled",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9DE1B0))
        }

        if (!notificationGranted) {
            Text("Notifications are needed for an ongoing visible session and a Stop control.",
                style = MaterialTheme.typography.bodySmall)
            O2PAction("Allow monitor notifications", appearance, Modifier.fillMaxWidth()) {
                if (Build.VERSION.SDK_INT >= 33)
                    requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (status.running || status.starting) {
            Text("Active app: " + status.foregroundLabel, fontWeight = FontWeight.SemiBold)
            Text("Identification: " + status.attribution, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            val soc = status.latestSample?.get(Metric.SOC_TEMP)?.display ?: "Unavailable"
            val battery = status.latestSample?.get(Metric.BATTERY_TEMP)?.display ?: "Unavailable"
            Text("SoC " + soc + "   ·   Battery " + battery,
                style = MaterialTheme.typography.bodyMedium)
            Text("Saved intervals: " + status.intervalsSaved,
                style = MaterialTheme.typography.bodyMedium)
            O2PAction("Stop gameplay recording", appearance, Modifier.fillMaxWidth(), primary = true) {
                context.startService(Intent(context, GameplayMonitorService::class.java)
                    .setAction(GameplayMonitorService.ACTION_STOP))
            }
        } else {
            O2PAction(
                "Start gameplay recording", appearance, Modifier.fillMaxWidth(),
                primary = true, enabled = notificationGranted
            ) {
                onBegin() // stop the competing on-screen sampler first
                try {
                    val intent = Intent(context, GameplayMonitorService::class.java)
                        .setAction(GameplayMonitorService.ACTION_START)
                        .putExtra(GameplayMonitorService.EXTRA_SAMPLE_MS, sampleMs)
                        .putExtra(GameplayMonitorService.EXTRA_HISTORY_MS, recordMs)
                        .putExtra(GameplayMonitorService.EXTRA_LABEL, manualLabel.trim().take(64))
                    context.startForegroundService(intent)
                    uiMessage = "Start requested. Check the recording status before opening the game."
                } catch (e: Exception) {
                    uiMessage = "Unable to start: " + (e.message ?: "foreground service error")
                }
            }
        }
        status.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
        uiMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
    }

    Spacer(Modifier.height(12.dp))
    O2PPanel(appearance) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SAVED GAMEPLAY SESSIONS", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            O2PAction("Refresh", appearance) { refreshHistory() }
        }
        Text("Stored locally · last 20 sessions retained · automatic app attribution requires Usage Access.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        if (sessions.isEmpty()) {
            Text("No recorded sessions yet.")
        }
        sessions.forEach { session ->
            HorizontalDivider(color = Color.White.copy(alpha = 0.13f))
            Text(
                (session.manualLabel.ifBlank { "Gameplay session" }) + " · " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(session.startedAt)),
                fontWeight = FontWeight.SemiBold
            )
            Text(session.status + " · " + session.intervalCount + " stored intervals · every " +
                (session.recordIntervalMs / 1000L) + "s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            O2PAction(
                "View session " + session.id,
                appearance, Modifier.fillMaxWidth(),
                enabled = !loading
            ) {
                loading = true
                scope.launch {
                    try {
                        selectedRows = withContext(Dispatchers.IO) { db.read(session.id) }
                        selectedSessionId = session.id
                    } catch (e: Exception) {
                        uiMessage = "Unable to read session: " + (e.message ?: "storage error")
                    } finally { loading = false }
                }
            }
        }

        val selected = selectedSessionId
        if (selected != null) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Text("SELECTED SESSION " + selected, fontWeight = FontWeight.SemiBold)
            Text(selectedRows.size.toString() + " intervals loaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            // Do not imply one app when the player switched apps during the session.
            val packages = selectedRows.mapNotNull { row ->
                row.packageName?.let { row.label + " (" + it + ")" }
            }.distinct().take(5)
            Text(
                if (packages.isEmpty()) "Detected foreground apps: Unavailable (manual labels only)"
                else "Detected foreground apps: " + packages.joinToString(", "),
                style = MaterialTheme.typography.bodySmall
            )
            val soc = selectedRows.mapNotNull { it.values[Metric.SOC_TEMP]?.average }
            val peak = selectedRows.mapNotNull { it.values[Metric.SOC_TEMP]?.maximum }.maxOrNull()
            if (soc.isNotEmpty()) {
                Text(
                    "Average SoC: " + String.format(Locale.US, "%.1f °C", soc.average()) +
                        " · Peak observed: " + String.format(Locale.US, "%.1f °C", peak),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            O2PAction(
                "Export selected session CSV", appearance, Modifier.fillMaxWidth(),
                enabled = selectedRows.isNotEmpty()
            ) {
                val file = "O2P_Gameplay_" + selected + "_" +
                    SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv"
                createReport.launch(file)
            }
            O2PAction(
                "Delete selected session", appearance, Modifier.fillMaxWidth(),
                enabled = !status.running
            ) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { db.delete(selected) }
                        selectedSessionId = null
                        selectedRows = emptyList()
                        refreshHistory()
                    } catch (e: Exception) {
                        uiMessage = "Cannot delete session: " + (e.message ?: "storage error")
                    }
                }
            }
        }
    }
}
