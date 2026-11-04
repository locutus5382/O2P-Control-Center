package com.odin2portal.control

import com.odin2portal.control.autopilot.*
import org.junit.Assert.*
import org.junit.Test

class AutoPilotPlannerTest {
    private val env = FrequencyEnvelope(
        availableLevels = mapOf(ClockDomain.GPU to listOf(400L, 500L, 600L)),
        currentCeilings = mapOf(ClockDomain.GPU to 600L),
        sessionMaximums = mapOf(ClockDomain.GPU to 600L)
    )
    private fun frame(t: Long, fps: Double = 30.0, trusted: Boolean = true) = FrameWindow(
        uptimeMs = t, targetFps = 30.0, observedFps = fps, p95IntervalMs = 33.3,
        trusted = trusted, freshFrames = 30, gpuBusyPercent = 40, cpuBusyPercent = 40
    )
    @Test fun lowersOnlyAfterSustainedStableWindows() {
        val p = AutoPilotPlanner()
        assertTrue(p.evaluate(frame(1000), env) is PlannerDecision.Hold)
        assertTrue(p.evaluate(frame(2000), env) is PlannerDecision.Hold)
        assertEquals(PlannerDecision.ProposeCeiling(ClockDomain.GPU, 500L,
            "Stable FPS with measured headroom"), p.evaluate(frame(3000), env))
    }
    @Test fun regressionRestoresPreviousCeiling() {
        val p = AutoPilotPlanner()
        p.evaluate(frame(1000), env); p.evaluate(frame(2000), env); p.evaluate(frame(3000), env)
        assertEquals(PlannerDecision.RestorePrevious(ClockDomain.GPU, 600L,
            "Performance regression after lowering"), p.evaluate(frame(4000, fps = 26.0), env))
    }
    @Test fun neverProbesWithoutTrustedFrames() {
        val p = AutoPilotPlanner()
        repeat(5) { p.evaluate(frame((it + 1) * 1000L, trusted = false), env) }
        assertTrue(p.evaluate(frame(6000), env) is PlannerDecision.Hold)
    }
    @Test fun thermalGuardRestoresSession() {
        val p = AutoPilotPlanner()
        assertTrue(p.evaluate(frame(1000).copy(thermalGuard = true), env)
            is PlannerDecision.RestoreSession)
    }
}
