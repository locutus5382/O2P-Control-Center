package com.odin2portal.control.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.DeadObjectException
import com.odin2portal.control.hardware.host.O2PReadHostMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

enum class HostNodeStatus { OBSERVED, PERMISSION_DENIED, NOT_FOUND, EMPTY, IO_ERROR }

data class HostNodeRead(
    val path: String,
    val status: HostNodeStatus,
    val value: String?,
    val linuxErrno: Int?,
    val error: String?,
    val source: String = "PSERVER_READ_ONLY_HOST"
)

data class ReadOnlyHostResult(
    val authenticated: Boolean,
    val verifiedHostUid: Int?,
    val nodes: List<HostNodeRead>,
    val diagnostic: String,
    val capturedAt: Long = System.currentTimeMillis()
) {
    // Compatibility accessor for existing diagnostic UI; never hide failures.
    val fields: Map<String, String> get() = nodes.associate {
        it.path to (it.value ?: "UNAVAILABLE: " + it.status.name +
            (it.linuxErrno?.let { n -> " errno=$n" } ?: ""))
    }
    fun export(): String = buildString {
        appendLine("O2P PServer READ-ONLY HOST VALIDATION")
        appendLine("Authenticated privileged host: " + authenticated)
        appendLine("Verified host UID: " + (verifiedHostUid ?: "UNVERIFIED"))
        appendLine("Result: " + diagnostic)
        appendLine("No CPU/GPU/thermal/fan writes were performed.")
        nodes.forEach {
            appendLine(it.path + " | " + it.status +
                " | " + (it.value ?: "null") +
                " | errno=" + (it.linuxErrno ?: "not available") +
                " | error=" + (it.error ?: "none") +
                " | source=" + it.source)
        }
        appendLine("Do not interpret a single host read as an authenticated OEM Stock baseline.")
    }
}

/** Volatile, per-process last opt-in host test for consolidated diagnostic JSON. */
object PServerValidationCache {
    @Volatile var last: ReadOnlyHostResult? = null
        private set
    fun record(value: ReadOnlyHostResult) { last = value }
}

/**
 * Explicit user-triggered probe only. A valid PServer launch is not a grant of
 * unrestricted shell commands: the host has only PING, READ_FIXED and STOP,
 * checks caller UID, and dies after 20 seconds regardless of client behavior.
 */
class PServerReadOnlyHostClient(private val context: Context) {
    companion object {
        private val lock = Any()
        private const val HANDOFF_WAIT_MS = 8_000L
        private const val MAX_DEX_BYTES = 50L * 1024 * 1024
        private val ALLOWED_PATHS = setOf(
            "related_cpus", "scaling_min_freq", "scaling_max_freq",
            "cpuinfo_max_freq", "scaling_available_frequencies",
            "gpu_available_frequencies", "min_pwrlevel", "max_pwrlevel",
            "max_gpuclk", "min_freq", "max_freq"
        )
    }

    suspend fun verifyReadOnly(): ReadOnlyHostResult = withContext(Dispatchers.IO) {
        val result = synchronized(lock) {
            runCatching { verifyInternal() }.getOrElse {
                ReadOnlyHostResult(false, null, emptyList(),
                    "Read-only PServer host unavailable: " +
                        it.javaClass.simpleName + ": " + (it.message ?: "unknown"))
            }
        }
        PServerValidationCache.record(result)
        result
    }

