package com.odin2portal.control

import com.odin2portal.control.monitor.SessionLease
import com.odin2portal.control.monitor.SessionResumePolicy
import org.junit.Assert.*
import org.junit.Test

class SessionResumePolicyTest {
    private fun lease(
        heartbeat: Long = 115000L,
        stopRequested: Boolean = false
    ) = SessionLease(
        sessionId = 42L,
        startedAt = 100000L,
        heartbeatAt = heartbeat,
        sampleMs = 2000L,
        historyMs = 10000L,
        manualLabel = "Mario Kart",
        stopRequested = stopRequested,
        processId = 999,
        lastEvent = "SERVICE_STARTED"
    )

    @Test fun recentUserSessionMayResumeOnlyWhenSqliteStillRecording() {
        assertTrue(SessionResumePolicy.shouldResume(lease(), 120000L, true))
        assertFalse(SessionResumePolicy.shouldResume(lease(), 120000L, false))
    }

    @Test fun neverRestartAfterExplicitStopOrMissingLease() {
        assertFalse(SessionResumePolicy.shouldResume(
            lease(stopRequested = true), 120000L, true))
        assertFalse(SessionResumePolicy.shouldResume(null, 120000L, true))
    }

    @Test fun neverRestartAfterStaleHeartbeatOrFutureClock() {
        assertFalse(SessionResumePolicy.shouldResume(lease(), 245001L, true))
        assertFalse(SessionResumePolicy.shouldResume(
            lease(heartbeat = 130000L), 120000L, true))
    }

    @Test fun refusesInvalidSessionId() {
        assertFalse(SessionResumePolicy.shouldResume(
            lease().copy(sessionId = -1L), 120000L, true))
    }
}
