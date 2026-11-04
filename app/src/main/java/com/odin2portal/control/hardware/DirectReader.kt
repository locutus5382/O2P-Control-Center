package com.odin2portal.control.hardware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DirectReader : ReadTransport {
    override suspend fun read(path: KernelPath): HardwareRead = withContext(Dispatchers.IO) {
        try {
            val file = File(path.value)
            if (!file.isFile || !file.canRead()) {
                HardwareRead.unavailable(path.value, "Not readable without elevated access")
            } else {
                HardwareRead(path.value, file.readText().trim(), ReadSource.DIRECT)
            }
        } catch (e: Exception) {
            HardwareRead.unavailable(path.value, e.message ?: "Direct read failed")
        }
    }

    override suspend fun probe() = PrivilegeStatus(
        ReadSource.DIRECT, true, null, "Android app process; no elevated access requested"
    )
}
