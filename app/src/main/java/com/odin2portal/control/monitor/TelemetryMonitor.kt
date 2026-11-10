package com.odin2portal.control.monitor

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.odin2portal.control.discovery.SysfsInventory
import com.odin2portal.control.discovery.AndroidThermalProbe
import com.odin2portal.control.discovery.AndroidThermalEvidence
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
    FAN_RPM("Fan RPM", "hwmon tachometer if available"),
    ANDROID_THERMAL_STATUS("Android thermal state", "PowerManager thermal status"),
    THERMAL_HEADROOM("Thermal headroom", "PowerManager, not a temperature")
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
    private val namedThermals by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SysfsInventory.monitoredThermalZones(::read)
    }
    private val fanTachometers by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SysfsInventory.indices("/sys/class/hwmon", "hwmon", 31).flatMap { id ->
            (1..4).map { "/sys/class/hwmon/hwmon$id/fan" + it + "_input" }
        }.filter { File(it).isFile && File(it).canRead() }
    }
    private var thermalApiCheckedAt = 0L
    private var thermalApi = AndroidThermalProbe.capture(app)

    private fun read(path: String): String? = try {
        File(path).takeIf { it.isFile && it.canRead() }?.bufferedReader()?.use { it.readText().trim().take(8192) }
    } catch (_: Exception) { null }
    private fun temp(accept: (String) -> Boolean): Reading {
        // Named paths are identified once. Only numeric temperatures are
        // sampled during gameplay (not all 128 /type files every second).
        val readings = namedThermals.filter { accept(it.type) }
            .map { it.type to Parsing.temperatureC(read(it.temperaturePath)) }
        val peak = SensorSelection.hottest(readings, accept)
            ?: return Reading(null, "No readable, correctly named thermal sensor")
        return Reading(
            String.format(Locale.US, "%.1f °C", peak.celsius),
            "Highest of ${peak.matchingCount} named sensors · ${peak.sensor}"
        )
    }
    private fun androidThermalReadings(now: Long): Pair<Reading, Reading> {
        if (now - thermalApiCheckedAt >= 5_000L || thermalApiCheckedAt == 0L) {
            thermalApi = AndroidThermalProbe.capture(app)
            thermalApiCheckedAt = now
        }
        val status = Reading(thermalApi.status?.let { thermalApi.statusLabel },
            thermalApi.statusReason)
        val headroom = Reading(thermalApi.headroom?.let {
            String.format(Locale.US, "%.3f", it)
        }, thermalApi.headroomReason)
        return status to headroom
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
        for (path in fanTachometers) {
            val rpm = read(path)?.toIntOrNull()
            if (rpm != null && rpm in 0..50000)
                return Reading("$" + "{rpm} RPM", path)
        }
        return Reading(null, "No readable fan tachometer; PWM is not RPM")
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
        val thermalApiReadings = androidThermalReadings(System.currentTimeMillis())
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
            Metric.FAN_RPM to fanRpm(),
            Metric.ANDROID_THERMAL_STATUS to thermalApiReadings.first,
            Metric.THERMAL_HEADROOM to thermalApiReadings.second
        ))
    }
}

