package com.odin2portal.control.domain

object Parsing {
    fun cpuList(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        val result = sortedSetOf<Int>()
        for (token in raw.trim().split(Regex("[,\\s]+"))) {
            val range = token.split('-')
            when (range.size) {
                1 -> range[0].toIntOrNull()?.takeIf { it in 0..255 }?.let(result::add)
                2 -> {
                    val first = range[0].toIntOrNull()
                    val last = range[1].toIntOrNull()
                    if (first != null && last != null && first in 0..255 && last in first..255) {
                        (first..last).forEach(result::add)
                    }
                }
            }
        }
        return result.toList()
    }

    fun frequencyList(raw: String?): List<Long> = raw.orEmpty()
        .trim().split(Regex("\\s+"))
        .mapNotNull { it.toLongOrNull() }
        .filter { it > 0L }
        .distinct().sorted()

    fun memoryKb(raw: String?, key: String): Long? = raw.orEmpty().lineSequence()
        .firstOrNull { it.substringBefore(':') == key }
        ?.substringAfter(':')?.trim()?.substringBefore(' ')
        ?.toLongOrNull()

    /** Android thermal zone values can be degrees or milli-degrees; don't infer on weird scales. */
    fun temperatureC(raw: String?): Double? {
        val v = raw?.trim()?.toDoubleOrNull() ?: return null
        val c = if (v > 1000.0) v / 1000.0 else v
        return c.takeIf { it in -20.0..150.0 }
    }
}
