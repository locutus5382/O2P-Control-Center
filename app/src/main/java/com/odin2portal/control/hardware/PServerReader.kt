package com.odin2portal.control.hardware

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Based on O2PKernelTurb's PServerBinder discovery and transact(0) protocol.
 *  NOT an Android public API. Read-only and opt-in until the user's firmware is validated.
 *  Binder calls may block, and coroutine cancellation cannot interrupt a blocked transact.
 */
class PServerReader : ReadTransport {
    @SuppressLint("PrivateApi")
    private fun binder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        val method = sm.getMethod("getService", String::class.java)
        method.invoke(null, "PServerBinder") as? IBinder
    } catch (_: Exception) {
        null
    }

    private fun execute(command: String): ShellProtocol.Parsed {
        val remote = binder() ?: return ShellProtocol.Parsed(null, null, "PServerBinder absent")
        val input = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            input.writeStringArray(arrayOf(command, "1"))
            if (!remote.transact(0, input, reply, 0)) {
                return ShellProtocol.Parsed(null, null, "Binder transaction rejected")
            }
            val bytes = reply.createByteArray()
                ?: return ShellProtocol.Parsed(null, null, "Binder returned no bytes")
            return ShellProtocol.parse(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return ShellProtocol.Parsed(null, null, "Binder error: ${e.message}")
        } finally {
            input.recycle()
            reply.recycle()
        }
    }

    override suspend fun read(path: KernelPath): HardwareRead = withContext(Dispatchers.IO) {
        val parsed = execute(ShellProtocol.readCommand(path))
        if (parsed.verified) HardwareRead(path.value, parsed.value, ReadSource.PSERVER)
        else HardwareRead.unavailable(path.value, parsed.message ?: "PServer read unverified")
    }

    override suspend fun probe(): PrivilegeStatus = withContext(Dispatchers.IO) {
        val parsed = execute(ShellProtocol.identityCommand())
        val uid = if (parsed.verified) parsed.value?.toIntOrNull() else null
        PrivilegeStatus(
            transport = ReadSource.PSERVER,
            available = parsed.verified && uid != null,
            reportedUid = uid,
            reason = if (parsed.verified && uid != null) "Verified command result" else
                parsed.message ?: "Invalid identity response"
        )
    }
}
