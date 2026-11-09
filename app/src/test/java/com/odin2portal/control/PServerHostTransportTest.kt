package com.odin2portal.control

import com.odin2portal.control.hardware.KernelPath
import com.odin2portal.control.hardware.PServerHostConnection
import com.odin2portal.control.hardware.PServerHostTransport
import org.junit.Assert.*
import org.junit.Test

/** All transactions use injected fakes; no real Binder or privileged operation occurs. */
class PServerHostTransportTest {
    private class FakeConnection : PServerHostConnection {
        override var alive: Boolean = true
        var launched = 0
        var accepted = true
        override fun launch(command: String): Boolean {
            launched++
            return accepted
        }
    }

    @Test fun livenessProbeNeverLaunchesAnyCommand() {
        val fake = FakeConnection()
        val transport = PServerHostTransport { fake }
        assertTrue(transport.serviceVisible)
        assertEquals(0, fake.launched)
        fake.alive = false
        assertFalse(transport.serviceVisible)
        assertEquals(0, fake.launched)
    }

    @Test fun absentServiceReturnsUnavailable() {
        val transport = PServerHostTransport { null }
        assertFalse(transport.serviceVisible)
        assertTrue(transport.launchHost("test-only").isFailure)
    }

    @Test fun futureHostLauncherEnforcesInputLengthAndNulGuard() {
        val fake = FakeConnection()
        val transport = PServerHostTransport { fake }
        assertTrue(transport.launchHost("").isFailure)
        assertTrue(transport.launchHost("valid\u0000invalid").isFailure)
        assertTrue(transport.launchHost("é".repeat(128)).isFailure) // 256 bytes
        assertEquals(0, fake.launched)
        assertTrue(transport.launchHost("trusted-host-fixture").isSuccess)
        assertEquals(1, fake.launched)
    }

    @Test fun transactionRejectionIsNotRetried() {
        val fake = FakeConnection().apply { accepted = false }
        val transport = PServerHostTransport { fake }
        assertTrue(transport.launchHost("trusted-host-fixture").isFailure)
        assertEquals(1, fake.launched)
    }

    @Test fun kernelPathValidationRemainsReadOnlyAndRejectsInjection() {
        assertNotNull(KernelPath.parse("/sys/class/kgsl/kgsl-3d0/gpubusy"))
        assertNotNull(KernelPath.parse("/proc/pressure/memory"))
        assertNull(KernelPath.parse("/sys/a'; reboot"))
        assertNull(KernelPath.parse("/sys/../data/local/tmp"))
        assertNull(KernelPath.parse("/sys/a//b"))
    }
}
