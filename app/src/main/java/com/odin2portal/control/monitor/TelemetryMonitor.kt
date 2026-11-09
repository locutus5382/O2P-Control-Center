package com.odin2portal.control.monitor

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.odin2portal.control.domain.Parsing
import com.odin2portal.control.domain.SensorSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Read-only on-demand telemetry. No root, daemon, kernel writes, or boot receiver. */
enum class Metric(val label: String, val source: String) {
    FPS("Presented FPS", "Needs validated per-layer SurfaceFlinger access"),
    SOC_TEMP("CPU / SoC temperature", "Named thermal zone"),
    GPU_TEMP("GPU temperature", "Named GPU thermal zone"),
    BATTERY_TEMP("Battery temperature", "Android battery broadcast"),
    BATTERY_LEVEL("Battery level", "Android battery broadcast"),
    CPU_LOAD("CPU load", "/proc/stat, sampled delta"),
    CPU_PEAK_CORE("Busiest CPU core", "/proc/stat per-core delta; highest sampled utilization"),
    CPU_CLOCKS("CPU clocks", "cpufreq policies"),
    GPU_CLOCK("GPU clock", "KGSL devfreq"),
    GPU_LOAD("GPU load", "KGSL utilization"),
    RAM("System RAM", "/proc/meminfo"),
    FAN_RPM("Fan RPM", "hwmon tachometer if available")
}
data class Reading(val value: String?, val detail: String = "") {
    val display: String get() = value ?: "Unavailable"
}
data class MonitorSample(val timestamp: Long, val readings: Map<Metric, Reading>) {
    fun get(metric: Metric): Reading = readings[metric] ?: Reading(null)
}
object CpuStats {
    data class Times(val idle: Long, val total: Long)
    fun parse(raw: String?): Times? {
        val fields = raw?.trim()?.split(Regex("\\s+")) ?: return null
        if (fields.firstOrNull() != "cpu" || fields.size < 5) return null
        val v = fields.drop(1).map { it.toLongOrNull() ?: return null }
        if (v.any { it < 0L }) return null
        return Times(v[3] + v.getOrElse(4) { 0L }, v.sum())
    }
    fun busy(before: Times?, after: Times?): Int? {
        if (before == null || after == null) return null
        val dTotal = after.total - before.total
        val dIdle = after.idle - before.idle
        if (dTotal <= 0L || dIdle < 0L || dIdle > dTotal) return null
        return ((dTotal - dIdle) * 100.0 / dTotal).roundToInt().coerceIn(0, 100)
    }
}

