package com.odin2portal.control.hardware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Strict read-only PServer capability probe.
 *
 * Old v0.6 builds sent a legacy stdout/exit-marker shell request, but the
 * Portal's actual firmware did not return the expected marker. We now follow
 * ClusterTune's current architecture: probe Binder liveness without sending
 * a command; later launch a dedicated audited host and verify its identity.
 *
 * Stage 1 does NOT launch that host or use PServer to read any kernel node.
 */
class PServerReader(
    private val transport: PServerHostTransport = PServerHostTransport()
) : ReadTransport {
    override suspend fun probe(): PrivilegeStatus = withContext(Dispatchers.IO) {
        val visible = transport.serviceVisible
        PrivilegeStatus(
            transport = ReadSource.PSERVER,
            available = false, // no authenticated privileged host yet
            reportedUid = null, // do not invent a UID from Binder liveness
            reason = if (visible) {
                "PServerBinder visible and alive. ClusterTune-style host transport detected, " +
                    "but host authentication and privileged readback are NOT implemented. " +
                    "No shell command, host launch, root operation, or hardware write executed."
            } else {
                "PServerBinder absent or inaccessible. No privileged host available. " +
                    "Direct telemetry remains usable without root."
            }
        )
    }

    override suspend fun read(path: KernelPath): HardwareRead =
        HardwareRead.unavailable(
            path.value,
            "Read-only privileged PServer host not implemented; no legacy command execution. " +
                "Use verified Direct readings or wait for an authenticated host."
        )
}
