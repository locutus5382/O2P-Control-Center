package com.odin2portal.control.hardware

/** Restricts shell reads to single, absolute sysfs/procfs paths without metacharacters. */
@JvmInline
value class KernelPath private constructor(val value: String) {
    companion object {
        private val safeChars = Regex("^/(sys|proc)/[A-Za-z0-9_./-]+$")
        fun parse(raw: String): KernelPath? {
            if (raw.length > 240 || !safeChars.matches(raw)) return null
            val parts = raw.split('/').drop(1)
            if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
            return KernelPath(raw)
        }
    }
}