class DirectMonitor(private val app: Application) {
    private var before: CpuStats.Times? = null
    private val priorCores = mutableMapOf<String, CpuStats.Times>()
    private fun read(path: String): String? = try {
        File(path).takeIf { it.isFile && it.canRead() }?.bufferedReader()?.use { it.readText().trim().take(8192) }
    } catch (_: Exception) { null }
    private fun temp(accept: (String) -> Boolean): Reading {
        val zones = File("/sys/class/thermal").listFiles()
            ?.filter { it.name.matches(Regex("thermal_zone\\d+")) }?.take(128).orEmpty()
        val readings = zones.mapNotNull { zone ->
            val name = read(zone.path + "/type") ?: return@mapNotNull null
            if (!accept(name)) return@mapNotNull null
            name to Parsing.temperatureC(read(zone.path + "/temp"))
        }
        val peak = SensorSelection.hottest(readings, accept)
            ?: return Reading(null, "No readable, correctly named thermal sensor")
        return Reading(
            String.format(Locale.US, "%.1f °C", peak.celsius),
            "Highest of ${peak.matchingCount} named sensors · ${peak.sensor}"
        )
    }
    private fun cpuClocks(): Reading {
        val policies = File("/sys/devices/system/cpu/cpufreq").listFiles()
            ?.filter { it.name.matches(Regex("policy\\d+")) }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            ?.take(12).orEmpty()
        val observed = policies.mapNotNull { policy ->
            val khz = read(policy.path + "/scaling_cur_freq")?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            policy.name + ": " + String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
        }
        return if (observed.isEmpty()) Reading(null, "Frequency policies not accessible")
        else Reading(observed.joinToString(" · "), "Reported scaling_cur_freq")
    }
    private fun gpuClock(): Reading {
        val root = "/sys/class/kgsl/kgsl-3d0/"
        for (suffix in listOf("devfreq/cur_freq", "gpuclk")) {
            val value = read(root + suffix)?.toLongOrNull()?.takeIf { it > 0 } ?: continue
            val mhz = if (value > 2_000_000) value / 1_000_000.0 else value / 1000.0
            return Reading(String.format(Locale.US, "%.0f MHz", mhz), suffix)
        }
        return Reading(null, "GPU clock not accessible")
    }
    private fun gpuLoad(): Reading {
        val root = "/sys/class/kgsl/kgsl-3d0/"
        read(root + "gpu_busy_percentage")?.trim()?.substringBefore(" ")?.trim()
            ?.removeSuffix("%")?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }?.let {
            return Reading(it.toString() + "%", "gpu_busy_percentage")
        }
        val ticks = read(root + "gpubusy")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ticks.size >= 2 && ticks[1] > 0 && ticks[0] in 0..ticks[1])
            return Reading((ticks[0] * 100.0 / ticks[1]).roundToInt().toString() + "%", "gpubusy busy/total ticks")
        return Reading(null, "GPU utilization not accessible")
    }
    private fun fanRpm(): Reading {
        for (hwmon in File("/sys/class/hwmon").listFiles()?.take(32).orEmpty()) for (i in 1..4) {
            val rpm = read(hwmon.path + "/fan" + i + "_input")?.toIntOrNull()
            if (rpm != null && rpm in 0..50000) return Reading(rpm.toString() + " RPM", hwmon.name + "/fan" + i + "_input")
        }
        return Reading(null, "No tachometer; PWM is not RPM")
    }
    suspend fun sample(): MonitorSample = withContext(Dispatchers.IO) {
        val battery: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val batteryTemp = raw?.takeIf { it in -200..1500 }?.let {
            Reading(String.format(Locale.US, "%.1f °C", it / 10.0), "Android battery broadcast")
        } ?: Reading(null, "Temperature unavailable")
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryLevel = if (scale > 0 && level in 0..scale)
            Reading("${(level * 100.0 / scale).roundToInt()}%", "Android battery broadcast")
        else Reading(null, "Battery level unavailable")
        val procStat = read("/proc/stat")
        val now = CpuStats.parse(procStat?.lineSequence()?.firstOrNull())
        val cpu = CpuStats.busy(before, now)
        before = now
        // Aggregate CPU load can conceal a saturated emulation thread.
        // Measure all eight reported per-core counters from the same /proc/stat
        // read; do not add extra polling syscalls or assume a single process.
        val coreLoads = procStat?.lineSequence()?.mapNotNull { raw ->
            val fields = raw.trim().split(Regex("\\s+"))
            val name = fields.firstOrNull() ?: return@mapNotNull null
            if (!name.matches(Regex("cpu\\d+"))) return@mapNotNull null
            val parsed = CpuStats.parse("cpu " + fields.drop(1).joinToString(" "))
                ?: return@mapNotNull null
            val load = CpuStats.busy(priorCores[name], parsed)
            priorCores[name] = parsed
            if (load == null) null else name to load
        }?.toList().orEmpty()
        val busiest = coreLoads.maxByOrNull { it.second }
        val mem = read("/proc/meminfo")
        val total = Parsing.memoryKb(mem, "MemTotal")
        val available = Parsing.memoryKb(mem, "MemAvailable")
        val memory = if (total != null && available != null && available in 0..total && total > 0)
            Reading(
                String.format(Locale.US, "%.2f GiB", (total - available) / 1048576.0),
                String.format(Locale.US, "System RAM estimate · %.2f GiB total", total / 1048576.0)
            )
        else Reading(null, "System RAM not accessible")
        MonitorSample(System.currentTimeMillis(), mapOf(
            Metric.FPS to Reading(null, "FPS backend needs on-device layer validation"),
            Metric.SOC_TEMP to temp(SensorSelection::isCpu),
            Metric.GPU_TEMP to temp(SensorSelection::isGpu),
            Metric.BATTERY_TEMP to batteryTemp,
            Metric.BATTERY_LEVEL to batteryLevel,
            Metric.CPU_LOAD to Reading(cpu?.toString()?.plus("%"), "First sample establishes a baseline"),
            Metric.CPU_PEAK_CORE to Reading(busiest?.second?.toString()?.plus("%"),
                busiest?.first?.let { "$it busiest of " + coreLoads.size + " cores" }
                    ?: "Core counters need two samples"),
            Metric.CPU_CLOCKS to cpuClocks(),
            Metric.GPU_CLOCK to gpuClock(),
            Metric.GPU_LOAD to gpuLoad(),
            Metric.RAM to memory,
            Metric.FAN_RPM to fanRpm()
        ))
    }
}

