// SPDX-License-Identifier: MIT
// Adapted from ClusterTune's PServerHostLauncher.kt,
// Copyright (c) 2026 Aurelio Bernal. MIT license: licenses/ClusterTune-MIT.txt
package com.odin2portal.control.hardware

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.os.DeadObjectException
import java.lang.reflect.Method

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

/**
 * Cache the ServiceManager reflection method once. Cache the Binder only while
 * alive, invalidating it via Binder death notifications. No user shell or retry
 * path exists. This is NOT used in the 1–2 second DirectMonitor sampling loop.
 */
private object FirmwarePServerService {
    private val checkService: Method by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Class.forName("android.os.ServiceManager")
            .getMethod("checkService", String::class.java)
    }
    @Volatile private var cached: IBinder? = null
    @Volatile private var recipient: IBinder.DeathRecipient? = null

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    @Synchronized
    fun resolve(): IBinder? {
        cached?.takeIf { it.isBinderAlive }?.let { return it }
        cached = null
        recipient = null
        val fresh = checkService.invoke(null, "PServerBinder") as? IBinder ?: return null
        if (!fresh.isBinderAlive) return null
        val death = IBinder.DeathRecipient {
            synchronized(this) {
                if (cached === fresh) {
                    cached = null
                    recipient = null
                }
            }
        }
        try {
            fresh.linkToDeath(death, 0)
        } catch (_: android.os.RemoteException) {
            return null
        }
        cached = fresh
        recipient = death
        return fresh
    }

    @Synchronized
    fun invalidate(dead: IBinder) {
        if (cached === dead) {
            recipient?.let { runCatching { dead.unlinkToDeath(it, 0) } }
            cached = null
            recipient = null
        }
    }
}

private fun connectToFirmwarePServer(): PServerHostConnection? =
    FirmwarePServerService.resolve()?.let(::FirmwarePServerConnection)

private class FirmwarePServerConnection(private val service: IBinder) : PServerHostConnection {
    override val alive: Boolean get() = service.isBinderAlive

    override fun launch(command: String): Boolean {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInt(2)
            request.writeString(command)
            request.writeString("0") // output-disabled launch-only protocol
            // Never retry automatically after any failure or ambiguous acceptance.
            return service.transact(0, request, response, 0)
        } catch (error: DeadObjectException) {
            FirmwarePServerService.invalidate(service)
            throw error
        } catch (error: android.os.RemoteException) {
            FirmwarePServerService.invalidate(service)
            throw error
        } finally {
            response.recycle()
            request.recycle()
        }
    }
}
