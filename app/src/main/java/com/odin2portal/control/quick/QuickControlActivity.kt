package com.odin2portal.control.quick

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odin2portal.control.R
import com.odin2portal.control.monitor.*
import com.odin2portal.control.studio.*
import com.odin2portal.control.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** Quick Settings always enters an explicit, user-operated control panel. */
class QuickControlActivity : ComponentActivity() {
    companion object {
        const val ACTION_PROFILE = "com.odin2portal.control.quick.PROFILE"
        const val ACTION_SESSION = "com.odin2portal.control.quick.SESSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val which = if (intent?.action == ACTION_SESSION) "session" else "profile"
        setContent {
            LaunchedEffect(Unit) {
                while (isActive) {
                    val latest = withContext(Dispatchers.IO) {
                        RecordingStatusBridge.read(this@QuickControlActivity)
                    }
                    GameplayRuntime.update(latest)
                    delay(1_500L)
                }
            }
            val appearance = rememberAppearanceSettings()
            O2PTheme(appearance) {
                O2PBackdrop(appearance) {
                    Column(Modifier.fillMaxSize().padding(18.dp)
                        .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (which == "session") SessionQuickPanel(appearance, ::finish)
                        else ProfileQuickPanel(appearance, ::finish)
                    }
                }
            }
        }
    }
}

object QuickTileInstaller {
    fun request(activity: Activity, session: Boolean, result: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            result("Open Android Quick Settings → Edit and add the O2P tile manually.")
            return
        }
        val manager = activity.getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            result("Open Quick Settings → Edit to add O2P manually.")
            return
        }
        val service = if (session) SessionQuickTileService::class.java
            else ProfileQuickTileService::class.java
        val label = if (session) "O2P Session" else "O2P Profile"
        val icon = if (session) R.drawable.ic_qs_session else R.drawable.ic_qs_profile
        manager.requestAddTileService(ComponentName(activity, service), label,
            Icon.createWithResource(activity, icon), activity.mainExecutor) { response ->
            result(when (response) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                    "$label tile added."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    "$label tile is already in Quick Settings."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                    "Tile not added. You can add it via Quick Settings → Edit."
                else -> "Android could not add the tile; use Quick Settings → Edit."
            })
        }
    }
}

@Composable
fun QuickTileInstallActions(appearance: AppearanceSettings, session: Boolean) {
    val activity = LocalContext.current as? Activity
    var feedback by remember { mutableStateOf<String?>(null) }
    O2PAction("Add " + (if (session) "O2P Session" else "O2P Profile") + " Quick Settings tile",
        appearance, Modifier.fillMaxWidth(), enabled = activity != null) {
        activity?.let { QuickTileInstaller.request(it, session) { message ->
            feedback = message
        } }
    }
    feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ProfileQuickPanel(appearance: AppearanceSettings, dismiss: () -> Unit) {
    val context = LocalContext.current
    val model: DashboardViewModel = viewModel()
    val state by model.state.collectAsState()
    val catalogue = remember(state.result?.capturedAtUtc) {
        state.result?.let { StudioDiscovery.fromSnapshot(it, Build.FINGERPRINT) }
    }
    val store = remember { StudioProfileStore(context.applicationContext) }
    val prefs = remember { QuickProfilePreferences(context.applicationContext) }
    var token by remember { mutableIntStateOf(0) }
    val saved = remember(catalogue, token) {
        catalogue?.let(store::load).orEmpty()
    }
    val options = remember(catalogue, saved) {
        catalogue?.let { prefs.options(it, saved) }.orEmpty()
    }
    var current by remember { mutableStateOf(prefs.selected()?.id ?: "preset:STOCK") }
    var edit by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("My underclock") }
    var message by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(emptyMap<String, Long>()) }

    LaunchedEffect(current, catalogue, saved) {
        val c = catalogue ?: return@LaunchedEffect
        val preset = StudioPreset.entries.firstOrNull { current == "preset:" + it.name }
        draft = when {
            preset != null -> store.presetOverride(c, preset)?.values ?: preset.values(c)
            else -> saved.firstOrNull { it.id == current }?.values ?: emptyMap()
        }
    }

    O2PPanel(appearance) {
        Text("O2P PROFILE · QUICK ACCESS", style = MaterialTheme.typography.titleLarge)
        Text("These are READ-ONLY underclock previews. PServer and OEM Stock are not verified, so this tile CANNOT change clocks.",
            color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (catalogue?.domains?.isNotEmpty() != true) {
            Text("Discovering CPU/GPU frequency tables…", color = appearance.colors.muted)
        } else {
            Text("Choose a preview", style = MaterialTheme.typography.titleMedium)
            options.forEach { choice ->
                O2PAction(choice.title, appearance, Modifier.fillMaxWidth(),
                    primary = current == choice.id) {
                    current = choice.id
                    prefs.choose(choice)
                    QuickTileUpdates.refresh(context.applicationContext)
                    message = choice.title + " selected for preview. No tuning was applied."
                }
            }
            O2PAction("Next profile preview", appearance, Modifier.fillMaxWidth()) {
                val next = prefs.cyclePreview()
                if (next != null) {
                    current = next.id
                    QuickTileUpdates.refresh(context.applicationContext)
                    message = next.title + " selected for preview; hardware unchanged."
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tap tile to cycle previews", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Switch(checked = prefs.cycleOnTap, onCheckedChange = {
                    prefs.setCycleOnTap(it)
                    token++
                })
            }
            O2PAction(if (edit) "Hide frequency editor" else "Tune preview · exact MHz",
                appearance, Modifier.fillMaxWidth()) { edit = !edit }
            if (edit) {
                Text("Preview editing only · original Stock limits remain untouched.",
                    color = appearance.colors.muted)
                catalogue.domains.forEach { domain ->
                    val value = draft[domain.id] ?: domain.observedCeiling
                    val idx = domain.supported.indexOf(value).coerceAtLeast(0)
                    val index = idx.coerceIn(0, domain.supported.lastIndex)
                    Text(domain.label + "  " + domain.mhz(domain.supported[index]))
                    Slider(
                        value = index.toFloat(),
                        onValueChange = {
                            draft = draft + (domain.id to domain.supported[
                                it.toInt().coerceIn(0, domain.supported.lastIndex)])
                        },
                        valueRange = 0f..domain.supported.lastIndex.toFloat(),
                        steps = (domain.supported.size - 2).coerceAtLeast(0)
                    )
                }
                OutlinedTextField(value = newName, onValueChange = {
                    newName = it.take(48)
                }, label = { Text("Save as custom preview") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                O2PAction("Save new custom preview", appearance, Modifier.fillMaxWidth(),
                    enabled = ProfileValidator.error(catalogue, draft) == null) {
                    val new = store.createCustom(catalogue, newName, draft)
                    if (new != null) {
                        token++
                        current = new.id
                        message = "Saved preview. Actual device clocks were NOT changed."
                    } else message = "Preview was not saved."
                }
            }
            O2PAction("Open full Performance Studio", appearance, Modifier.fillMaxWidth()) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .putExtra("o2p_open_section", "Studio"))
                dismiss()
            }
        }
        message?.let { Text(it, color = appearance.colors.muted) }
        O2PAction("Close", appearance, Modifier.fillMaxWidth()) { dismiss() }
    }
}

