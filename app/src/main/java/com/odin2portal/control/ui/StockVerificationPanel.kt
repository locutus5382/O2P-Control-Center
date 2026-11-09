package com.odin2portal.control.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.odin2portal.control.hardware.AccessMode
import com.odin2portal.control.studio.*

/** User-directed dual-capture review. Every value is observed, never written. */
@Composable
fun StockVerificationPanel(appearance: AppearanceSettings, state: DashboardState) {
    val context = LocalContext.current
    val store = remember(context) { StockEvidenceStore(context.applicationContext) }
    val snapshot = state.result
    val catalogue = remember(snapshot?.capturedAtUtc) {
        snapshot?.let { StudioDiscovery.fromSnapshot(it, Build.FINGERPRINT) }
    }
    var modeLabel by rememberSaveable { mutableStateOf("") }
    var observations by remember { mutableStateOf(store.load()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val review = if (catalogue != null && modeLabel.isNotBlank())
        StockEvidence.review(catalogue, observations, modeLabel) else null
    val snapshotAlreadyCaptured = catalogue?.let { cat ->
        observations.any { it.capturedAt == cat.capturedAt &&
            it.firmwareFingerprint == cat.fingerprint && it.oemModeLabel == modeLabel.trim() }
    } == true

    O2PPanel(appearance) {
        Text("OEM STOCK EVIDENCE · READ ONLY", style = MaterialTheme.typography.titleMedium)
        Text("Compare two snapshots of the same AYN mode. This observes firmware limits; it does not authenticate or restore Stock.",
            color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = modeLabel, onValueChange = { modeLabel = it.take(48); message = null },
            label = { Text("AYN performance mode — manual label") },
            placeholder = { Text("e.g. Standard") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text("Set the desired mode in AYN's own controls, stop other tuning apps, refresh the readings, then capture. Repeat after 20 seconds without changing mode.",
            color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        O2PAction("Capture current observed limits", appearance, Modifier.fillMaxWidth(),
            primary = true,
            enabled = !state.busy && modeLabel.isNotBlank() &&
                catalogue?.domains?.isNotEmpty() == true && !snapshotAlreadyCaptured) {
            val c = catalogue ?: return@O2PAction
            val access = if (snapshot?.accessMode == AccessMode.PSERVER) {
                if (snapshot?.privilege?.available == true) "PServer host identity would be verified; write host unverified"
                else "PServer service probe only; no authenticated host, direct fallback"
            } else "Direct read-only Android app"
            val observation = StockEvidence.capture(c, modeLabel, access)
            message = if (observation != null && store.add(observation)) {
                observations = store.load()
                "Evidence saved. Refresh sensors before the next capture."
            } else "Could not save the current observation."
        }
        if (snapshotAlreadyCaptured) {
            Text("This snapshot is already saved. Use Refresh Direct / Probe PServer above before capturing again.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        val count = catalogue?.let { c ->
            observations.count {
                it.firmwareFingerprint == c.fingerprint && it.device == c.device &&
                    it.oemModeLabel == modeLabel.trim()
            }
        } ?: 0
        StatusLine("Saved captures for this mode", count.toString(), appearance)
        StatusLine("OEM Stock authenticated", "NO", appearance)
        if (review != null) {
            Text(review.summary, style = MaterialTheme.typography.bodyMedium,
                color = if (review.status == EvidenceStatus.STABLE_OBSERVED)
                    appearance.colors.ink else appearance.colors.muted)
            review.details.take(5).forEach { Text(it, color = appearance.colors.muted,
                style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("Enter the exact AYN performance mode name to see its captured evidence.",
                color = appearance.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        O2PAction("Share evidence + raw device report", appearance, Modifier.fillMaxWidth(),
            enabled = catalogue != null && snapshot != null) {
            val c = catalogue ?: return@O2PAction
            val raw = snapshot?.diagnosticText() ?: ""
            val textReport = StockEvidence.report(c, observations, modeLabel) +
                "\n\n=== RAW READ-ONLY DIAGNOSTICS ===\n" + raw
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "O2P OEM Stock evidence (read only)")
                putExtra(Intent.EXTRA_TEXT, textReport)
            }
            context.startActivity(Intent.createChooser(intent, "Share Stock candidate report"))
        }
        if (count > 0) {
            O2PAction("Clear this mode's evidence", appearance, Modifier.fillMaxWidth()) {
                confirmClear = true
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = appearance.colors.muted) }
        Text("No CPU/GPU writes, PServer host launch, root access or boot-time tuning are performed by this checker.",
            style = MaterialTheme.typography.labelSmall, color = appearance.colors.muted)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete saved observations?") },
            text = { Text("Only stored evidence for the selected firmware and mode will be deleted. No hardware setting will change.") },
            confirmButton = {
                TextButton(onClick = {
                    val c = catalogue
                    if (c != null) {
                        store.clearMode(c.fingerprint, modeLabel.trim())
                        observations = store.load()
                    }
                    confirmClear = false
                }) { Text("Delete observations") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}
