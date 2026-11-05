package com.odin2portal.control.hardware

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only firmware compatibility probe. PServerBinder is a private OEM API,
 * NOT guaranteed on Portal and NOT evidence of permission to change hardware.
 *
 * Deliberately does not fall back to su, request writes, start a privileged
 * host, or retry a possibly accepted transaction. A separate audited host
 * implementation is necessary before O2P can ever change clock ceilings.
 */
class PServerReader : ReadTransport {
    @SuppressLint("PrivateApi")
    private fun binder(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        // checkService does not wait for a service to appear, unlike getService.
        val method = serviceManager.getMethod("checkService", String::class.java)
        method.invoke(null, "PServerBinder") as? IBinder
    } catch (_: Exception) {
        null
    }

    private fun execute(command: String): ShellProtocol.Parsed {
        val remote = binder() ?: return ShellProtocol.Parsed(null, null, "PServerBinder absent or inaccessible")
        if (!remote.isBinderAlive) return ShellProtocol.Parsed(null, null, "PServerBinder is not alive")
        val input = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            // Legacy output-capture protocol tested ONLY with a read-only
            // identity query or validated sysfs reads. It is not assumed to
            // match the launch-only protocol used by newer ClusterTune.
            input.writeStringArray(arrayOf(command, "1"))
            if (!remote.transact(0, input, reply, 0)) {
                return ShellProtocol.Parsed(null, null, "PServerBinder rejected legacy read-only request")
            }
            val bytes = reply.createByteArray()
                ?: return ShellProtocol.Parsed(null, null,
                    "Binder accepted request but returned no output; legacy protocol unverified")
            if (bytes.size > 65_536) {
                return ShellProtocol.Parsed(null, null, "PServer output exceeds diagnostic safety limit")
            }
            return ShellProtocol.parse(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return ShellProtocol.Parsed(null, null,
                "PServer legacy output probe failed: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            input.recycle()
            reply.recycle()
        }
    }

    override suspend fun read(path: KernelPath): HardwareRead = withContext(Dispatchers.IO) {
        val parsed = execute(ShellProtocol.readCommand(path))
        if (parsed.verified && parsed.value != null) {
            HardwareRead(path.value, parsed.value, ReadSource.PSERVER)
        } else {
            HardwareRead.unavailable(path.value, parsed.message ?: "Unverified PServer read")
        }
    }

    override suspend fun probe(): PrivilegeStatus = withContext(Dispatchers.IO) {
        val service = binder()
        if (service == null || !service.isBinderAlive) {
            return@withContext PrivilegeStatus(
                ReadSource.PSERVER, false, null,
                "PServerBinder not visible/alive. No privileged access verified."
            )
        }
        val parsed = execute(ShellProtocol.identityCommand())
        val uid = if (parsed.verified) parsed.value?.trim()?.toIntOrNull() else null
        val verified = parsed.verified && uid == 0
        PrivilegeStatus(
            transport = ReadSource.PSERVER,
            available = verified,
            reportedUid = uid,
            reason = when {
                verified -> "Legacy read-only identity verified (UID 0). " +
                    "PServer host and safe writes NOT verified."
                uid != null -> "PServer replied UID $uid (not elevated); unavailable for tuning."
                else -> "PServerBinder alive, but legacy command-output protocol unverified: " +
                    (parsed.message ?: "identity response unsupported") +
                    ". This does NOT mean PServer host launch is unsupported."
            }
        )
    }
}
