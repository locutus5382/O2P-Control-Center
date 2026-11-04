package com.odin2portal.control.hardware

/** When the user requests an elevated transport, try direct reads first, then that transport.
 *  There is no silent su fallback, and an unavailable reading stays unavailable.
 */
class HardwareReader(
    private val direct: ReadTransport = DirectReader(),
    private val pserver: ReadTransport = PServerReader(),
    private val root: ReadTransport = RootReader()
) {
    suspend fun probe(mode: AccessMode): PrivilegeStatus = when (mode) {
        AccessMode.DIRECT_ONLY -> direct.probe()
        AccessMode.PSERVER -> pserver.probe()
        AccessMode.ROOT -> root.probe()
    }

    suspend fun read(rawPath: String, mode: AccessMode): HardwareRead {
        val path = KernelPath.parse(rawPath)
            ?: return HardwareRead.unavailable(rawPath, "Rejected invalid kernel path")
        val fromApp = direct.read(path)
        if (fromApp.isValid || mode == AccessMode.DIRECT_ONLY) return fromApp
        return when (mode) {
            AccessMode.PSERVER -> pserver.read(path)
            AccessMode.ROOT -> root.read(path)
            AccessMode.DIRECT_ONLY -> fromApp
        }
    }
}
