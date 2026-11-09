package com.odin2portal.control.hardware.host;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Ephemeral, READ-ONLY host launched by the firmware's PServer on explicit
 * user request. No generic commands, root shell entry point, sysfs writes,
 * chmod, governor/min/max setter, or persistent tuning capability.
 *
 * Strictly exits after a single verified read or its 20-second deadline.
 */
public final class O2PReadHostMain {
    public static final String ACTION = "com.odin2portal.control.READ_HOST_HANDOFF";
    public static final String DESCRIPTOR = "com.odin2portal.control.IReadHostV2";
    public static final String CALLBACK = "com.odin2portal.control.IReadHostProofV1";
    public static final int MAGIC = 0x4f325031;
    public static final int VERSION = 2;
    public static final int PING = 1;
    public static final int READ = 2;
    public static final int STOP = 3;
    public static final int READ_OK = 0;
    public static final int READ_DENIED = 1;
    public static final int READ_NOT_FOUND = 2;
    public static final int READ_EMPTY = 3;
    public static final int READ_ERROR = 4;

    /** Exact paths only: client may NOT supply a path or a shell command. */
    private static final String[] PATHS = {
        "/sys/devices/system/cpu/cpufreq/policy0/related_cpus",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies",
        "/sys/devices/system/cpu/cpufreq/policy3/related_cpus",
        "/sys/devices/system/cpu/cpufreq/policy3/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy3/scaling_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy3/cpuinfo_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy3/scaling_available_frequencies",
        "/sys/devices/system/cpu/cpufreq/policy7/related_cpus",
        "/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy7/cpuinfo_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy7/scaling_available_frequencies",
        "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
        "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
        "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
        "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
        "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
        "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
    };

    private O2PReadHostMain() {}

    private static final class ReadOnlyBinder extends Binder {
        private final int owner;
        private final String nonce;
        private final long generation;
        private boolean stopped;
        private boolean readDone;

        ReadOnlyBinder(int owner, String nonce, long generation) {
            this.owner = owner;
            this.nonce = nonce;
            this.generation = generation;
            attachInterface(null, DESCRIPTOR);
        }

        @Override protected synchronized boolean onTransact(
                int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (Binder.getCallingUid() != owner) {
                throw new SecurityException("Read host restricted to initiating app UID");
            }
            data.enforceInterface(DESCRIPTOR);
            if (!nonce.equals(data.readString()) || generation != data.readLong()) {
                throw new SecurityException("Invalid host handoff identity");
            }
            if (stopped || reply == null) return false;
            try {
                switch (code) {
                    case PING: {
                        IBinder callback = data.readStrongBinder();
                        if (callback == null) throw new SecurityException("Proof callback required");
                        Parcel challenge = Parcel.obtain();
                        Parcel answer = Parcel.obtain();
                        try {
                            challenge.writeInterfaceToken(CALLBACK);
                            challenge.writeString(nonce);
                            challenge.writeLong(generation);
                            if (!callback.transact(1, challenge, answer, 0)) {
                                throw new SecurityException("Proof callback failed");
                            }
                            if (answer.readInt() != 1) {
                                throw new SecurityException("Proof callback rejected");
                            }
                        } finally {
                            challenge.recycle();
                            answer.recycle();
                        }
                        reply.writeInt(MAGIC);
                        reply.writeInt(VERSION);
                        reply.writeInt(Process.myUid());
                        reply.writeString(nonce);
                        reply.writeLong(generation);
                        return true;
                    }
                    case READ:
                        if (readDone) throw new SecurityException("Read host single-use");
                        readDone = true;
                        reply.writeInt(PATHS.length);
                        for (String path : PATHS) {
                            NodeResult result = readFixedPath(path);
                            reply.writeString(path);
                            reply.writeInt(result.status);
                            reply.writeString(result.value);
                            reply.writeInt(result.errno);
                            reply.writeString(result.reason);
                        }
                        return true;
                    case STOP:
                        stopped = true;
                        reply.writeInt(1);
                        notifyAll();
                        return true;
                    default:
                        return false;
                }
            } catch (Exception error) {
                reply.setDataPosition(0);
                reply.setDataSize(0);
                reply.writeInt(-1);
                reply.writeString(error.getClass().getSimpleName());
                return true;
            }
        }

        private static final class NodeResult {
            final int status;
            final String value;
            final int errno;
            final String reason;

            NodeResult(int status, String value, int errno, String reason) {
                this.status = status;
                this.value = value;
                this.errno = errno;
                this.reason = reason;
            }
        }

        private NodeResult readFixedPath(String path) {
            FileDescriptor descriptor = null;
            try {
                descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
                byte[] buffer = new byte[1024];
                int count = Os.read(descriptor, buffer, 0, buffer.length);
                if (count <= 0)
                    return new NodeResult(READ_EMPTY, null, 0, "Empty sysfs response");
                String text = new String(buffer, 0, count, StandardCharsets.UTF_8).trim();
                if (text.length() == 0)
                    return new NodeResult(READ_EMPTY, null, 0, "Whitespace-only response");
                if (text.length() > 512)
                    return new NodeResult(READ_ERROR, null, 0, "Value exceeded 512 characters");
                return new NodeResult(READ_OK, text, 0, "");
            } catch (ErrnoException error) {
                int err = error.errno;
                int status = err == OsConstants.EACCES || err == OsConstants.EPERM
                    ? READ_DENIED
                    : err == OsConstants.ENOENT ? READ_NOT_FOUND : READ_ERROR;
                return new NodeResult(status, null, err,
                    "Linux errno " + err + " (" + error.functionName + ")");
            } catch (Exception error) {
                return new NodeResult(READ_ERROR, null, 0,
                    "Exception: " + error.getClass().getSimpleName());
            } finally {
                if (descriptor != null) {
                    try { Os.close(descriptor); } catch (Exception ignored) {}
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0 && Process.myUid() != 1000)
            throw new SecurityException("Privileged OEM host not started");
        if (args.length != 4) throw new IllegalArgumentException("Expected 4 host parameters");
        final int owner = Integer.parseInt(args[0]);
        final String nonce = args[1];
        final String packageName = args[2];
        final long generation = Long.parseLong(args[3]);
        if (owner <= 1000 || nonce.length() < 30 || nonce.length() > 64 ||
            !packageName.equals("com.odin2portal.control")) {
            throw new SecurityException("Invalid host identity");
        }

        // Match ClusterTune's tested system-context handoff pattern. Hidden
        // APIs and SELinux may differ on the Portal, so failure is fail-closed.
        android.os.Looper.prepare();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method main = threadClass.getDeclaredMethod("systemMain");
        main.setAccessible(true);
        Object thread = main.invoke(null);
        java.lang.reflect.Method getContext = threadClass.getDeclaredMethod("getSystemContext");
        getContext.setAccessible(true);
        Context context = (Context) getContext.invoke(thread);
        ReadOnlyBinder host = new ReadOnlyBinder(owner, nonce, generation);
        Bundle payload = new Bundle();
        payload.putBinder("host", host);
        Intent intent = new Intent(ACTION)
            .setPackage(packageName)
            .putExtra("nonce", nonce)
            .putExtra("generation", generation)
            .putExtra("payload", payload);
        context.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(owner));

        // No indefinite elevated process. No mutation means no cleanup daemon.
        synchronized (host) {
            final long deadline = SystemClock.elapsedRealtime() + 20_000L;
            while (!host.stopped && SystemClock.elapsedRealtime() < deadline) {
                host.wait(Math.max(1L, deadline - SystemClock.elapsedRealtime()));
            }
        }
    }
}
