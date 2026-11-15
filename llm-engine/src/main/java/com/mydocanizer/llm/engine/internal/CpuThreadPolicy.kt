package com.mydocanizer.llm.engine.internal

import java.io.File

/**
 * Selects performance cores from sysfs. A core qualifies when its cpuinfo_max_freq
 * is at least 70% of the highest reported frequency. The result is clamped to 2..4.
 */
internal object CpuThreadPolicy {
    fun recommendedThreads(
        cpuRoot: File = File("/sys/devices/system/cpu"),
        availableProcessors: Int = Runtime.getRuntime().availableProcessors()
    ): Int {
        val frequencies = cpuRoot.listFiles().orEmpty().mapNotNull { cpuDir ->
            val match = CPU_DIRECTORY.matchEntire(cpuDir.name) ?: return@mapNotNull null
            val value = runCatching {
                File(cpuDir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
            value?.takeIf { it > 0L }
        }
        val maxFrequency = frequencies.maxOrNull()
        val count = if (maxFrequency != null) {
            frequencies.count { it * 10L >= maxFrequency * 7L }
        } else {
            availableProcessors - 2
        }
        return count.coerceIn(2, 4)
    }

    private val CPU_DIRECTORY = Regex("cpu[0-9]+")
}
