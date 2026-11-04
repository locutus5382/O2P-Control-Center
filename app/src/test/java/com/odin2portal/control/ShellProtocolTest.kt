package com.odin2portal.control

import com.odin2portal.control.hardware.KernelPath
import com.odin2portal.control.hardware.ShellProtocol
import org.junit.Assert.*
import org.junit.Test

class ShellProtocolTest {
    @Test fun validPathsAreAccepted() {
        assertNotNull(KernelPath.parse("/sys/class/kgsl/kgsl-3d0/gpubusy"))
        assertNotNull(KernelPath.parse("/proc/pressure/memory"))
    }
    @Test fun shellInjectionsAreRejected() {
        assertNull(KernelPath.parse("/sys/a'; reboot"))
        assertNull(KernelPath.parse("/sys/../data/local/tmp"))
        assertNull(KernelPath.parse("/sys/a//b"))
    }
    @Test fun emptyReplyNeverClaimsSuccess() {
        assertFalse(ShellProtocol.parse("").verified)
        assertFalse(ShellProtocol.parse("OK").verified)
        assertFalse(ShellProtocol.parse(null).verified)
    }
    @Test fun validReplyHasExitStatus() {
        val reply = ShellProtocol.parse("2400000\n__O2P_CC_RC_V1__=0\n")
        assertTrue(reply.verified)
        assertEquals("2400000", reply.value)
    }
    @Test fun unsuccessfulCommandIsNotData() {
        val reply = ShellProtocol.parse("permission denied\n__O2P_CC_RC_V1__=1\n")
        assertFalse(reply.verified)
        assertNull(reply.value)
        assertEquals(1, reply.code)
    }
}
