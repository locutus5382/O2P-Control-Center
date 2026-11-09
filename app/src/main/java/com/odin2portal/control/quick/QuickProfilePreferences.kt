package com.odin2portal.control.quick

import android.content.Context
import android.os.Build
import com.odin2portal.control.studio.SavedStudioProfile
import com.odin2portal.control.studio.StudioCatalogue
import com.odin2portal.control.studio.StudioPreset
import org.json.JSONArray
import org.json.JSONObject

/** Saved QUICK ACCESS PREVIEW only. Never represents applied hardware state. */
data class QuickProfileChoice(val id: String, val title: String)

class QuickProfilePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "o2p_quick_profile_preview_v1", Context.MODE_PRIVATE
    )
    val cycleOnTap: Boolean get() = prefs.getBoolean("cycle_on_tap", false)
    fun setCycleOnTap(value: Boolean) {
        prefs.edit().putBoolean("cycle_on_tap", value).apply()
    }

    fun options(catalogue: StudioCatalogue, saved: List<SavedStudioProfile>): List<QuickProfileChoice> {
        val items = StudioPreset.entries.map {
            QuickProfileChoice("preset:" + it.name,
                if (it == StudioPreset.STOCK) "Stock (preview)" else it.name.lowercase()
                    .replaceFirstChar(Char::uppercaseChar) + " · preview")
        } + saved.filter { it.originalPreset == null }.map {
            QuickProfileChoice(it.id, it.name.take(48) + " · custom preview")
        }
        // The cache is only used for cycling preview names; it cannot feed a writer.
        val root = JSONObject().put("fingerprint", catalogue.fingerprint)
        val list = JSONArray()
        items.forEach { list.put(JSONObject().put("id", it.id).put("title", it.title)) }
        root.put("choices", list)
        prefs.edit().putString("available_choices", root.toString()).apply()
        return items
    }

    fun cachedOptions(): List<QuickProfileChoice> = runCatching {
        val root = JSONObject(prefs.getString("available_choices", "{}") ?: "{}")
        if (root.optString("fingerprint") != Build.FINGERPRINT) return@runCatching emptyList()
        val items = root.optJSONArray("choices") ?: return@runCatching emptyList()
        (0 until minOf(items.length(), 64)).mapNotNull {
            val item = items.optJSONObject(it) ?: return@mapNotNull null
            val key = item.optString("id")
            val title = item.optString("title")
            if (key.isBlank() || title.isBlank()) null
            else QuickProfileChoice(key.take(96), title.take(72))
        }
    }.getOrDefault(emptyList())

    fun selected(): QuickProfileChoice? {
        if (prefs.getString("fingerprint", null) != Build.FINGERPRINT) return null
        val id = prefs.getString("selected_id", "") ?: ""
        val title = prefs.getString("selected_title", "") ?: ""
        if (id.isEmpty() || title.isEmpty()) return null
        return QuickProfileChoice(id, title)
    }

    fun choose(choice: QuickProfileChoice) {
        prefs.edit()
            .putString("fingerprint", Build.FINGERPRINT)
            .putString("selected_id", choice.id.take(96))
            .putString("selected_title", choice.title.take(72))
            .apply()
    }

    fun cyclePreview(): QuickProfileChoice? {
        val available = cachedOptions().ifEmpty {
            StudioPreset.entries.map {
                QuickProfileChoice("preset:" + it.name, it.name + " · preview")
            }
        }
        if (available.isEmpty()) return null
        val currentId = selected()?.id
        val index = available.indexOfFirst { it.id == currentId }
        val next = available[(index + 1).mod(available.size)]
        choose(next)
        return next
    }
}
