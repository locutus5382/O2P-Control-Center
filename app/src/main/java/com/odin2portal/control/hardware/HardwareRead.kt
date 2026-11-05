package com.odin2portal.control.hardware

enum class ReadSource { DIRECT, PSERVER, NONE }

data class HardwareRead(
    val path: String,
    val value: String?,
    val source: ReadSource,
    val error: String? = null
) {
    val isValid: Boolean get() = value != null && error == null
    companion object {
        fun unavailable(path: String, reason: String) =
            HardwareRead(path, null, ReadSource.NONE, reason)
    }
}

enum class AccessMode { DIRECT_ONLY, PSERVER }

data class PrivilegeStatus(
    val transport: ReadSource,
    val available: Boolean,
    val reportedUid: Int?,
    val reason: String
)

interface ReadTransport {
    suspend fun read(path: KernelPath): HardwareRead
    suspend fun probe(): PrivilegeStatus
}
