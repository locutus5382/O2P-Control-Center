// SPDX-License-Identifier: MIT
// Adapted from ClusterTune's PServerHostLauncher.kt,
// Copyright (c) 2026 Aurelio Bernal. MIT license: licenses/ClusterTune-MIT.txt
package com.odin2portal.control.hardware

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel

/**
 * PServer is a privileged firmware service. The transport is NOT a reader,
 * a shell, or proof of authorization. This stage only probes Binder presence.
 *
 * The launch request exists for a future dedicated, audited, read-only host.
 * No production O2P component currently invokes launchHost.
 */
internal interface PServerHostConnection {
    val alive: Boolean
    fun launch(command: String): Boolean
}

internal class PServerHostTransport(
    private val connect: () -> PServerHostConnection? = ::connectToFirmwarePServer
) {
    val serviceVisible: Boolean
        get() = runCatching { connect()?.alive == true }.getOrDefault(false)

    /**
     * FOR FUTURE INTEGRATION ONLY. This is intentionally unused until O2P has
     * a read-only, authenticated, allowlisted host and nonce-protected handoff.
     * An accepted Binder transaction does not prove the host started.
     *
     * Never call this from UI, preferences, user-entered commands or scripts.
     */
    internal fun launchHost(command: String): Result<Unit> = runCatching {
        require(command.isNotBlank() && '\u0000' !in command) { "Invalid host launcher" }
        require(command.toByteArray(Charsets.UTF_8).size <= 255) {
            "PServer launcher exceeds 255 UTF-8 bytes"
        }
        val current = checkNotNull(connect()) { "PServerBinder unavailable" }
        check(current.alive) { "PServerBinder no longer alive" }
        // No automatic retry. The firmware may have accepted a command even if
        // the transport reports a broken transaction.
        check(current.launch(command)) { "PServer did not accept host launch" }
    }
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
private fun connectToFirmwarePServer(): PServerHostConnection? {
    val service = Class.forName("android.os.ServiceManager")
        .getMethod("checkService", String::class.java)
        .invoke(null, "PServerBinder") as? IBinder
    return service?.let(::FirmwarePServerConnection)
}

private class FirmwarePServerConnection(private val service: IBinder) : PServerHostConnection {
    override val alive: Boolean get() = service.isBinderAlive

    override fun launch(command: String): Boolean {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            // Firmware protocol on tested AYN Thor:
            // 2 UTF-16 strings preceded by int32 count; no interface token.
            // Output is explicitly disabled; no stdout bridge.
            request.writeInt(2)
            request.writeString(command)
            request.writeString("0")
            return service.transact(0, request, response, 0)
        } finally {
            response.recycle()
            request.recycle()
        }
    }
}