data class MonitorState(
    val running: Boolean = false,
    val intervalMs: Long = 2000L,
    val selected: Set<Metric> = setOf(Metric.FPS, Metric.SOC_TEMP, Metric.GPU_TEMP, Metric.BATTERY_TEMP, Metric.CPU_LOAD, Metric.CPU_CLOCKS, Metric.GPU_LOAD, Metric.RAM),
    val sample: MonitorSample? = null,
    val recording: Boolean = false,
    val recordingIntervalMs: Long = RecordingIntervals.defaultMs,
    val sessionLabel: String = "",
    val capturedSamples: Int = 0,
    val pendingSamples: Int = 0,
    val droppedRows: Long = 0,
    val recentIntervals: List<RecordedInterval> = emptyList(),
    val error: String? = null
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("monitor", 0)
    private val collector = DirectMonitor(app)
    private val data = MutableStateFlow(MonitorState(
        selected = Metric.entries.filter { prefs.getBoolean("show_" + it.name, it in MonitorState().selected) }.toSet(),
        intervalMs = prefs.getLong("interval", 2000L).coerceIn(1000L, 5000L),
        recordingIntervalMs = prefs.getLong("history_interval", RecordingIntervals.defaultMs)
            .takeIf(RecordingIntervals::valid) ?: RecordingIntervals.defaultMs,
        sessionLabel = prefs.getString("session_label", "")?.take(64) ?: ""
    ))
    val state = data.asStateFlow()
    private var job: Job? = null
    // 600 aggregated intervals maximum, not 600 raw polling events.
    // History remains in RAM until a new recording begins or ViewModel is destroyed.
    private val history = SessionHistory(data.value.recordingIntervalMs)

    fun setSessionLabel(label: String) {
        if (data.value.recording) return
        val safe = label.take(64).replace("\\n", " ").replace("\\r", " ")
        data.value = data.value.copy(sessionLabel = safe)
        prefs.edit().putString("session_label", safe).apply()
    }

    fun setRecordingInterval(ms: Long) {
        if (!RecordingIntervals.valid(ms)) return
        history.interval(ms) // closes any partial window before changing interval
        data.value = data.value.copy(
            recordingIntervalMs = ms,
            capturedSamples = history.rowCount,
            pendingSamples = history.pendingSampleCount,
            droppedRows = history.droppedRows,
            recentIntervals = history.snapshot().takeLast(3)
        )
        prefs.edit().putLong("history_interval", ms).apply()
    }

    fun setRecording(enabled: Boolean) {
        if (enabled && !data.value.running) return
        if (enabled) {
            history.reset()
            data.value = data.value.copy(recording = true, capturedSamples = 0, pendingSamples = 0, droppedRows = 0, recentIntervals = emptyList())
        } else {
            history.closePartial()
            data.value = data.value.copy(
                recording = false,
                capturedSamples = history.rowCount,
                pendingSamples = 0,
                droppedRows = history.droppedRows,
                recentIntervals = history.snapshot().takeLast(3)
            )
        }
    }

    /** Explicit export only: users choose a destination through Android's file picker. */
    fun exportCsv(): String? {
        if (data.value.recording) history.closePartial()
        data.value = data.value.copy(
            capturedSamples = history.rowCount,
            pendingSamples = history.pendingSampleCount,
            droppedRows = history.droppedRows,
            recentIntervals = history.snapshot().takeLast(3)
        )
        return history.snapshot().takeIf { it.isNotEmpty() }?.let(MonitorCsv::formatHistory)
    }

    fun enable(metric: Metric, enabled: Boolean) {
        val old = data.value.selected
        data.value = data.value.copy(selected = if (enabled) old + metric else old - metric)
        prefs.edit().putBoolean("show_" + metric.name, enabled).apply()
    }
    fun interval(ms: Long) {
        if (ms !in listOf(1000L, 2000L, 5000L)) return
        data.value = data.value.copy(intervalMs = ms)
        prefs.edit().putLong("interval", ms).apply()
    }
    fun start() {
        if (job?.isActive == true) return
        data.value = data.value.copy(running = true, error = null)
        job = viewModelScope.launch {
            while (isActive) {
                try {
                    val snapshot = collector.sample()
                    if (data.value.recording) {
                        history.offer(snapshot, data.value.sessionLabel)
                    }
                    data.value = data.value.copy(
                        sample = snapshot,
                        capturedSamples = history.rowCount,
                        pendingSamples = history.pendingSampleCount,
                        droppedRows = history.droppedRows,
                        recentIntervals = history.snapshot().takeLast(3),
                        error = null
                    )
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { data.value = data.value.copy(error = e.message ?: "Telemetry read failed") }
                delay(data.value.intervalMs)
            }
        }
    }
    fun stop() {
        job?.cancel()
        job = null
        if (data.value.recording) history.closePartial()
        data.value = data.value.copy(
            running = false,
            recording = false,
            capturedSamples = history.rowCount,
            pendingSamples = 0,
            droppedRows = history.droppedRows,
            recentIntervals = history.snapshot().takeLast(3)
        )
    }
    override fun onCleared() { stop(); super.onCleared() }
}