@Composable
private fun SessionQuickPanel(appearance: AppearanceSettings, dismiss: () -> Unit) {
    val context = LocalContext.current
    val status by GameplayRuntime.state.collectAsState()
    val settings = remember { HudPreferences(context.applicationContext) }
    var message by remember { mutableStateOf<String?>(null) }
    fun startRecording() {
        try {
            context.startForegroundService(Intent(context, GameplayMonitorService::class.java)
                .setAction(GameplayMonitorService.ACTION_START)
                .putExtra(GameplayMonitorService.EXTRA_SAMPLE_MS, 2000L)
                .putExtra(GameplayMonitorService.EXTRA_HISTORY_MS, RecordingIntervals.defaultMs))
            message = "Start requested. Check the ongoing O2P notification."
        } catch (error: Exception) {
            message = "Could not start: " + (error.message ?: "unknown error")
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else message = "Notification permission is required for a visible Stop action."
    }
    O2PPanel(appearance) {
        Text("O2P SESSION · QUICK ACCESS", style = MaterialTheme.typography.titleLarge)
        Text(if (status.running) "● Monitoring active" else "● Not recording",
            style = MaterialTheme.typography.titleMedium)
        Text("One recording service, one HUD. Controls also work while Eden is in the foreground.",
            color = appearance.colors.muted)
        if (!status.running && !status.starting) {
            O2PAction("Start session", appearance, Modifier.fillMaxWidth(), primary = true) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else startRecording()
            }
        } else {
            O2PAction("End session", appearance, Modifier.fillMaxWidth(), primary = true) {
                context.startService(Intent(context, GameplayMonitorService::class.java)
                    .setAction(GameplayMonitorService.ACTION_STOP))
                message = "Stop requested. Recorded data will be saved."
            }
        }
        O2PAction("Refresh HUD window", appearance, Modifier.fillMaxWidth(),
            enabled = status.running) {
            context.startService(Intent(context, GameplayMonitorService::class.java)
                .setAction(GameplayMonitorService.ACTION_REATTACH_HUD))
            message = "One window refresh requested. Eden visibility is not guaranteed."
        }
        O2PAction(if (settings.floatingVisible) "Hide HUD" else "Show HUD",
            appearance, Modifier.fillMaxWidth(), enabled = status.running) {
            context.startService(Intent(context, GameplayMonitorService::class.java)
                .setAction(GameplayMonitorService.ACTION_TOGGLE_HUD))
            settings.refresh()
        }
        if (!Settings.canDrawOverlays(context)) {
            Text("Floating HUD permission is not granted; monitoring works without overlay.",
                color = appearance.colors.muted)
            O2PAction("Display over other apps permission", appearance, Modifier.fillMaxWidth()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)))
            }
        }
        val journal = remember { SessionReliabilityJournal(context.applicationContext) }
        Text("Session lifecycle: " + (status.lifecycleStatus ?: journal.status()),
            color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        status.monitorProcessId?.let { pid ->
            Text("Recorder process: " + pid,
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        (status.lifecycleEvents.ifEmpty { journal.events() }).takeLast(4).reversed()
            .forEach { item ->
                Text(item, style = MaterialTheme.typography.labelSmall,
                    color = appearance.colors.muted)
            }
        O2PAction("Share recorder lifecycle report", appearance, Modifier.fillMaxWidth()) {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "O2P recording lifecycle report")
                putExtra(Intent.EXTRA_TEXT, RecordingStatusBridge.diagnosticReport(status))
            }
            context.startActivity(Intent.createChooser(share, "Share recording report"))
        }
        Text("HUD: " + (status.hudDiagnostic ?: "No running HUD diagnostic"),
            color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        Text("Foreground: " + status.foregroundLabel, style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        O2PAction("Open full Sessions screen", appearance, Modifier.fillMaxWidth()) {
            context.startActivity(Intent(context, MainActivity::class.java)
                .putExtra("o2p_open_section", "Sessions"))
            dismiss()
        }
        O2PAction("Close", appearance, Modifier.fillMaxWidth()) { dismiss() }
    }
}
