package com.odin2portal.control.autopilot

/**
 * Offline decision logic ONLY. Not connected to hardware, services or the app UI in alpha 0.1.
 * Does not attempt to solve late frames with unrealistic millisecond frequency jumps.
 */
data class FrameWindow(
    val uptimeMs: Long,
    val targetFps: Double,
    val observedFps: Double?,
    val p95IntervalMs: Double?,
    val trusted: Boolean,
    val freshFrames: Int,
    val gpuBusyPercent: Int?,
    val cpuBusyPercent: Int?,
    val thermalGuard: Boolean = false,
    val memoryPressure: Boolean = false
)

enum class ClockDomain { GPU, CPU_PERFORMANCE }

/** All values are from real hardware discovery and in each domain's own units. */
data class FrequencyEnvelope(
    val availableLevels: Map<ClockDomain, List<Long>>,
    val currentCeilings: Map<ClockDomain, Long>,
    val sessionMaximums: Map<ClockDomain, Long>
)

sealed interface PlannerDecision {
    data class Hold(val reason: String) : PlannerDecision
    data class ProposeCeiling(val domain: ClockDomain, val value: Long, val reason: String) : PlannerDecision
    data class RestorePrevious(val domain: ClockDomain, val value: Long, val reason: String) : PlannerDecision
    data class RestoreSession(val reason: String) : PlannerDecision
    data class AcceptProbe(val domain: ClockDomain, val value: Long) : PlannerDecision
}

/** A safe and deterministic controller foundation for later integration. */
class AutoPilotPlanner(
    private val stableWindowsRequired: Int = 3,
    private val validationMs: Long = 10_000L,
    private val cooldownMs: Long = 20_000L
) {
    private data class Probe(
        val domain: ClockDomain,
        val previous: Long,
        val attempted: Long,
        val beganMs: Long
    )
    private var pending: Probe? = null
    private var stableWindows = 0
    private var cooldownUntil = 0L
    private var lastTimestamp = -1L

    fun reset() {
        pending = null
        stableWindows = 0
        cooldownUntil = 0L
        lastTimestamp = -1L
    }

    fun evaluate(s: FrameWindow, envelope: FrequencyEnvelope): PlannerDecision {
        if (s.uptimeMs <= lastTimestamp) {
            return PlannerDecision.Hold("Out-of-order metrics window")
        }
        lastTimestamp = s.uptimeMs
        if (s.thermalGuard) {
            reset()
            return PlannerDecision.RestoreSession("OEM thermal guard active")
        }
        if (!s.trusted || s.observedFps == null || s.p95IntervalMs == null || s.freshFrames < 3) {
            stableWindows = 0
            return PlannerDecision.Hold("Frame timing unsupported or stale")
        }
        if (s.memoryPressure) {
            stableWindows = 0
            return PlannerDecision.Hold("Memory pressure: avoid clock experiments")
        }
        if (s.targetFps !in 10.0..240.0) {
            return PlannerDecision.Hold("Target FPS invalid")
        }
        val frameMs = 1000.0 / s.targetFps
        val regressed = s.observedFps < s.targetFps - 1.0 || s.p95IntervalMs > frameMs * 1.40
        val probe = pending
        if (probe != null) {
            if (regressed) {
                pending = null
                stableWindows = 0
                cooldownUntil = s.uptimeMs + cooldownMs
                return PlannerDecision.RestorePrevious(
                    probe.domain, probe.previous, "Performance regression after lowering"
                )
            }
            if (s.uptimeMs - probe.beganMs < validationMs) {
                return PlannerDecision.Hold("Validating frequency change")
            }
            pending = null
            stableWindows = 0
            cooldownUntil = s.uptimeMs + cooldownMs
            return PlannerDecision.AcceptProbe(probe.domain, probe.attempted)
        }
        if (regressed) {
            stableWindows = 0
            return PlannerDecision.Hold("Target FPS not met; do not lower frequencies")
        }
        if (s.uptimeMs < cooldownUntil) {
            return PlannerDecision.Hold("Clock-change cooldown")
        }
        stableWindows++
        if (stableWindows < stableWindowsRequired) {
            return PlannerDecision.Hold("Collecting stable observations")
        }
        // Heuristic only; the real controller also needs emulator/frame-layer identity and safety vetoes.
        val candidates = buildList {
            if (s.gpuBusyPercent != null && s.gpuBusyPercent < 60) add(ClockDomain.GPU)
            if (s.cpuBusyPercent != null && s.cpuBusyPercent < 55) add(ClockDomain.CPU_PERFORMANCE)
        }
        for (domain in candidates) {
            val current = envelope.currentCeilings[domain] ?: continue
            val maximum = envelope.sessionMaximums[domain] ?: continue
            if (current > maximum) continue // OEM/user envelope must never be exceeded
            val supported = envelope.availableLevels[domain].orEmpty()
                .filter { it > 0 && it <= maximum }.distinct().sorted()
            if (!supported.contains(current)) continue
            val next = supported.lastOrNull { it < current } ?: continue
            pending = Probe(domain, current, next, s.uptimeMs)
            stableWindows = 0
            return PlannerDecision.ProposeCeiling(domain, next, "Stable FPS with measured headroom")
        }
        stableWindows = 0
        return PlannerDecision.Hold("No verified lower hardware OPP or insufficient headroom")
    }
}
