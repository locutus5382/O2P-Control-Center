package com.odin2portal.control.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.odin2portal.control.hardware.AccessMode
import com.odin2portal.control.studio.*
import java.util.Locale

/**
 * v0.6 Stage 1: simulation + saved user preferences only.
 * There is deliberately no kernel writer, PServer apply call or enabled Apply.
 */
@Composable
fun PerformanceStudioPage(
    appearance: AppearanceSettings,
    state: DashboardState,
    refresh: (AccessMode) -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { StudioProfileStore(context.applicationContext) }
    val catalog = remember(state.result?.capturedAtUtc) {
        state.result?.let { StudioDiscovery.fromSnapshot(it, Build.FINGERPRINT) }
    }
    var chosen by remember { mutableStateOf("preset:STOCK") }
    var draft by remember { mutableStateOf(emptyMap<String, Long>()) }
    var saved by remember { mutableStateOf(emptyList<SavedStudioProfile>()) }
    var customName by remember { mutableStateOf("My underclock") }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun loadProfile(key: String) {
        val c = catalog ?: return
        chosen = key
        val preset = StudioPreset.entries.firstOrNull { key == "preset:" + it.name }
        val item = saved.firstOrNull { it.id == key }
        draft = when {
            preset != null -> store.presetOverride(c, preset)?.values ?: preset.values(c)
            item != null -> item.values
            else -> emptyMap()
        }
        feedback = null
    }
    LaunchedEffect(catalog?.capturedAt, catalog?.fingerprint) {
        if (catalog != null) {
            saved = store.load(catalog)
            loadProfile("preset:STOCK")
        } else {
            saved = emptyList()
            draft = emptyMap()
        }
    }

    val selectedPreset = StudioPreset.entries.firstOrNull { chosen == "preset:" + it.name }
    val custom = saved.firstOrNull { it.id == chosen }
    val hasTable = catalog?.domains?.isNotEmpty() == true
    val validation = catalog?.let { ProfileValidator.error(it, draft) }
    val editingStock = selectedPreset == StudioPreset.STOCK

    O2PColumns(left = {
        O2PPanel(appearance) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PERFORMANCE STUDIO", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text("READ ONLY", color = appearance.colors.muted,
                    style = MaterialTheme.typography.labelSmall)
            }
            Text("Underclock-only design. No frequencies are changed in this preview.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enable tuning", style = MaterialTheme.typography.labelLarge)
                    Text("OFF · Stock / App Default", style = MaterialTheme.typography.bodySmall,
                        color = appearance.colors.muted)
                }
                Switch(checked = false, onCheckedChange = {}, enabled = false)
            }
            Text("When enabled in a later, hardware-verified build, OFF will request and verify full Stock restoration. It is not a functional restoration control yet.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                O2PAction("Refresh Direct", appearance, Modifier.weight(1f),
                    enabled = !state.busy) { refresh(AccessMode.DIRECT_ONLY) }
                O2PAction("Probe PServer", appearance, Modifier.weight(1f),
                    enabled = !state.busy) { refresh(AccessMode.PSERVER) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(catalog?.hardwareNote() ?: "Discovering frequency policies...",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            Text("PServer: " + (state.result?.privilege?.reason ?: "not probed"),
                style = MaterialTheme.typography.bodySmall, color = appearance.colors.muted)
        }

        StockVerificationPanel(appearance, state)

        O2PPanel(appearance) {
            Text("BUILT-IN PROFILES", style = MaterialTheme.typography.titleMedium)
            StudioPreset.entries.forEach { preset ->
                O2PAction(
                    when (preset) {
                        StudioPreset.STOCK -> "Stock reference · observed 100%"
                        StudioPreset.LIGHT -> "Light Underclock"
                        StudioPreset.MODERATE -> "Moderate Underclock"
                        StudioPreset.STRONG -> "Strong Underclock"
                    },
                    appearance, Modifier.fillMaxWidth(),
                    primary = chosen == "preset:" + preset.name,
                    enabled = hasTable
                ) { loadProfile("preset:" + preset.name) }
            }
            if (saved.any { it.originalPreset == null }) {
                HorizontalDivider(color = appearance.colors.outline)
                Text("MY PROFILES", style = MaterialTheme.typography.titleMedium)
                saved.filter { it.originalPreset == null }.forEach { profile ->
                    O2PAction(profile.name, appearance, Modifier.fillMaxWidth(),
                        primary = chosen == profile.id) { loadProfile(profile.id) }
                }
            }
        }
        O2PPanel(appearance) {
            Text("SAVE A CUSTOM PROFILE", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = customName, onValueChange = { customName = it.take(48) },
                label = { Text("Profile name") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            O2PAction("Save current preview as new profile", appearance, Modifier.fillMaxWidth(),
                enabled = validation == null && !editingStock) {
                val c = catalog ?: return@O2PAction
                val new = store.createCustom(c, customName, draft)
                if (new != null) {
                    saved = store.load(c)
                    loadProfile(new.id)
                    feedback = "Saved preview only. No device settings changed."
                } else feedback = "Unable to save; verify fields and name."
            }
        }
    }, right = {
        O2PPanel(appearance) {
            Text("FREQUENCY LIMIT PREVIEW", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    editingStock -> "Stock candidate · observed ceilings ONLY; not OEM-verified."
                    selectedPreset != null -> selectedPreset.description
                    custom != null -> custom.name
                    else -> "Select a profile"
                },
                color = appearance.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (catalog == null || catalog.domains.isEmpty()) {
                Text("No verified selectable CPU/GPU frequency tables were available from this capture. Do not guess MHz values; try Direct/PServer diagnostics.",
                    color = appearance.colors.muted)
            } else {
                catalog.domains.forEach { domain ->
                    HorizontalDivider(color = appearance.colors.outline)
                    val index = domain.supported.indexOf(draft[domain.id])
                        .takeIf { it >= 0 }
                        ?: domain.supported.lastIndex
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(domain.label, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "Current " + (domain.currentClock?.let(domain::mhz) ?: "unavailable")
                                    + " · highest listed step " + domain.mhz(domain.observedCeiling),
                                color = appearance.colors.muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(domain.percentOfObserved(domain.supported[index]).toString() + "%",
                            style = MaterialTheme.typography.titleLarge)
                    }
                    Text(domain.mhz(domain.supported[index]),
                        fontWeight = FontWeight.SemiBold, color = appearance.colors.ink)
                    Text("Reported scaling maximum: " +
                        (domain.reportedRuntimeCeiling?.let(domain::mhz) ?: "not readable") +
                        " · reported scaling minimum: " +
                        (domain.reportedRuntimeMinimum?.let(domain::mhz) ?: "not readable"),
                        color = appearance.colors.muted,
                        style = MaterialTheme.typography.bodySmall)
                    domain.referenceWarning?.let { warning ->
                        Text(warning, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = index.toFloat(),
                        onValueChange = { v ->
                            if (!editingStock) {
                                val pos = v.toInt().coerceIn(0, domain.supported.lastIndex)
                                draft = draft + (domain.id to domain.supported[pos])
                            }
                        },
                        valueRange = 0f..domain.supported.lastIndex.toFloat(),
                        steps = (domain.supported.size - 2).coerceAtLeast(0),
                        enabled = !editingStock
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(domain.mhz(domain.supported.first()), color = appearance.colors.muted,
                            style = MaterialTheme.typography.labelSmall)
                        Text("Highest listed step " + domain.mhz(domain.observedCeiling),
                            color = appearance.colors.muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text("Slider steps come from exposed kernel tables, never estimated values. The highest step is not proof of an OEM Stock ceiling.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            validation?.let { Text("Preview incomplete: " + it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
            O2PAction("Apply to hardware · locked pending PServer validation",
                appearance, Modifier.fillMaxWidth(), primary = true, enabled = false) {}
        }

        O2PPanel(appearance) {
            Text("PROFILE EDITOR", style = MaterialTheme.typography.titleMedium)
            when {
                editingStock -> Text("Stock is immutable; always available as the default.",
                    color = appearance.colors.muted)
                selectedPreset != null -> {
                    Text("Preset adjustments are saved as local overrides. Original preset remains recoverable.",
                        color = appearance.colors.muted)
                    O2PAction("Save changes to preset", appearance, Modifier.fillMaxWidth(),
                        primary = true, enabled = validation == null) {
                        val c = catalog ?: return@O2PAction
                        feedback = if (store.savePreset(c, selectedPreset, draft)) {
                            saved = store.load(c)
                            "Preset preview saved. No hardware writes."
                        } else "Unable to save preset preview"
                    }
                    O2PAction("Reset preset to default", appearance, Modifier.fillMaxWidth()) {
                        val c = catalog ?: return@O2PAction
                        store.resetPreset(c, selectedPreset)
                        saved = store.load(c)
                        loadProfile("preset:" + selectedPreset.name)
                    }
                }
                custom != null -> {
                    O2PAction("Save changes to custom profile", appearance, Modifier.fillMaxWidth(),
                        primary = true, enabled = validation == null) {
                        val c = catalog ?: return@O2PAction
                        feedback = if (store.save(c, custom.copy(values = draft))) {
                            saved = store.load(c)
                            "Custom preview saved. No hardware writes."
                        } else "Unable to save changes"
                    }
                    O2PAction("Delete custom profile", appearance, Modifier.fillMaxWidth()) {
                        val c = catalog ?: return@O2PAction
                        store.delete(c, custom.id)
                        saved = store.load(c)
                        loadProfile("preset:STOCK")
                        feedback = "Deleted local custom profile."
                    }
                }
                else -> Text("Select a profile.", color = appearance.colors.muted)
            }
            feedback?.let {
                Text(it, color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    })
}
