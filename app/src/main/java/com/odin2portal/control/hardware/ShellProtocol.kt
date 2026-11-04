package com.odin2portal.control.hardware

/** Marker is required: empty shell stdout must never be treated as success. */
object ShellProtocol {
    private const val MARKER = "__O2P_CC_RC_V1__="
    data class Parsed(val value: String?, val code: Int?, val message: String?) {
        val verified get() = code == 0
    }
    fun readCommand(path: KernelPath): String =
        "cat '${path.value}'; o2p_code=\$?; printf '\\n${MARKER}%s\\n' \"\$o2p_code\""

    fun identityCommand(): String =
        "id -u; o2p_code=\$?; printf '\\n${MARKER}%s\\n' \"\$o2p_code\""

    fun parse(output: String?): Parsed {
        if (output == null) return Parsed(null, null, "No response")
        val markerPos = output.lastIndexOf(MARKER)
        if (markerPos < 0) return Parsed(null, null, "No verified exit marker")
        val rawCode = output.substring(markerPos + MARKER.length).trim()
        val code = rawCode.toIntOrNull()
            ?: return Parsed(null, null, "Invalid exit marker")
        val data = output.substring(0, markerPos).trimEnd('\n', '\r')
        return if (code == 0) Parsed(data.trim(), 0, null)
            else Parsed(null, code, "Command failed with exit status $code")
    }
}
