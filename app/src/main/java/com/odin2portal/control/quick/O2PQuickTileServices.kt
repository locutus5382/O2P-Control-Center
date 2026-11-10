package com.odin2portal.control.quick

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.odin2portal.control.monitor.RecordingStatusBridge

/** Two native Android Quick Settings tiles; neither is a privileged tuning path. */
abstract class O2PBaseTileService : TileService() {
    protected abstract val panel: String
    protected abstract fun updatePresentation()

    override fun onStartListening() {
        super.onStartListening()
        updatePresentation()
    }

    protected fun launchPanel() {
        val intent = Intent(this, QuickControlActivity::class.java)
            .setAction(if (panel == "profile")
                QuickControlActivity.ACTION_PROFILE else QuickControlActivity.ACTION_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, if (panel == "profile") 2201 else 2202,
                intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleUnlockedTap() }
        } else handleUnlockedTap()
    }

    protected open fun handleUnlockedTap() = launchPanel()

    protected fun display(label: String, description: String, active: Boolean) {
        qsTile?.apply {
            this.label = label
            this.subtitle = description
            this.contentDescription = label + ". " + description
            this.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    protected fun refreshSelf() {
        requestListeningState(applicationContext,
            ComponentName(this, this.javaClass))
    }
}

class ProfileQuickTileService : O2PBaseTileService() {
    override val panel: String = "profile"

    override fun updatePresentation() {
        val prefs = QuickProfilePreferences(applicationContext)
        val choice = prefs.selected()
        display("O2P Profile",
            if (choice == null) "Stock preview · tuning unavailable"
            else choice.title.take(34) + " (not applied)",
            active = false
        )
    }

    override fun handleUnlockedTap() {
        val prefs = QuickProfilePreferences(applicationContext)
        if (prefs.cycleOnTap) {
            // The tile cycles saved PREVIEWS only. There is no writer or false success state.
            prefs.cyclePreview()
            updatePresentation()
        } else launchPanel()
    }
}

class SessionQuickTileService : O2PBaseTileService() {
    override val panel: String = "session"

    override fun updatePresentation() {
        val current = GameplayRuntime.state.value
        display(
            "O2P Session",
            when {
                current.running -> "Recording · tap for Stop / Refresh HUD"
                current.starting -> "Starting · tap for controls"
                else -> {
                    val journal = SessionReliabilityJournal(applicationContext)
                    if (journal.lease() != null) "Recording interrupted/recovery pending · tap"
                    else "Stopped · tap to Start"
                }
            },
            active = current.running
        )
    }
}

object QuickTileUpdates {
    fun refresh(context: android.content.Context) {
        runCatching {
            TileService.requestListeningState(context,
                ComponentName(context, SessionQuickTileService::class.java))
            TileService.requestListeningState(context,
                ComponentName(context, ProfileQuickTileService::class.java))
        }
    }
}
