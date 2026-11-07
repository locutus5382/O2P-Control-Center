package com.odin2portal.control.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-triggered local evidence, not persistent tuning state. */
class StockEvidenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "o2p_stock_evidence_v1", Context.MODE_PRIVATE
    )

    fun load(): List<StockObservation> = runCatching {
        val root = JSONObject(prefs.getString("observations", "{}") ?: "{}")
        if (root.optInt("schema", 1) != 1) return@runCatching emptyList()
        val arr = root.optJSONArray("rows") ?: return@runCatching emptyList()
        val rows = mutableListOf<StockObservation>()
        for (i in 0 until minOf(arr.length(), 24)) {
            val item = arr.optJSONObject(i) ?: continue
            val domains = mutableListOf<StockDomainObservation>()
            val domainArray = item.optJSONArray("domains") ?: continue
            if (domainArray.length() > 16) continue
            for (j in 0 until domainArray.length()) {
                val d = domainArray.optJSONObject(j) ?: continue
                val id = d.optString("id", "").take(48)
                val type = DomainType.entries.firstOrNull { it.name == d.optString("type") }
                    ?: continue
                val ceiling = d.optLong("ceiling", 0L)
                val o = d.optJSONArray("opps") ?: continue
                if (o.length() > 128) continue
                val frequencies = (0 until o.length()).map { o.optLong(it, 0L) }
                if (id.isNotBlank() && ceiling > 0L &&
                    frequencies == frequencies.sorted().distinct() &&
                    ceiling in frequencies) {
                    domains += StockDomainObservation(
                        id, type, ceiling, frequencies,
                        d.optLong("clock", -1L).takeIf { it > 0L },
                        d.optLong("hwMax", -1L).takeIf { it > 0L },
                        d.optLong("runtimeMax", -1L).takeIf { it > 0L },
                        d.optLong("runtimeMin", -1L).takeIf { it > 0L }
                    )
                }
            }
            val timestamp = item.optLong("at", 0L)
            val fingerprint = item.optString("firmware")
            val device = item.optString("device")
            val mode = item.optString("mode")
            if (timestamp > 0L && fingerprint.isNotBlank() && device.isNotBlank() &&
                mode.isNotBlank() && domains.isNotEmpty()) {
                rows += StockObservation(timestamp, fingerprint, device, mode.take(48),
                    domains, item.optString("access").take(160))
            }
        }
        rows.sortedBy { it.capturedAt }.takeLast(12)
    }.getOrDefault(emptyList())

    fun add(value: StockObservation): Boolean {
        if (value.domains.isEmpty() || value.oemModeLabel.isBlank()) return false
        val old = load().filterNot {
            it.capturedAt == value.capturedAt &&
                it.firmwareFingerprint == value.firmwareFingerprint &&
                it.oemModeLabel == value.oemModeLabel
        }
        return persist((old + value).sortedBy { it.capturedAt }.takeLast(12))
    }

    fun clearMode(firmwareFingerprint: String, mode: String): Boolean =
        persist(load().filterNot {
            it.firmwareFingerprint == firmwareFingerprint && it.oemModeLabel == mode
        })

    private fun persist(rows: List<StockObservation>): Boolean = runCatching {
        val arr = JSONArray()
        rows.forEach { row ->
            val domainRows = JSONArray()
            row.domains.forEach { domain ->
                val opps = JSONArray()
                domain.supported.forEach { opps.put(it) }
                domainRows.put(JSONObject()
                    .put("id", domain.id)
                    .put("type", domain.type.name)
                    .put("ceiling", domain.observedCeiling)
                    .put("clock", domain.currentClock ?: -1L)
                    .put("hwMax", domain.maxHardwareReported ?: -1L)
                    .put("runtimeMax", domain.reportedRuntimeCeiling ?: -1L)
                    .put("runtimeMin", domain.reportedRuntimeMinimum ?: -1L)
                    .put("opps", opps))
            }
            arr.put(JSONObject()
                .put("at", row.capturedAt)
                .put("firmware", row.firmwareFingerprint)
                .put("device", row.device)
                .put("mode", row.oemModeLabel)
                .put("access", row.accessNote)
                .put("domains", domainRows))
        }
        prefs.edit().putString("observations", JSONObject()
            .put("schema", 1).put("rows", arr).toString()).commit()
    }.getOrDefault(false)
}
