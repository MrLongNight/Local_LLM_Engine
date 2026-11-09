package com.mydocanizer.llm.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineDiagnosticsTest {

    @Test
    fun nativeDecodeMetricsAreGroupedByProcessBackendAndInferenceRun() {
        val lines = listOf(
            nativeMetric(index = 1, decodeUs = 100_000, pid = 100, runId = "vulkan-run", backend = "Vulkan"),
            nativeMetric(index = 2, decodeUs = 120_000, pid = 100, runId = "vulkan-run", backend = "Vulkan"),
            nativeMetric(index = 1, decodeUs = 9_000_000, pid = 100, runId = "cpu-run", backend = "CPU"),
            nativeMetric(index = 1, decodeUs = 300_000, pid = 101, runId = "vulkan-run", backend = "Vulkan"),
            kotlinMetric(index = 1, jniWallUs = 105_000, pid = 100, runId = "vulkan-run", backend = "Vulkan"),
            kotlinMetric(index = 2, jniWallUs = 125_000, pid = 100, runId = "vulkan-run", backend = "Vulkan")
        )

        val report = EngineDiagnostics.buildCompactReportForTest(lines)

        assertTrue(report.contains("pid=100 backend=Vulkan runId=vulkan-run samples=2"))
        assertTrue(report.contains("decode p50=120000us"))
        assertTrue(report.contains("decode p50=9000000us"))
        assertTrue(report.contains("pid=101 backend=Vulkan runId=vulkan-run samples=1"))
        assertTrue(report.contains("pid=100 backend=CPU runId=cpu-run samples=1"))
        assertTrue(report.contains("jniWall p50=125000us"))
    }

    @Test
    fun legacyNativeSamplesWithoutRunIdentityAreNotMixedIntoPercentiles() {
        val legacy = "2026-11-09 21:00:00.000 [NATIVE] TOKEN_STEP_METRIC - " +
            "index=1, sampleUs=100, decodeUs=9000000, decodeSubmitUs=9000000, " +
            "gpuSyncWaitUs=0, pieceUs=5, totalUs=9000105, backend=CPU"

        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(legacy, nativeMetric(1, 100_000, 200, "fresh-run", "Vulkan"))
        )

        assertTrue(report.contains("Legacy native samples without pid/runId/backend correlation (excluded from percentiles): 1"))
        assertTrue(report.contains("pid=200 backend=Vulkan runId=fresh-run samples=1"))
        assertFalse(report.contains("pid=unknown backend=CPU"))
        assertFalse(report.contains("decode p50=9000000us"))
    }


    @Test
    fun percentileUsesTheFullSortedRangeForP50AndP90() {
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(
                nativeMetric(1, 100, 300, "percentile-run", "Vulkan"),
                nativeMetric(2, 200, 300, "percentile-run", "Vulkan"),
                nativeMetric(3, 300, 300, "percentile-run", "Vulkan"),
                nativeMetric(4, 400, 300, "percentile-run", "Vulkan")
            )
        )

        assertTrue(report.contains("decode p50=300us"))
        assertTrue(report.contains("p90=400us"))
    }

    @Test
    fun benchmarkSummaryIsNotDisplacedByManyTokenStepRows() {
        val tokenRows = (1..32).map { index ->
            kotlinMetric(
                index = index,
                jniWallUs = index * 1_000L,
                pid = 400,
                runId = "long-chat-run",
                backend = "Vulkan"
            )
        }
        val benchmarkResult =
            "2026-11-09 21:00:01.000 [INFO] [benchmark] BACKEND_BENCHMARK_RESULT - " +
                "CPU: measuredTokensPerSecond=0.750,activeBackend=CPU"

        val report = EngineDiagnostics.buildCompactReportForTest(tokenRows + benchmarkResult)

        assertTrue(report.contains("BACKEND_BENCHMARK_RESULT"))
        assertTrue(report.contains("measuredTokensPerSecond=0.750"))
    }

    private fun nativeMetric(
        index: Int,
        decodeUs: Long,
        pid: Int,
        runId: String,
        backend: String
    ): String =
        "2026-11-09 21:00:00.000 [NATIVE] TOKEN_STEP_METRIC - " +
            "index=$index, sampleUs=500, decodeUs=$decodeUs, decodeSubmitUs=$decodeUs, " +
            "gpuSyncWaitUs=0, pieceUs=5, totalUs=${decodeUs + 505}, " +
            "pid=$pid, runId=$runId, backend=$backend"

    private fun kotlinMetric(
        index: Int,
        jniWallUs: Long,
        pid: Int,
        runId: String,
        backend: String
    ): String =
        "2026-11-09 21:00:00.000 [INFO] [inference] TOKEN_STEP_METRIC - " +
            "runId=$runId,pid=$pid,nativeStep=$index,jniWallUs=$jniWallUs," +
            "visiblePieces=$index,backend=$backend"
}
