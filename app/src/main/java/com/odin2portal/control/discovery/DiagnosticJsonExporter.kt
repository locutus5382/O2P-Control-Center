package com.odin2portal.control.discovery

import android.content.Context
import android.os.Build
import com.odin2portal.control.monitor.GameplayRuntime
import com.odin2portal.control.monitor.SessionReliabilityJournal
import com.odin2portal.control.domain.DeviceSnapshot
import com.odin2portal.control.domain.SensorSelection
import com.odin2portal.control.hardware.HardwareRead
import com.odin2portal.control.hardware.HostNodeStatus
import com.odin2portal.control.hardware.PServerValidationCache
import org.json.JSONArray
import org.json.JSONObject

/** Manual, redacted, read-only device support bundle; no secrets or private file paths. */
object DiagnosticJsonExporter {
    const val SCHEMA_VERSION = 2
    data class Gate(val name: String, val state: String, val detail: String)

    fun gates(snapshot: DeviceSnapshot, context: Context? = null): List<Gate> {
        val host = PServerValidationCache.last
        val cpuSensors = snapshot.thermals.count {
            it.type.value?.let(SensorSelection::isCpu) == true && it.temperature.isValid
        }
        val gpuSensors = snapshot.thermals.count {
            it.type.value?.let(SensorSelection::isGpu) == true && it.temperature.isValid
        }
        val cpuComplete = snapshot.cpus.size == 3 &&
            snapshot.cpus.all { it.availableKHz.isValid }
        val hostMinMax = host?.authenticated == true && snapshot.cpus.size == 3 &&
            snapshot.cpus.all { cpu ->
                listOf("scaling_min_freq", "scaling_max_freq").all { suffix ->
                    host.nodes.any { item ->
                        item.path.endsWith("/" + cpu.name + "/" + suffix) &&
                            item.status == HostNodeStatus.OBSERVED
                    }
                }
            }
        val base = listOf(
            Gate("CPU frequency tables", if (cpuComplete) "OBSERVED" else "PARTIAL",
                snapshot.cpus.size.toString() + " policies; " +
                    snapshot.cpus.count { it.availableKHz.isValid } + " readable OPP tables"),
            Gate("GPU frequency table", if (snapshot.gpuNodes.any {
                it.path.endsWith("/gpu_available_frequencies") && it.isValid
            }) "OBSERVED" else "PARTIAL", "KGSL frequency-to-power-level mapping not verified"),
            Gate("Named CPU and GPU thermals",
                if (cpuSensors > 0 && gpuSensors > 0) "OBSERVED" else "PARTIAL",
                cpuSensors.toString() + " CPU and " + gpuSensors + " GPU named sensors"),
            Gate("Android thermal API",
                if (snapshot.androidThermal?.status != null) "OBSERVED" else "UNAVAILABLE",
                "Headroom " + (snapshot.androidThermal?.headroomLabel ?: "unavailable")),
            Gate("Fan tachometer (optional)",
                if (snapshot.fanNodes.any {
                    it.path.contains("/fan") && it.path.endsWith("_input") && it.isValid
                }) "OBSERVED" else "OPTIONAL", "Fan is never controlled by O2P"),
            Gate("Authenticated PServer host",
                if (host?.authenticated == true) "VERIFIED" else "BLOCKED",
                when {
                    host == null -> "No explicit host test in this process"
                    host.authenticated -> "UID challenge verified; node read status is separate"
                    else -> "Host identity unavailable: " + privacySafe(host.diagnostic)
                }),
            Gate("CPU effective minimum and maximum",
                if (hostMinMax) "OBSERVED" else "BLOCKED",
                "Requires a working, authenticated read-only host and 3 min/max policy pairs"),
            Gate("Verified OEM Stock restoration", "BLOCKED",
                "Repeated physical-device tests in known AYN modes required"),
            Gate("CPU/GPU write backend", "DISABLED", "No frequency writes exist")
        )
        if (context == null) return base
        val running = GameplayRuntime.state.value.running
        val journal = SessionReliabilityJournal(context)
        return base + Gate("Session / HUD process state",
            if (running) "RECORDING" else if (journal.lease() != null) "RESTART_PENDING"
            else "STOPPED", journal.status())
    }