    private fun verifyInternal(): ReadOnlyHostResult {
        if (!PServerHostTransport().serviceVisible)
            return ReadOnlyHostResult(false, null, emptyList(),
                "PServerBinder not visible or alive on this firmware")

        val generation = context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val hostBinder = AtomicReference<IBinder?>()
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != O2PReadHostMain.ACTION) return
                if (intent.getStringExtra("nonce") != nonce ||
                    intent.getLongExtra("generation", -1L) != generation) return
                val host = intent.getBundleExtra("payload")?.getBinder("host") ?: return
                if (hostBinder.compareAndSet(null, host)) latch.countDown()
            }
        }
        // Android 13: exported dynamic receiver is required for a distinct
        // root/system process. Nonce + callback Binder UID proof authenticate.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(O2PReadHostMain.ACTION),
                Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(O2PReadHostMain.ACTION))
        }

        var host: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
        val hostDied = AtomicBoolean(false)
        try {
            val dir = File(context.codeCacheDir, "o2p-ephemeral-read-host")
                .apply { if (!exists()) check(mkdirs()) { "Cannot create host cache" } }
            val dex = extractDex(dir, generation)
            val startup = File(dir, "startup.log")
            startup.writeText("")
            val script = File(dir, "launch-readonly.sh")
            val classpath = dex.joinToString(":") { it.absolutePath }
            script.writeText(buildString {
                appendLine("#!/system/bin/sh")
                append("CLASSPATH=")
                append(quote(classpath))
                append(" nohup setsid /system/bin/app_process /system/bin ")
                append(O2PReadHostMain::class.java.name)
                listOf(Process.myUid().toString(), nonce, context.packageName,
                    generation.toString()).forEach { append(" ").append(quote(it)) }
                appendLine(" >'./startup.log' 2>&1 </dev/null &")
            })
            script.setReadable(true, true)
            // A short, fixed launcher command. Neither an arbitrary shell
            // command nor a user-specified path is ever accepted by the host.
            val launch = "cd " + quote(dir.absolutePath) + " && sh './launch-readonly.sh'"
            val response = PServerHostTransport().launchHost(launch)
            response.getOrThrow()
            if (!latch.await(HANDOFF_WAIT_MS, TimeUnit.MILLISECONDS)) {
                val log = startup.takeIf { it.isFile }?.readText()?.takeLast(512).orEmpty()
                throw IllegalStateException(
                    "Host did not hand off Binder on this Portal firmware. " + log
                )
            }
            host = checkNotNull(hostBinder.get()) { "Host handoff contained no Binder" }
            val death = IBinder.DeathRecipient { hostDied.set(true) }
            host.linkToDeath(death, 0)
            deathRecipient = death
            val verifiedUid = authenticate(host, nonce, generation)
            check(!hostDied.get()) { "Privileged host died during authentication" }
            val values = readFixedValues(host, nonce, generation)
            check(!hostDied.get()) { "Privileged host died during read-only capability request" }
            return ReadOnlyHostResult(true, verifiedUid, values,
                "Authenticated single-use PServer read-only host. UID callback verified. " +
                    "Hardware writes remain unavailable.")
        } finally {
            host?.let { ref ->
                runCatching { stop(ref, nonce, generation) }
                deathRecipient?.let { recipient ->
                    runCatching { ref.unlinkToDeath(recipient, 0) }
                }
            }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun authenticate(host: IBinder, nonce: String, generation: Long): Int {
        check(host.isBinderAlive) { "Handoff Binder died before authentication" }
        check(host.interfaceDescriptor == O2PReadHostMain.DESCRIPTOR) {
            "Unexpected host interface descriptor"
        }
        val callerUid = AtomicInteger(-1)
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1 || reply == null) return false
                data.enforceInterface(O2PReadHostMain.CALLBACK)
                if (data.readString() != nonce || data.readLong() != generation)
                    throw SecurityException("Invalid host UID challenge")
                val actual = getCallingUid()
                if (actual != 0 && actual != 1000)
                    throw SecurityException("Host is not privileged")
                callerUid.set(actual)
                reply.writeInt(1)
                return true
            }
        }
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(O2PReadHostMain.DESCRIPTOR)
            request.writeString(nonce)
            request.writeLong(generation)
            request.writeStrongBinder(callback)
            check(host.transact(O2PReadHostMain.PING, request, response, 0)) {
                "Host refused identity challenge"
            }
            check(response.readInt() == O2PReadHostMain.MAGIC) { "Host ping returned failure" }
            check(response.readInt() == O2PReadHostMain.VERSION) { "Host protocol mismatch" }
            val statedUid = response.readInt()
            check(response.readString() == nonce && response.readLong() == generation) {
                "Host generation/nonce mismatch"
            }
            check(statedUid == callerUid.get() && statedUid in listOf(0, 1000)) {
                "Host UID proof not verified by Binder kernel"
            }
            return statedUid
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    private fun readFixedValues(host: IBinder, nonce: String,
                                generation: Long): List<HostNodeRead> {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(O2PReadHostMain.DESCRIPTOR)
            request.writeString(nonce)
            request.writeLong(generation)
            check(host.transact(O2PReadHostMain.READ, request, response, 0)) {
                "Read-only host refused fixed capabilities"
            }
            val count = response.readInt()
            check(count in 1..24) { "Unexpected host node count=$count" }
            val seen = mutableSetOf<String>()
            return buildList {
                repeat(count) {
                    val path = checkNotNull(response.readString())
                    val code = response.readInt()
                    val value = response.readString()
                    val errno = response.readInt()
                    val reason = response.readString()
                    check(path.length < 160 && path.startsWith("/sys/") &&
                        path.substringAfterLast('/') in ALLOWED_PATHS &&
                        (value?.length ?: 0) <= 512 &&
                        (reason?.length ?: 0) <= 160 && seen.add(path)) {
                        "Malformed or duplicate host read path"
                    }
                    val status = when (code) {
                        O2PReadHostMain.READ_OK -> HostNodeStatus.OBSERVED
                        O2PReadHostMain.READ_DENIED -> HostNodeStatus.PERMISSION_DENIED
                        O2PReadHostMain.READ_NOT_FOUND -> HostNodeStatus.NOT_FOUND
                        O2PReadHostMain.READ_EMPTY -> HostNodeStatus.EMPTY
                        O2PReadHostMain.READ_ERROR -> HostNodeStatus.IO_ERROR
                        else -> error("Unknown host read status $code")
                    }
                    check((status == HostNodeStatus.OBSERVED) ==
                        (value != null && value.isNotBlank())) {
                        "Host node status/value mismatch"
                    }
                    add(HostNodeRead(path, status, value,
                        errno.takeIf { it > 0 }, reason?.takeIf { it.isNotBlank() }))
                }
            }
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    private fun stop(host: IBinder, nonce: String, generation: Long) {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(O2PReadHostMain.DESCRIPTOR)
            request.writeString(nonce)
            request.writeLong(generation)
            host.transact(O2PReadHostMain.STOP, request, response, 0)
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    private fun extractDex(dir: File, generation: Long): List<File> {
        val marker = File(dir, "generation.txt")
        val cached = marker.takeIf { it.isFile }?.readLines().orEmpty()
        if (cached.firstOrNull() == generation.toString()) {
            val files = cached.drop(1).filter {
                it.matches(Regex("classes(\\d*)\\.dex"))
            }.map { File(dir, it) }
            if (files.isNotEmpty() && files.all { it.isFile && it.length() in 1..MAX_DEX_BYTES })
                return files
        }
        val output = mutableListOf<File>()
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val names = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                .map { it.name }.sorted().toList()
            check(names.isNotEmpty() && names.size <= 12) { "Invalid APK dex entries" }
            names.forEach { name ->
                val entry = checkNotNull(zip.getEntry(name))
                check(entry.size in 1..MAX_DEX_BYTES) { "Oversized host dex" }
                val target = File(dir, name)
                val tmp = File(dir, ".$name.tmp")
                zip.getInputStream(entry).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                check(tmp.length() in 1..MAX_DEX_BYTES) { "Invalid extracted dex" }
                if (target.exists()) check(target.delete())
                check(tmp.renameTo(target)) { "Cannot finalize host dex" }
                output += target
            }
        }
        marker.writeText(generation.toString() + "\n" +
            output.joinToString("\n") { it.name })
        return output
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
