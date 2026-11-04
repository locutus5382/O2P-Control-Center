package com.odin2portal.control.hardware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Root is only attempted on explicit user selection. This reader contains no write API. */
class RootReader : ReadTransport {
    private fun execute(command: String): ShellProtocol.Parsed {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(3500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                ShellProtocol.Parsed(null, null, "Root command timed out")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText().take(8192) }
                if (process.exitValue() != 0) {
                    ShellProtocol.Parsed(null, process.exitValue(), "su exited ${process.exitValue()}")
                } else ShellProtocol.parse(output)
            }
        } catch (e: Exception) {
            ShellProtocol.Parsed(null, null, "Root unavailable: ${e.message}")
        } finally {
            process?.destroy()
        }
    }

    override suspend fun read(path: KernelPath): HardwareRead = withContext(Dispatchers.IO) {
        val parsed = execute(ShellProtocol.readCommand(path))
        if (parsed.verified) HardwareRead(path.value, parsed.value, ReadSource.SU)
        else HardwareRead.unavailable(path.value, parsed.message ?: "Root read unverified")
    }

    override suspend fun probe(): PrivilegeStatus = withContext(Dispatchers.IO) {
        val parsed = execute(ShellProtocol.identityCommand())
        val uid = if (parsed.verified) parsed.value?.toIntOrNull() else null
        PrivilegeStatus(
            ReadSource.SU,
            parsed.verified && uid == 0,
            uid,
            if (parsed.verified) "Returned UID $uid" else parsed.message ?: "Root not verified"
        )
    }
}
