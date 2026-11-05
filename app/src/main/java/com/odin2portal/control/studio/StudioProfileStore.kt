package com.odin2portal.control.studio

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

data class SavedStudioProfile(
    val id: String,
    val name: String,
    val firmware: String,
    val device: String,
    val values: Map<String, Long>,
    val originalPreset: StudioPreset? = null
)

/**
 * User's desired limits, NOT applied kernel state. A saved profile is never
 * interpreted as active. Rejects incompatible firmware, policy or OPP changes.
 */
class StudioProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "o2p_readonly_studio_v1", Context.MODE_PRIVATE
    )
    private val schema = 1

    fun load(catalogue: StudioCatalogue): List<SavedStudioProfile> {
        val raw = prefs.getString("profiles_json", null) ?: return emptyList()
        return runCatching {
            val doc = JSONObject(raw)
            if (doc.optInt("schema", -1) != schema) return@runCatching emptyList()
            val rows = doc.optJSONArray("profiles") ?: return@runCatching emptyList()
            val profiles = mutableListOf<SavedStudioProfile>()
            for (i in 0 until rows.length()) {
                val p = rows.optJSONObject(i) ?: continue
                if (p.optString("firmware") != catalogue.fingerprint ||
                    p.optString("device") != catalogue.device) continue
                val name = p.optString("name").trim().take(48)
                if (name.isEmpty()) continue
                val obj = p.optJSONObject("values") ?: continue
                val values = mutableMapOf<String, Long>()
                for (key in obj.keys()) {
                    val value = obj.optLong(key, -1L)
                    if (value > 0L) values[key] = value
                }
                if (ProfileValidator.error(catalogue, values) != null) continue
                val preset = StudioPreset.entries.firstOrNull {
                    it.name == p.optString("originalPreset") && it != StudioPreset.STOCK
                }
                val id = p.optString("id")
                if (id.isNotBlank()) {
                    profiles += SavedStudioProfile(id, name, catalogue.fingerprint,
                        catalogue.device, values, preset)
                }
            }
            profiles.distinctBy { it.id }.take(60)
        }.getOrDefault(emptyList())
    }

    fun save(catalogue: StudioCatalogue, profile: SavedStudioProfile): Boolean {
        if (profile.originalPreset == StudioPreset.STOCK ||
            profile.firmware != catalogue.fingerprint ||
            profile.device != catalogue.device ||
            profile.name.isBlank() || profile.name.length > 48 ||
            ProfileValidator.error(catalogue, profile.values) != null) return false
        val existing = load(catalogue).toMutableList()
        if (existing.size >= 60 && existing.none { it.id == profile.id }) return false
        existing.removeAll { it.id == profile.id }
        existing += profile
        return persist(existing)
    }

    fun createCustom(catalogue: StudioCatalogue, name: String,
                     values: Map<String, Long>): SavedStudioProfile? {
        val safeName = name.trim().take(48)
        if (safeName.isEmpty()) return null
        val result = SavedStudioProfile(
            UUID.randomUUID().toString(), safeName, catalogue.fingerprint,
            catalogue.device, values, null
        )
        return result.takeIf { save(catalogue, it) }
    }

    fun delete(catalogue: StudioCatalogue, id: String): Boolean =
        persist(load(catalogue).filterNot { it.id == id })

    fun presetOverride(catalogue: StudioCatalogue, preset: StudioPreset) =
        load(catalogue).firstOrNull { it.originalPreset == preset }

    fun savePreset(catalogue: StudioCatalogue, preset: StudioPreset,
                   values: Map<String, Long>): Boolean {
        if (preset == StudioPreset.STOCK) return false
        val current = presetOverride(catalogue, preset)
        return save(catalogue, SavedStudioProfile(
            current?.id ?: "preset:" + preset.name, preset.name + " (edited)",
            catalogue.fingerprint, catalogue.device, values, preset
        ))
    }

    fun resetPreset(catalogue: StudioCatalogue, preset: StudioPreset): Boolean {
        if (preset == StudioPreset.STOCK) return false
        val existing = load(catalogue)
        return persist(existing.filterNot { it.originalPreset == preset })
    }

    private fun persist(list: List<SavedStudioProfile>): Boolean = runCatching {
        val root = JSONObject().put("schema", schema)
        val array = JSONArray()
        for (profile in list) {
            val o = JSONObject().put("id", profile.id)
                .put("name", profile.name)
                .put("device", profile.device)
                .put("firmware", profile.firmware)
                .put("originalPreset", profile.originalPreset?.name ?: "")
            val values = JSONObject()
            profile.values.forEach { (id, mhz) -> values.put(id, mhz) }
            o.put("values", values)
            array.put(o)
        }
        root.put("profiles", array)
        prefs.edit().putString("profiles_json", root.toString()).commit()
    }.getOrDefault(false)
}
