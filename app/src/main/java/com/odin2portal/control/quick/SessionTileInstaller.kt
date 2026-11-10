package com.odin2portal.control.quick

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.os.Build
import android.graphics.drawable.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.odin2portal.control.R
import com.odin2portal.control.ui.AppearanceSettings
import com.odin2portal.control.ui.O2PAction

/** Adds only the single user-initiated recording Start/Stop tile. */
object QuickTileInstaller {
    fun request(activity: Activity, result: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            result("Open Android Quick Settings → Edit and add Start/Stop session.")
            return
        }
        val manager = activity.getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            result("Open Android Quick Settings → Edit to add Start/Stop session.")
            return
        }
        manager.requestAddTileService(
            ComponentName(activity, SessionQuickTileService::class.java),
            "O2P Session",
            Icon.createWithResource(activity, R.drawable.ic_qs_session),
            activity.mainExecutor
        ) { response ->
            result(when (response) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                    "O2P Start/Stop session tile added."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    "O2P Session tile is already installed."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                    "Tile not added; use Quick Settings → Edit."
                else -> "Android could not add the tile; use Quick Settings → Edit."
            })
        }
    }
}

@Composable
fun QuickTileInstallActions(appearance: AppearanceSettings) {
    val activity = LocalContext.current as? Activity
    var feedback by remember { mutableStateOf<String?>(null) }
    O2PAction("Add Start/Stop Session tile", appearance, Modifier.fillMaxWidth(),
        enabled = activity != null) {
        activity?.let { QuickTileInstaller.request(it) { feedback = it } }
    }
    feedback?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = appearance.colors.muted)
    }
}
