package com.odin2portal.control.quick

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.odin2portal.control.monitor.GameplayMonitorService
import com.odin2portal.control.monitor.RecordingIntervals
import com.odin2portal.control.monitor.RecordingStatusBridge
import com.odin2portal.control.ui.MainActivity

/**
 * One user-action Quick Settings toggle: Start Session / Stop Session.
 *
 * All tuning, recording configuration, HUD adjustment, history and diagnostics
 * live inside the main app, not a second Quick Settings UI.
 * This tile NEVER selects or applies an underclock profile.
 */
class SessionQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updatePresentation()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggleSession() } else toggleSession()
    }

    private fun toggleSession() {
        val current = RecordingStatusBridge.read(applicationContext)
        try {
            val intent = Intent(this, GameplayMonitorService::class.java)
            if (current.running || current.starting) {
                // Explicit user action, not a background-triggered lifecycle stop.
                startService(intent.setAction(GameplayMonitorService.ACTION_STOP))
            } else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                        "Allow O2P notifications in the main app before starting a session",
                        Toast.LENGTH_LONG).show()
                    openTelemetry()
                    return
                }
                val prefs = applicationContext.getSharedPreferences("monitor", MODE_PRIVATE)
                val sampleMs = prefs.getLong("interval", 2000L)
                    .takeIf { it in listOf(1000L, 2000L, 5000L) } ?: 2000L
                val historyMs = prefs.getLong("history_interval", RecordingIntervals.defaultMs)
                    .takeIf(RecordingIntervals::valid) ?: RecordingIntervals.defaultMs
                val label = prefs.getString("session_label", "")?.trim()?.take(64) ?: ""
                // This call originates directly from a user's Quick Settings tap.
                startForegroundService(intent.setAction(GameplayMonitorService.ACTION_START)
                    .putExtra(GameplayMonitorService.EXTRA_SAMPLE_MS, sampleMs)
                    .putExtra(GameplayMonitorService.EXTRA_HISTORY_MS, historyMs)
                    .putExtra(GameplayMonitorService.EXTRA_LABEL, label))
            }
        } catch (e: RuntimeException) {
            Toast.makeText(this, "O2P session action failed; see Telemetry in the main app",
                Toast.LENGTH_LONG).show()
            openTelemetry()
        }
        updatePresentation()
    }

    private fun updatePresentation() {
        val state = RecordingStatusBridge.read(applicationContext)
        qsTile?.apply {
            label = if (state.running || state.starting) "Stop session" else "Start session"
            subtitle = when {
                state.running -> "O2P recording"
                state.starting -> "Starting recorder"
                state.recoveryPending -> "Previous recording interrupted"
                else -> "O2P idle"
            }
            contentDescription = "O2P Control Center. " + label + ". " + subtitle
            this.state = if (state.running || state.starting)
                Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun openTelemetry() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("o2p_open_section", "Telemetry")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, 3305, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

object QuickTileUpdates {
    fun refresh(context: android.content.Context) {
        runCatching {
            TileService.requestListeningState(context,
                ComponentName(context, SessionQuickTileService::class.java))
        }
    }
}