    fun export(snapshot: DeviceSnapshot, context: Context? = null): String {
        val root = JSONObject()
            .put("schema", "o2p.hardware.capabilities")
            .put("schemaVersion", SCHEMA_VERSION)
            .put("redacted", true)
            .put("readOnly", true)
            .put("capturedAtEpochMs", snapshot.capturedAtUtc)
            .put("device", JSONObject()
                .put("model", snapshot.model)
                .put("androidVersion", snapshot.androidVersion)
                .put("firmwareFingerprint", Build.FINGERPRINT))
            .put("privilege", JSONObject()
                .put("mode", snapshot.accessMode.name)
                .put("probeTransport", snapshot.privilege.transport.name)
                .put("probeAvailable", snapshot.privilege.available)
                .put("reason", privacySafe(snapshot.privilege.reason)))
        val cpus = JSONArray()
        snapshot.cpus.forEach { p ->
            cpus.put(JSONObject().put("policy", p.name)
                .put("relatedCpus", read(p.relatedCpus))
                .put("currentKHz", read(p.currentKHz))
                .put("minimumKHz", read(p.minKHz))
                .put("maximumKHz", read(p.maxKHz))
                .put("hardwareMaximumKHz", read(p.hardwareMaxKHz))
                .put("availableFrequenciesKHz", read(p.availableKHz)))
        }
        root.put("cpuPolicies", cpus)
        root.put("gpuNodes", JSONArray().also { a ->
            snapshot.gpuNodes.forEach { a.put(read(it)) }
        })
        root.put("thermalZones", JSONArray().also { a ->
            snapshot.thermals.forEach { z ->
                a.put(JSONObject().put("index", z.index)
                    .put("type", read(z.type)).put("temperature", read(z.temperature)))
            }
        })
        root.put("fanHwmon", JSONArray().also { a ->
            snapshot.fanNodes.forEach { a.put(read(it)) }
        })
        root.put("androidThermal", JSONObject()
            .put("status", snapshot.androidThermal?.status ?: JSONObject.NULL)
            .put("statusLabel", snapshot.androidThermal?.statusLabel ?: "UNAVAILABLE")
            .put("statusReason", snapshot.androidThermal?.statusReason ?: "Not captured")
            .put("headroom", snapshot.androidThermal?.headroom?.toDouble() ?: JSONObject.NULL)
            .put("headroomReason", snapshot.androidThermal?.headroomReason ?: "Not captured"))
        root.put("memory", read(snapshot.memory))
        root.put("memoryPressure", read(snapshot.pressure))

        val host = PServerValidationCache.last
        root.put("pserverHost", if (host == null) JSONObject()
            .put("status", "NOT_TESTED")
            .put("note", "Run explicit read-only PServer host verification in Studio")
            else JSONObject()
                .put("status", if (host.authenticated) "AUTHENTICATED" else "UNVERIFIED")
                .put("capturedAtEpochMs", host.capturedAt)
                .put("uid", host.verifiedHostUid ?: JSONObject.NULL)
                .put("diagnostic", privacySafe(host.diagnostic))
                .put("nodes", JSONArray().also { a ->
                    host.nodes.forEach { node ->
                        a.put(JSONObject().put("path", node.path)
                            .put("status", node.status.name)
                            .put("value", node.value ?: JSONObject.NULL)
                            .put("linuxErrno", node.linuxErrno ?: JSONObject.NULL)
                            .put("error", node.error?.let(::privacySafe) ?: JSONObject.NULL)
                            .put("source", node.source))
                    }
                }))
        root.put("validationGates", JSONArray().also { a ->
            gates(snapshot, context).forEach { gate ->
                a.put(JSONObject().put("name", gate.name)
                    .put("state", gate.state).put("detail", gate.detail))
            }
        })
        if (context != null) {
            val journal = SessionReliabilityJournal(context)
            val session = GameplayRuntime.state.value
            root.put("sessionDiagnostics", JSONObject()
                .put("runningInCurrentProcess", session.running)
                .put("startingInCurrentProcess", session.starting)
                .put("persistedLeasePresent", journal.lease() != null)
                .put("lifecycleStatus", privacySafe(journal.status()))
                .put("hudWindowStatus", session.hudDiagnostic?.let(::privacySafe)
                    ?: "No HUD diagnostic")
                .put("events", JSONArray().also { a ->
                    journal.events().forEach { a.put(privacySafe(it)) }
                }))
        }
        root.put("privacyNote", "Explicit export only. No device serial, Android ID, accounts, " +
            "app/process inventory, host nonce, private-cache path or arbitrary shell output.")
        root.put("safetyNote",
            "Observed reads do NOT authenticate OEM Stock or authorize underclock writes.")
        return root.toString(2)
    }

    private fun read(r: HardwareRead) = JSONObject()
        .put("path", if (r.path.startsWith("/sys/") || r.path.startsWith("/proc/"))
            r.path else "REDACTED_PATH")
        .put("state", if (r.isValid) "OBSERVED" else "UNAVAILABLE")
        .put("value", r.value?.take(8192) ?: JSONObject.NULL)
        .put("source", r.source.name)
        .put("error", r.error?.let(::privacySafe) ?: JSONObject.NULL)

    private fun privacySafe(raw: String): String = raw
        .replace(Regex("/data/(?:user|data|local)/[^\\s]+"), "[private_path]")
        .replace(Regex("(?i)nonce\\s*[:=]\\s*\\S+"), "nonce=[redacted]")
        .take(300)
}
