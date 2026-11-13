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
        val prefillPath =
            "2026-11-10 16:00:02.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_PREFILL_PATH - " +
                "device=Mali-G57,intDot=1,coopMatInt=0,canQuantizeY=1,selected=Q8_1_MATMUL"
        val decodePath =
            "2026-11-10 16:00:03.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - " +
                "device=Mali-G57,intDot=1,quantizeY=1,selected=Q8_1_INTEGER_DOT_MMVQ"
        val memoryBudget =
            "2026-11-10 16:00:04.000 [NATIVE] ggml_vulkan: LOCAL_LLM_MEMORY_BUDGET - " +
                "device=0,name=Mali-G57,integrated=1,extensionSupported=0,heapCount=1"
        val memoryHeap =
            "2026-11-10 16:00:05.000 [NATIVE] ggml_vulkan: LOCAL_LLM_MEMORY_HEAP - " +
                "device=0,heapIndex=0,budgetKnown=0,heapSizeBytes=7784226816,freeEstimateBytes=7784226816"
        val noisyErrors = (1..24).map { index ->
            "2026-11-10 16:01:%02d.000 [ERROR] [engine] SYNTHETIC_FAILURE - ".format(index) +
                "x".repeat(420)
        }
        val noisyPerformance = (1..16).map { index ->
            "2026-11-10 16:02:%02d.000 [INFO] [benchmark] GPU_LAYER_SWEEP_RESULT - ".format(index) +
                "detail=".repeat(90)
        }

        val noisyTokenMetrics = (1..30).flatMap { run ->
            (1..8).map { index ->
                nativeMetric(
                    index = index,
                    decodeUs = run * 10_000L + index * 1_000L,
                    pid = 700 + run,
                    runId = "overflow-run-$run",
                    backend = "Vulkan"
                )
            }
        }
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(capability, actualOffload, prefillPath, decodePath, memoryBudget, memoryHeap) +
                noisyErrors + noisyPerformance + noisyTokenMetrics
        )

        assertTrue(report.contains("int dot: 1"))
        assertTrue(report.contains("offloaded 37/37 layers to GPU"))
        assertTrue(report.contains("selected=Q8_1_MATMUL"))
        assertTrue(report.contains("selected=Q8_1_INTEGER_DOT_MMVQ"))
        assertTrue(report.contains("extensionSupported=0"))
        assertTrue(report.contains("budgetKnown=0"))
        assertTrue(
            report.indexOf("[VULKAN-LAUFZEITBELEG") <
                report.indexOf("[WICHTIGE EREIGNISSE")
        )
        assertTrue(report.contains("[Export begrenzt: weitere Einträge wurden abgeschnitten]"))
    }

    @Test
    fun compactReportRetainsPerRunCpuAndVulkanGraphNodeDistribution() {
        val distribution = "2026-11-12 05:00:00.000 [NATIVE] llama_context: " +
            "LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION - runId=matrix-q4-run,graphNodes=128," +
            "graphSplits=3,cpuNodes=12,vulkanNodes=116,otherNodes=0,unassignedNodes=0," +
            "backendIdsChanged=1,graphSizeChanged=1"
        val noise = (1..28).map { index ->
            "2026-11-12 05:00:%02d.000 [NATIVE] ggml_vulkan: SHADER_STATUS - ".format(index) +
                "shader=".repeat(50)
        }
        val report = EngineDiagnostics.buildCompactReportForTest(noise + distribution)

        assertTrue(report.contains("LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION"))
        assertTrue(report.contains("runId=matrix-q4-run"))
        assertTrue(report.contains("graphSplits=3"))
        assertTrue(report.contains("cpuNodes=12"))
        assertTrue(report.contains("vulkanNodes=116"))
    }

    @Test
    fun compactReportRetainsDmmvWorkgroupSelectionAndAllSweepResults() {
        val nativeSelections = listOf(
            "2026-11-11 16:00:00.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_DMMV_WG_SELECTED - " +
                "override=AUTO,selected=SUBGROUP,subgroupSize=16",
            "2026-11-11 16:01:00.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_DMMV_WG_SELECTED - " +
                "override=SUBGROUP,selected=SUBGROUP,subgroupSize=16",
            "2026-11-11 16:02:00.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_DMMV_WG_SELECTED - " +
                "override=LARGE,selected=LARGE,subgroupSize=16",
            "2026-11-11 16:03:00.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_DMMV_WG_SELECTED - " +
                "override=AUTO,selected=SUBGROUP,subgroupSize=16"
        )
        val sweepEvents = listOf(
            "2026-11-11 16:00:00.000 [INFO] [benchmark] VULKAN_DMMV_SWEEP_STARTED - profiles=AUTO,SUBGROUP,LARGE,AUTO_REPEAT",
            "2026-11-11 16:00:01.000 [INFO] [benchmark] VULKAN_DMMV_SWEEP_PLAN - modelLayers=37,requestedGpuLayers=99",
            "2026-11-11 16:01:00.000 [INFO] [benchmark] VULKAN_DMMV_SWEEP_RESULT - runIndex=1,workgroup=AUTO,workgroupNativeId=0,measuredTokensPerSecond=1.6",
            "2026-11-11 16:02:00.000 [INFO] [benchmark] VULKAN_DMMV_SWEEP_RESULT - runIndex=2,workgroup=SUBGROUP,workgroupNativeId=1,measuredTokensPerSecond=1.8",
            "2026-11-11 16:03:00.000 [INFO] [benchmark] VULKAN_DMMV_SWEEP_RESULT - runIndex=3,workgroup=LARGE,workgroupNativeId=2,measuredTokensPerSecond=2.0",
            "2026-11-11 16:04:00.000 [INFO] [benchmark] VULKAN_DMMV_SWEEP_RESULT - runIndex=4,workgroup=AUTO,workgroupNativeId=0,measuredTokensPerSecond=1.6",
            "2026-11-11 16:04:01.000 [INFO] [benchmark] VULKAN_DMMV_SWEEP_RESTORED - gpuLayers=37"
        )

        val report = EngineDiagnostics.buildCompactReportForTest(nativeSelections + sweepEvents)

        assertTrue(report.contains("Q4_K-/DMMV-KERNELPFAD"))
        assertTrue(report.contains("override=AUTO,selected=SUBGROUP,subgroupSize=16"))
        assertTrue(report.contains("override=SUBGROUP,selected=SUBGROUP,subgroupSize=16"))
        assertTrue(report.contains("override=LARGE,selected=LARGE,subgroupSize=16"))
        assertTrue(report.contains("VULKAN_DMMV_SWEEP_STARTED"))
        assertTrue(report.contains("VULKAN_DMMV_SWEEP_PLAN"))
        assertTrue(report.contains("workgroup=AUTO,workgroupNativeId=0"))
        assertTrue(report.contains("workgroup=SUBGROUP,workgroupNativeId=1"))
        assertTrue(report.contains("workgroup=LARGE,workgroupNativeId=2"))
        assertTrue(report.contains("VULKAN_DMMV_SWEEP_RESTORED"))
    }

    @Test
    fun compactReportRetainsQ4kDecodePathSweepAndNativePathTransitions() {
        val mmvqPath =
            "2026-11-11 17:00:00.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - " +
                "device=Mali-G57,intDot=1,quantizeY=1,selected=Q8_1_INTEGER_DOT_MMVQ"
        val basePath =
            "2026-11-11 17:01:00.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - " +
                "device=Mali-G57,intDot=1,quantizeY=0,selected=BASE_TYPE_MMV"
        val sweepEvents = listOf(
            "2026-11-11 17:00:00.000 [INFO] [benchmark] VULKAN_Q4K_PATH_SWEEP_STARTED - profiles=MMVQ,BASE,BASE_REPEAT,MMVQ_REPEAT",
            "2026-11-11 17:00:01.000 [INFO] [benchmark] VULKAN_Q4K_PATH_SWEEP_PLAN - modelLayers=37,requestedGpuLayers=99",
            "2026-11-11 17:00:30.000 [INFO] [benchmark] VULKAN_Q4K_PATH_SWEEP_RESULT - runIndex=1,path=MMVQ_INTEGER_DOT,pathNativeId=0,measuredTokensPerSecond=1.6",
            "2026-11-11 17:01:00.000 [INFO] [benchmark] VULKAN_Q4K_PATH_SWEEP_RESULT - runIndex=2,path=BASE_DMMV,pathNativeId=3,measuredTokensPerSecond=2.0",
            "2026-11-11 17:01:30.000 [INFO] [benchmark] VULKAN_Q4K_PATH_SWEEP_RESULT - runIndex=3,path=BASE_DMMV,pathNativeId=3,measuredTokensPerSecond=2.1",
            "2026-11-11 17:02:00.000 [INFO] [benchmark] VULKAN_Q4K_PATH_SWEEP_RESULT - runIndex=4,path=MMVQ_INTEGER_DOT,pathNativeId=0,measuredTokensPerSecond=1.6",
            "2026-11-11 17:02:01.000 [INFO] [benchmark] VULKAN_Q4K_PATH_SWEEP_RESTORED - gpuLayers=37"
        )
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(mmvqPath, basePath) + sweepEvents
        )

        assertTrue(report.contains("selected=Q8_1_INTEGER_DOT_MMVQ"))
        assertTrue(report.contains("selected=BASE_TYPE_MMV"))
        assertTrue(report.contains("VULKAN_Q4K_PATH_SWEEP_STARTED"))
        assertTrue(report.contains("VULKAN_Q4K_PATH_SWEEP_PLAN"))
        assertTrue(report.contains("path=MMVQ_INTEGER_DOT,pathNativeId=0"))
        assertTrue(report.contains("path=BASE_DMMV,pathNativeId=3"))
        assertTrue(report.contains("VULKAN_Q4K_PATH_SWEEP_RESTORED"))
    }
    @Test
    fun vulkanPerOperationGpuTimestampsAreGroupedByProcessRunAndOperator() {
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(
                vulkanOperationMetric(6412, "decode-run-a", 100, "mul_mat_vec_q4_K", 1, 620.0, 620.0),
                vulkanOperationMetric(6412, "decode-run-a", 101, "mul_mat_vec_q4_K", 1, 580.0, 580.0),
                vulkanOperationMetric(6412, "decode-run-a", 102, "mul_mat_vec_q4_K", 1, 590.0, 590.0),
                vulkanOperationMetric(6412, "decode-run-b", 103, "mul_mat_vec_q4_K", 1, 900.0, 900.0),
                vulkanOperationMetric(6412, "decode-run-a", 102, "rms_norm", 1, 12.5, 12.5),
                vulkanOperationMetric(
                    6412, "decode-run-a", 104, "FLASH_ATTN_EXT dst(128;1;8)", 1, 25.0, 25.0
                ),
                vulkanOperationMetric(6412, "two-snapshot-run", 105, "two_value_op", 1, 580.0, 580.0),
                vulkanOperationMetric(6412, "two-snapshot-run", 106, "two_value_op", 1, 620.0, 620.0)
            )
        )

        assertTrue(report.contains("[VULKAN-GPU-OPERATIONSPROFIL"))
        assertTrue(
            report.contains(
                "pid=6412 runId=decode-run-a op=mul_mat_vec_q4_K snapshots=3 samples=3 " +
                    "gpuAvg p50=590.000us p90=614.000us max=620.000us gpuPerGraphUs=596.667"
            )
        )
        assertTrue(
            report.contains(
                "pid=6412 runId=decode-run-b op=mul_mat_vec_q4_K snapshots=1 samples=1 " +
                    "gpuAvg p50=900.000us p90=900.000us max=900.000us gpuPerGraphUs=900.000"
            )
        )
        assertTrue(report.contains("op=FLASH_ATTN_EXT dst(128;1;8) snapshots=1"))
        assertTrue(
            report.contains(
                "runId=two-snapshot-run op=two_value_op snapshots=2 samples=2 " +
                    "gpuAvg p50=600.000us p90=616.000us max=620.000us gpuPerGraphUs=600.000"
            )
        )
        assertFalse(report.contains("LOCAL_LLM_VULKAN_OP_TIMING -"))
    }

    @Test
    fun thermalPeakAndObservedStatesAreReportedPerInferenceRun() {
        val first =
            "2026-11-12 04:00:00.000 [INFO] [memory] RESOURCE_SNAPSHOT - " +
                "pid=701,phase=INFERENCE_run-thermal-1,processPssMb=900.0,nativePssMb=500.0," +
                "nativeHeapAllocatedMb=420.0,javaHeapUsedMb=80.0,systemAvailableRamMb=1200.0," +
                "thermalStatus=LIGHT,thermalStatusCode=1,microBatchSize=64"
        val second =
            "2026-11-12 04:00:01.000 [INFO] [memory] RESOURCE_SNAPSHOT - " +
                "pid=701,phase=INFERENCE_run-thermal-1,processPssMb=1100.0,nativePssMb=650.0," +
                "nativeHeapAllocatedMb=520.0,javaHeapUsedMb=90.0,systemAvailableRamMb=900.0," +
                "thermalStatus=SEVERE,thermalStatusCode=3,microBatchSize=64"

        val report = EngineDiagnostics.buildCompactReportForTest(listOf(first, second))

        assertTrue(report.contains("701/INFERENCE_run-thermal-1"))
        assertTrue(report.contains("PSS_peak=1100.0MB"))
        assertTrue(report.contains("availableRAM_min=900.0MB"))
        assertTrue(report.contains("thermal_peak=SEVERE"))
        assertTrue(report.contains("thermal_states=LIGHT/SEVERE"))
        assertTrue(report.contains("thermal=SEVERE"))
    }

    @Test
    fun ramSummaryIsPlacedBeforeVerboseTokenMetricsWhenExportMustBeTruncated() {
        val ramSample =
            "2026-11-10 16:00:00.000 [INFO] [memory] RESOURCE_SNAPSHOT - " +
                "pid=100,phase=MODEL_LOADED_IDLE,processPssMb=123.4,nativePssMb=50.0," +
                "nativeHeapAllocatedMb=20.0,javaHeapUsedMb=10.0,systemAvailableRamMb=1000.0"
        val manyTokenRuns = (1..50).flatMap { run ->
            (1..8).map { index ->
                nativeMetric(
                    index = index,
                    decodeUs = run * 100_000L + index,
                    pid = 800 + run,
                    runId = "ram-priority-run-$run",
                    backend = "Vulkan"
                )
            }
        }

        val report = EngineDiagnostics.buildCompactReportForTest(listOf(ramSample) + manyTokenRuns)
        val ramIndex = report.indexOf("[RAM-ZUSAMMENFASSUNG – SPITZENWERTE PRO PHASE]")
        val tokenIndex = report.indexOf("[NATIVE TOKEN-DECODE-ZEITEN – STICHPROBEN]")

        assertTrue(report.contains("MODEL_LOADED_IDLE"))
        assertTrue(ramIndex >= 0)
        assertTrue(tokenIndex < 0 || ramIndex < tokenIndex)
        assertTrue(report.contains("PSS_peak=123.4MB"))
    }

    private fun vulkanOperationMetric(
        pid: Int,
        runId: String,
        snapshot: Int,
        operation: String,
        samples: Int,
        averageUs: Double,
        totalUs: Double
    ): String =
        "2026-11-10 16:00:00.000 [NATIVE] ggml_vulkan: " +
            "LOCAL_LLM_VULKAN_OP_TIMING - pid=$pid,runId=$runId,snapshot=$snapshot,op=$operation," +
            "samples=$samples,avgUs=$averageUs,totalUs=$totalUs,gflops=n/a"

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
    @Test
    fun exportPeriodIgnoresMultilineExceptionContinuationWithoutTimestamp() {
        val first = "2026-11-13 00:00:49.737 [INFO] [engine] STARTED"
        val last = "2026-11-13 08:27:18.900 [INFO] [engine] COMPLETED"
        val continuation = "See callbackFlow API documentation for the details."

        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(first, continuation, last)
        )

        assertTrue(report.contains("Zeitraum: 2026-11-13 00:00:49.737 bis 2026-11-13 08:27:18.900"))
        assertFalse(report.contains("Zeitraum: 2026-11-13 00:00:49.737 bis See callbackFlow"))
    }

}
