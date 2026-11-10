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
    fun legacyGpuSyncFieldIsMappedToBackendSynchronizationNotKernelTime() {
        val oldFormat = "2026-11-10 16:00:00.000 [NATIVE] TOKEN_STEP_METRIC - " +
            "index=1, sampleUs=500, decodeUs=610000, decodeSubmitUs=7000, " +
            "gpuSyncWaitUs=603000, pieceUs=5, totalUs=610505, " +
            "pid=501, runId=legacy-sync-run, backend=Vulkan"

        val report = EngineDiagnostics.buildCompactReportForTest(listOf(oldFormat))

        assertTrue(report.contains("backendSyncWait p50=603000us"))
        assertTrue(report.contains("ältere Samples heißen gpuSyncWaitUs"))
        assertTrue(report.contains("nicht als reine GPU-Kernelzeit"))
        assertFalse(report.contains("gpuSyncWait p50="))
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

    @Test
    fun compactReportPrioritizesVulkanRuntimeCapabilitiesAndActualLayerOffload() {
        val capability = "2026-11-10 16:00:00.000 [NATIVE] ggml_vulkan: 0 = Mali-G57 (Mali driver) | " +
            "uma: 1 | rebar: 0 | fp16: true | bf16: 0 | fp4: 0 | warp size: 16 | " +
            "shared memory: 32768 | int dot: 1 | matrix cores: none"
        val actualOffload = "2026-11-10 16:00:01.000 [NATIVE] llama_model_load: offloaded 37/37 layers to GPU"
        val noisyErrors = (1..24).map { index ->
            "2026-11-10 16:01:%02d.000 [ERROR] [engine] SYNTHETIC_FAILURE - ".format(index) +
                "x".repeat(420)
        }
        val noisyPerformance = (1..16).map { index ->
            "2026-11-10 16:02:%02d.000 [INFO] [benchmark] GPU_LAYER_SWEEP_RESULT - ".format(index) +
                "detail=".repeat(90)
        }

        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(capability, actualOffload) + noisyErrors + noisyPerformance
        )

        assertTrue(report.contains("int dot: 1"))
        assertTrue(report.contains("offloaded 37/37 layers to GPU"))
        assertTrue(
            report.indexOf("[VULKAN-LAUFZEITBELEG") <
                report.indexOf("[WICHTIGE EREIGNISSE")
        )
        assertTrue(report.contains("[Export begrenzt: weitere Einträge wurden abgeschnitten]"))
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
            "backendSyncWaitUs=0, pieceUs=5, totalUs=${decodeUs + 505}, " +
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
