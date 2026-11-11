package com.mydocanizer.llm.engine.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class CpuThreadPolicyTest {
    @Test
    fun selectsCoresAtLeastSeventyPercentOfMaximumFrequency() {
        val root = createTempDir(prefix = "cpu-policy-")
        try {
            listOf(1_000_000L, 1_200_000L, 1_800_000L, 2_000_000L, 2_000_000L, 900_000L)
                .forEachIndexed { index, frequency ->
                    val frequencyFile = File(root, "cpu$index/cpufreq/cpuinfo_max_freq")
                    frequencyFile.parentFile!!.mkdirs()
                    frequencyFile.writeText(frequency.toString())
                }
            assertEquals(3, CpuThreadPolicy.recommendedThreads(root, 8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clampsLargePerformanceClusterToFourThreads() {
        val root = createTempDir(prefix = "cpu-policy-")
        try {
            repeat(8) { index ->
                val file = File(root, "cpu$index/cpufreq/cpuinfo_max_freq")
                file.parentFile!!.mkdirs()
                file.writeText("2000000")
            }
            assertEquals(4, CpuThreadPolicy.recommendedThreads(root, 8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToOnlineProcessorCountMinusTwoWhenSysfsIsUnavailable() {
        val missing = File("/path/that/does/not/exist/cpu-policy-test")
        assertEquals(2, CpuThreadPolicy.recommendedThreads(missing, 1))
        assertEquals(4, CpuThreadPolicy.recommendedThreads(missing, 16))
    }
}
