package com.mydocanizer.llm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineDiagnosticsTest {

    @Test
    fun inferencePhaseMetricsRemainCorrelatedAcrossKotlinNativeAndGraphEvidence() {
        val runId = "phase-run-01"
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(
                "2026-11-20 19:00:00.000 [INFO] [inference] INFERENCE_PHASE_METRICS - " +
                    "runId=$runId,pid=701,backend=Vulkan,prefillUs=1200000,ttftUs=1450000," +
                    "decodeUs=3200000,finishReason=STOP,status=COMPLETED,generatedTokens=32",
                "2026-11-20 19:00:00.001 [INFO] [inference] TTFT_METRIC - " +
                    "runId=$runId,pid=701,nativeStep=1,ttftUs=1450000,afterPrefillUs=250000," +
                    "firstPieceChars=1,backend=Vulkan",
                "2026-11-20 19:00:00.002 [NATIVE] LocalLlmEngineNative " +
                    "LOCAL_LLM_INFERENCE_PHASE_TOTALS - runId=$runId,pid=701,backend=Vulkan," +
                    "decodeWallUs=3200000,decodeCalls=32,decodeSubmitUs=90000," +
                    "decodeBackendSyncWaitUs=2800000,vkQueueSubmitUs=7000,vkFenceWaitUs=2400000," +
                    "logitsReadbackMapUs=0,logitsReadbackMapAllocations=0," +
                    "logitsReadbackCopyUs=1200,logitsReadbackSyncUs=900,sampleUs=5000," +
                    "sampleCalls=33,tokensGenerated=32,graphSplits=4,copySyncTimingScope=token_decode",
                "2026-11-20 19:00:00.003 [NATIVE] llama_context: " +
                    "LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION - runId=$runId,graphNodes=120," +
                    "graphSplits=4,cpuNodes=8,vulkanNodes=112,otherNodes=0,unassignedNodes=0"
            )
        )

        assertTrue(report.contains("Inferenz-Phasenmessung"))
        assertTrue(report.contains("ttftUs=1450000"))
        assertTrue(report.contains("LOCAL_LLM_INFERENCE_PHASE_TOTALS"))
        assertTrue(report.contains("vkFenceWaitUs=2400000"))
        assertTrue(report.contains("LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION"))
        assertTrue(report.contains("runId=$runId"))
    }

    @Test
    fun singleRunPhaseSummaryUsesLatestRunAndOnlyItsCorrelatedEvidence() {
        val lines = listOf(
            "2026-11-20 19:00:00.000 [INFO] [inference] INFERENCE_PHASE_METRICS - " +
                "runId=older-run,pid=701,backend=CPU,prefillUs=100,ttftUs=150,decodeUs=200," +
                "finishReason=STOP,status=COMPLETED,generatedTokens=3",
            "2026-11-20 19:00:00.001 [NATIVE] LocalLlmEngineNative LOCAL_LLM_INFERENCE_PHASE_TOTALS - " +
                "runId=older-run,pid=701,backend=CPU,decodeWallUs=200,decodeCalls=3",
            "2026-11-20 19:00:00.002 [NATIVE] llama_context: LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION - " +
                "runId=older-run,graphNodes=10,graphSplits=1,cpuNodes=10,vulkanNodes=0",
            "2026-11-20 19:00:10.000 [INFO] [inference] INFERENCE_PHASE_METRICS - " +
                "runId=latest-run,pid=702,backend=Vulkan,prefillUs=1200000,ttftUs=1450000," +
                "decodeUs=3200000,finishReason=STOP,status=COMPLETED,generatedTokens=32",
            "2026-11-20 19:00:10.001 [NATIVE] LocalLlmEngineNative LOCAL_LLM_INFERENCE_PHASE_TOTALS - " +
                "runId=latest-run,pid=702,backend=Vulkan,promptDecodeUs=1100000," +
                "promptDecodeSubmitUs=900000,promptBackendSyncWaitUs=200000," +
                "decodeWallUs=3200000,decodeCalls=32,decodeSubmitUs=90000," +
                "decodeBackendSyncWaitUs=2800000,vkQueueSubmitUs=7000,vkFenceWaitUs=2400000," +
                "logitsReadbackMapUs=0,logitsReadbackMapAllocations=0,logitsReadbackCopyUs=1200," +
                "logitsReadbackSyncUs=900,sampleUs=5000,sampleCalls=33,tokensGenerated=32,graphSplits=4",
            "2026-11-20 19:00:10.002 [NATIVE] llama_context: LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION - " +
                "runId=latest-run,graphNodes=120,graphSplits=4,cpuNodes=8,vulkanNodes=112," +
                "otherNodes=0,unassignedNodes=0,backendIdsChanged=0,graphSizeChanged=1",
            "2026-11-20 19:00:10.003 [NATIVE] llama_context: LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION - " +
                "runId=unrelated-run,graphNodes=99,graphSplits=2,cpuNodes=99,vulkanNodes=0"
        )

        val report = EngineDiagnostics.buildCompactReportForTest(lines)
        val summary = report.substringAfter("[4a] EINZEL-LAUF – KORRELATION DER INFERENZPHASEN")
            .substringBefore("[5] WICHTIGE EREIGNISSE / FEHLER")

        assertTrue(summary.contains("runId=latest-run pid=702 backend=Vulkan"))
        assertTrue(summary.contains("prefill=1200000us"))
        assertTrue(summary.contains("TTFT=1450000us"))
        assertTrue(summary.contains("decode=3200000us"))
        assertTrue(summary.contains("decodeSyncWait=2800000us"))
        assertTrue(summary.contains("queueSubmit=7000us"))
        assertTrue(summary.contains("fenceWait=2400000us"))
        assertTrue(summary.contains("cpuNodes=8 vulkanNodes=112"))
        assertFalse(summary.contains("older-run"))
        assertFalse(summary.contains("unrelated-run"))
    }

    @Test
    fun singleRunPhaseSummaryMarksMissingAndMismatchedEvidenceUnavailable() {
        val lines = listOf(
            "2026-11-20 19:00:00.000 [INFO] [inference] INFERENCE_PHASE_METRICS - " +
                "runId=partial-run,pid=701,backend=CPU,prefillUs=-1,ttftUs=-1,decodeUs=-1," +
                "finishReason=ERROR,status=FAILED,generatedTokens=0",
            "2026-11-20 19:00:00.001 [NATIVE] LocalLlmEngineNative LOCAL_LLM_INFERENCE_PHASE_TOTALS - " +
                "runId=partial-run,pid=999,backend=CPU,decodeWallUs=123"
        )

        val report = EngineDiagnostics.buildCompactReportForTest(lines)
        val summary = report.substringAfter("[4a] EINZEL-LAUF – KORRELATION DER INFERENZPHASEN")
            .substringBefore("[5] WICHTIGE EREIGNISSE / FEHLER")

        assertTrue(summary.contains("prefill=n/a"))
        assertTrue(summary.contains("TTFT=n/a"))
        assertTrue(summary.contains("decode=n/a"))
        assertTrue(summary.contains("Native totals: UNAVAILABLE (PID mismatch: Kotlin=701, native=999)"))
        assertTrue(summary.contains("Graph distribution: UNAVAILABLE"))
    }

    @Test
    fun singleRunPhaseSummaryExplainsWhenNoInferencePhaseEventExists() {
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(
                "2026-11-20 19:00:00.001 [NATIVE] LocalLlmEngineNative LOCAL_LLM_INFERENCE_PHASE_TOTALS - " +
                    "runId=orphan-run,pid=701,backend=CPU,decodeWallUs=123"
            )
        )
        val summary = report.substringAfter("[4a] EINZEL-LAUF – KORRELATION DER INFERENZPHASEN")
            .substringBefore("[5] WICHTIGE EREIGNISSE / FEHLER")

        assertTrue(summary.contains("Kein INFERENCE_PHASE_METRICS-Ereignis vorhanden"))
    }

    @Test
    fun extractedNativeMetricParserKeepsCurrentAndLegacySyncFieldsDistinct() {
        val currentLine = nativeMetric(
            index = 3, decodeUs = 900L, pid = 42, runId = "current-run", backend = "Vulkan"
        ).replace(
            "backendSyncWaitUs=0",
            "vkQueueSubmitUs=120,vkFenceWaitUs=650,backendSyncWaitUs=770"
        )
        val current = EngineDiagnosticsMetrics.nativeTokenStepMetric(currentLine)
        assertNotNull(current)
        assertEquals("current-run", current!!.runId)
        assertEquals(120L, current.vulkanQueueSubmitUs)
        assertEquals(650L, current.vulkanFenceWaitUs)
        assertEquals(770L, current.backendSyncWaitUs)
        assertFalse(current.usedLegacyGpuSyncWaitLabel)

        val legacyLine = nativeMetric(
            index = 4, decodeUs = 1_100L, pid = 42, runId = "legacy-run", backend = "CPU"
        ).replace("backendSyncWaitUs=0", "gpuSyncWaitUs=275")
        val legacy = EngineDiagnosticsMetrics.nativeTokenStepMetric(legacyLine)
        assertNotNull(legacy)
        assertEquals(275L, legacy!!.backendSyncWaitUs)
        assertTrue(legacy.usedLegacyGpuSyncWaitLabel)

        assertNull(
            EngineDiagnosticsMetrics.nativeTokenStepMetric(
                "2026-11-09 21:00:00.000 [NATIVE] TOKEN_STEP_METRIC - index=1,decodeUs=100"
            )
        )
    }

    @Test
    fun extractedKotlinAndVulkanMetricParsersRejectUncorrelatedOrInvalidRows() {
        val kotlin = EngineDiagnosticsMetrics.kotlinTokenStepMetric(
            kotlinMetric(index = 2, jniWallUs = 1_250L, pid = 73, runId = "kotlin-run", backend = "CPU")
        )
        assertNotNull(kotlin)
        assertEquals("kotlin-run", kotlin!!.runId)
        assertEquals(1_250L, kotlin.jniWallUs)

        val vulkan = EngineDiagnosticsMetrics.vulkanOperationSample(
            vulkanOperationMetric(
                pid = 73,
                runId = "vk-run",
                snapshot = 5,
                operation = "mul_mat_vec_q4_K",
                samples = 3,
                averageUs = 12.5,
                totalUs = 37.5
            ).replace("gflops=n/a", "gflops=0.125")
        )
        assertNotNull(vulkan)
        assertEquals("vk-run", vulkan!!.runId)
        assertEquals("mul_mat_vec_q4_K", vulkan.operation)
        assertEquals(0.125, vulkan.gflops!!, 0.0001)

        assertNull(
            EngineDiagnosticsMetrics.vulkanOperationSample(
                vulkanOperationMetric(73, "vk-run", 5, "broken", 0, -1.0, 0.0)
            )
        )
        assertNull(
            EngineDiagnosticsMetrics.kotlinTokenStepMetric(
                "2026-11-09 21:00:00.000 [NATIVE] TOKEN_STEP_METRIC - runId=native,pid=1"
            )
        )
    }


    @Test
    fun nativeDecodeReportIncludesQueueSubmitAndFenceWaitSeparately() {
        val line = nativeMetric(
            index = 1, decodeUs = 900_000, pid = 42, runId = "split-sync-run", backend = "Vulkan"
        ).replace(
            "backendSyncWaitUs=0",
            "vkQueueSubmitUs=12,vkFenceWaitUs=840000,backendSyncWaitUs=850000"
        )
        val report = EngineDiagnostics.buildCompactReportForTest(listOf(line))

        assertTrue(report.contains("vkQueueSubmit p50=12us"))
        assertTrue(report.contains("vkFenceWait p50=840000us"))
        assertTrue(report.contains("12/840000/850000"))
    }

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
                "device=Mali-G57,intDot=1,quantizeY=1,selected=MMVQ_INTEGER_DOT,reason=intDot_q4k_batch1,yKernelType=q8_1"
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

        // Keep the synthetic report above the new 128 KiB production cap so this
        // test still proves that truncation is applied after the prioritized sections.
        val noisyTokenMetrics = (1..260).flatMap { run ->
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
        assertTrue(report.contains("selected=MMVQ_INTEGER_DOT"))
        assertTrue(report.contains("extensionSupported=0"))
        assertTrue(report.contains("budgetKnown=0"))
        assertTrue(
            report.indexOf("[VULKAN-LAUFZEITBELEG") <
                report.indexOf("[5] WICHTIGE EREIGNISSE")
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
        val opDistribution = "2026-11-12 05:00:01.000 [NATIVE] llama_context: " +
            "LOCAL_LLM_GRAPH_OP_BACKEND_DISTRIBUTION - runId=matrix-q4-run,graphNodes=128," +
            "gdnCpuNodes=1,gdnVulkanNodes=0,gdnOtherNodes=0,gdnUnassignedNodes=0," +
            "ssmScanCpuNodes=0,ssmScanVulkanNodes=6,ssmScanOtherNodes=0,ssmScanUnassignedNodes=0," +
            "ssmConvCpuNodes=0,ssmConvVulkanNodes=6,ssmConvOtherNodes=0,ssmConvUnassignedNodes=0"
        val report = EngineDiagnostics.buildCompactReportForTest(noise + distribution + opDistribution)

        assertTrue(report.contains("LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION"))
        assertTrue(report.contains("LOCAL_LLM_GRAPH_OP_BACKEND_DISTRIBUTION"))
        assertTrue(report.contains("runId=matrix-q4-run"))
        assertTrue(report.contains("graphSplits=3"))
        assertTrue(report.contains("cpuNodes=12"))
        assertTrue(report.contains("vulkanNodes=116"))
        assertTrue(report.contains("gdnCpuNodes=1"))
        assertTrue(report.contains("ssmScanVulkanNodes=6"))
        assertTrue(report.contains("ssmConvVulkanNodes=6"))
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
        // Native fields are rendered as separate readable bullets, not a comma-joined raw field row.
        assertTrue(report.contains("override=AUTO"))
        assertTrue(report.contains("selected=SUBGROUP"))
        assertTrue(report.contains("override=SUBGROUP"))
        assertTrue(report.contains("override=LARGE"))
        assertTrue(report.contains("selected=LARGE"))
        assertTrue(report.contains("subgroupSize=16"))
        assertTrue(report.contains("VULKAN_DMMV_SWEEP_STARTED"))
        assertTrue(report.contains("VULKAN_DMMV_SWEEP_PLAN"))
        // Benchmark event fields are rendered separately in the compact report.
        assertTrue(report.contains("workgroup=AUTO"))
        assertTrue(report.contains("workgroupNativeId=0"))
        assertTrue(report.contains("workgroup=SUBGROUP"))
        assertTrue(report.contains("workgroupNativeId=1"))
        assertTrue(report.contains("workgroup=LARGE"))
        assertTrue(report.contains("workgroupNativeId=2"))
        assertTrue(report.contains("VULKAN_DMMV_SWEEP_RESTORED"))
    }

    @Test
    fun compactReportRetainsQ4kDecodePathSweepAndNativePathTransitions() {
        val mmvqPath =
            "2026-11-11 17:00:00.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - " +
                "device=Mali-G57,intDot=1,quantizeY=1,selected=MMVQ_INTEGER_DOT,reason=intDot_q4k_batch1,yKernelType=q8_1"
        val basePath =
            "2026-11-11 17:01:00.000 [NATIVE] ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - " +
                "device=Mali-G57,intDot=1,quantizeY=0,selected=BASE_TYPE_MMV,reason=explicit_base_override,yKernelType=f32"
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

        assertTrue(report.contains("selected=MMVQ_INTEGER_DOT"))
        assertTrue(report.contains("selected=BASE_TYPE_MMV"))
        assertTrue(report.contains("VULKAN_Q4K_PATH_SWEEP_STARTED"))
        assertTrue(report.contains("VULKAN_Q4K_PATH_SWEEP_PLAN"))
        // Structured event fields are rendered individually in the compact export.
        assertTrue(report.contains("path=MMVQ_INTEGER_DOT"))
        assertTrue(report.contains("pathNativeId=0"))
        assertTrue(report.contains("path=BASE_DMMV"))
        assertTrue(report.contains("pathNativeId=3"))
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

        assertTrue(report.contains("[3] VULKAN-GPU-OPERATIONSPROFIL"))
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
        assertTrue(report.contains("op=mul_mat_vec_q4_K snapshots=3 samples=3"))
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
    fun resourceMemoryPeaksAreCorrelatedToTheirNativeInferenceRunIds() {
        val lines = listOf(
            "2026-11-19 15:00:00.000 [INFO] [memory] RESOURCE_SNAPSHOT - " +
                "pid=701,phase=INFERENCE_cpu-request,processPssMb=100.0,nativePssMb=50.0," +
                "nativeHeapAllocatedMb=40.0,javaHeapUsedMb=10.0,systemAvailableRamMb=1200.0," +
                "thermalStatus=LIGHT",
            "2026-11-19 15:00:01.000 [INFO] [memory] RESOURCE_SNAPSHOT - " +
                "pid=701,phase=INFERENCE_cpu-request,processPssMb=120.0,nativePssMb=60.0," +
                "nativeHeapAllocatedMb=45.0,javaHeapUsedMb=12.0,systemAvailableRamMb=1100.0," +
                "thermalStatus=LIGHT",
            "2026-11-19 15:00:02.000 [INFO] [memory] RESOURCE_RUN_ID_LINK - " +
                "pid=701,diagnosticRunId=cpu-request,nativeRunId=cpu-native",
            "2026-11-19 15:01:00.000 [INFO] [memory] RESOURCE_SNAPSHOT - " +
                "pid=701,phase=INFERENCE_vulkan-request,processPssMb=200.0,nativePssMb=90.0," +
                "nativeHeapAllocatedMb=80.0,javaHeapUsedMb=20.0,systemAvailableRamMb=900.0," +
                "thermalStatus=MODERATE",
            "2026-11-19 15:01:01.000 [INFO] [memory] RESOURCE_SNAPSHOT - " +
                "pid=701,phase=INFERENCE_vulkan-request,processPssMb=230.0,nativePssMb=100.0," +
                "nativeHeapAllocatedMb=95.0,javaHeapUsedMb=24.0,systemAvailableRamMb=850.0," +
                "thermalStatus=MODERATE",
            "2026-11-19 15:01:02.000 [INFO] [memory] RESOURCE_RUN_ID_LINK - " +
                "pid=701,diagnosticRunId=vulkan-request,nativeRunId=vulkan-native"
        )

        val report = EngineDiagnostics.buildCompactReportForTest(lines)

        assertTrue(
            report.contains(
                "701/INFERENCE_cpu-request diagnosticRunId=cpu-request nativeRunId=cpu-native " +
                    "n=2 PSS_peak=120.0MB"
            )
        )
        assertTrue(
            report.contains(
                "701/INFERENCE_vulkan-request diagnosticRunId=vulkan-request nativeRunId=vulkan-native " +
                    "n=2 PSS_peak=230.0MB"
            )
        )
        assertTrue(report.contains("diagnosticRunId=cpu-request nativeRunId=cpu-native"))
        assertTrue(report.contains("diagnosticRunId=vulkan-request nativeRunId=vulkan-native"))
    }

    @Test
    fun nativeDecodeMetricsArePlacedBeforeVerboseRamAggregatesWhenExportMustBeTruncated() {
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
        val ramIndex = report.indexOf("[7] RAM-ZUSAMMENFASSUNG")
        val tokenIndex = report.indexOf("[6] TOKEN-DECODE- UND SYNCHRONISATIONSZEITEN")

        assertTrue(report.contains("MODEL_LOADED_IDLE"))
        assertTrue(ramIndex >= 0)
        assertTrue(tokenIndex >= 0)
        assertTrue(tokenIndex < ramIndex)
        assertTrue(report.contains("PSS_peak=123.4MB"))
        // Even when later RAM groups hit the hard export limit, the current
        // native decode metrics are already present in the export.
        assertTrue(report.contains("pid=850 backend=Vulkan runId=ram-priority-run-50 samples=8"))
    }

    @Test
    fun compactExportRetainsAll48MatrixProfileRowsWithoutLifecycleEventsDisplacingThem() {
        val lines = mutableListOf<String>()
        for (index in 1..48) {
            val profile = "PROFILE_%02d".format(index)
            val timestamp = "2026-11-13 10:00:%02d.000".format(index)
            lines += "$timestamp [INFO] [benchmark] FULL_MATRIX_PROFILE_STARTED - profile=$profile"
            lines += "$timestamp [INFO] [benchmark] CORRECTNESS_GATE_RESULT - " +
                "model=qwen35,profile=$profile,backend=Vulkan,status=PASS," +
                "reason=TOP1_IDENTICAL_TOP5_OVERLAP_GE_4,minimumTop5Overlap=5/5"
            if (index < 48) {
                lines += "$timestamp [INFO] [benchmark] FULL_MATRIX_RESULT - " +
                    "profile=$profile,model=qwen35,backend=Vulkan,gateStatus=PASS," +
                    "decodeTokensPerSecond=2.125,nativeTokensPerSecond=2.100," +
                    "nUbatch=128,flashAttention=DISABLED,gdnPolicy=BOTH_UNFUSED,graphSplits=2"
            } else {
                lines += "$timestamp [INFO] [benchmark] FULL_MATRIX_ROW_INVALID - " +
                    "profile=$profile,model=qwen35,backend=Vulkan,gateStatus=FAIL," +
                    "reason=CPU_TOP1_MISMATCH,tok/s=n/a,gateRunId=failed-gate"
            }
        }

        val report = EngineDiagnostics.buildCompactReportForTest(lines)

        assertTrue(report.contains("PROFILE_01"))
        assertTrue(report.contains("PROFILE_48"))
        assertTrue(report.contains("decodeTokensPerSecond=2.125"))
        assertTrue(report.contains("gateStatus=FAIL"))
        assertTrue(report.contains("tok/s=n/a"))
        assertTrue(report.contains("FULL_MATRIX_PROFILE_STARTED"))
        assertFalse(report.contains("[Export begrenzt: weitere Einträge wurden abgeschnitten]"))
    }


    @Test
    fun compactExportRetainsBothBackendAcceptanceRowsAndLifecycleEvents() {
        val lines = listOf(
            "2026-11-14 02:00:00.000 [INFO] [benchmark] BACKEND_ACCEPTANCE_STARTED - " +
                "model=lfm3,profiles=2,backends=CPU,VULKAN",
            "2026-11-14 02:00:01.000 [INFO] [benchmark] BACKEND_ACCEPTANCE_PROFILE_STARTED - " +
                "index=1,total=2,profile=CPU_REFERENCE,backend=CPU",
            "2026-11-14 02:00:02.000 [INFO] [benchmark] CORRECTNESS_GATE_RESULT - " +
                "profile=CPU_REFERENCE,backend=CPU,gateRunId=cpu-gate,status=PASS",
            "2026-11-14 02:00:03.000 [INFO] [benchmark] BACKEND_ACCEPTANCE_RESULT - " +
                "profile=CPU_REFERENCE,model=lfm3,backend=CPU,gateStatus=PASS," +
                "decodeTokensPerSecond=5.1,diagnosticRunId=cpu-measurement,gateRunId=cpu-gate",
            "2026-11-14 02:00:04.000 [INFO] [benchmark] BACKEND_ACCEPTANCE_PROFILE_STARTED - " +
                "index=2,total=2,profile=VULKAN_CANDIDATE,backend=VULKAN",
            "2026-11-14 02:00:05.000 [INFO] [benchmark] CORRECTNESS_GATE_RESULT - " +
                "profile=VULKAN_CANDIDATE,backend=VULKAN,gateRunId=vulkan-gate," +
                "cpuReferenceRunId=cpu-gate,status=PASS",
            "2026-11-14 02:00:06.000 [INFO] [benchmark] BACKEND_ACCEPTANCE_RESULT - " +
                "profile=VULKAN_CANDIDATE,model=lfm3,backend=VULKAN,gateStatus=PASS," +
                "decodeTokensPerSecond=2.9,diagnosticRunId=vulkan-measurement,gateRunId=vulkan-gate",
            "2026-11-14 02:00:07.000 [INFO] [benchmark] BACKEND_ACCEPTANCE_RESTORED - " +
                "backendPreference=VULKAN,activeBackend=Vulkan"
        )

        val report = EngineDiagnostics.buildCompactReportForTest(lines)

        assertTrue(report.contains("[1] CPU/VULKAN-ABNAHMETEST UND ÄLTERE MATRIX – PROFILERGEBNISSE"))
        assertTrue(report.contains("profile=CPU_REFERENCE"))
        assertTrue(report.contains("decodeTokensPerSecond=5.1"))
        assertTrue(report.contains("diagnosticRunId=cpu-measurement"))
        assertTrue(report.contains("profile=VULKAN_CANDIDATE"))
        assertTrue(report.contains("decodeTokensPerSecond=2.9"))
        assertTrue(report.contains("diagnosticRunId=vulkan-measurement"))
        assertTrue(report.contains("BACKEND_ACCEPTANCE_STARTED"))
        assertTrue(report.contains("BACKEND_ACCEPTANCE_RESTORED"))
        assertTrue(report.contains("BACKEND_ACCEPTANCE_PROFILE_STARTED"))
        assertFalse(report.contains("[Export begrenzt: weitere Einträge wurden abgeschnitten]"))
    }

    @Test
    fun compactReportRetainsRunCorrelatedEffectiveTimeoutPolicy() {
        val line =
            "2026-11-15 00:00:00.000 [INFO] [ipc_client] INFERENCE_TIMEOUT_POLICY - " +
                "request=timeout-run,source=last_loaded_model,serviceBindMs=5000," +
                "modelLoadMs=120000,prefillMs=120000,firstTokenMs=30000," +
                "interTokenMs=15000,totalInferenceMs=300000,abortGracePeriodMs=3000,reconnectMs=10000"

        val report = EngineDiagnostics.buildCompactReportForTest(listOf(line))

        assertTrue(report.contains("INFERENCE_TIMEOUT_POLICY"))
        assertTrue(report.contains("request=timeout-run"))
        assertTrue(report.contains("source=last_loaded_model"))
        assertTrue(report.contains("abortGracePeriodMs=3000"))
    }

    @Test
    fun compactReportRetainsLateTimeoutFieldsFromFullNativeRunContext() {
        // Preserve the production field order: timeout values follow backend, model,
        // context and sampler metadata, beyond the ordinary 16-field compact limit.
        val line =
            "2026-11-18 12:00:00.000 [INFO] [inference] INFERENCE_RUN_CONTEXT - " +
                "runId=native-timeout-run,pid=1234,model=qwen3,modelBytes=2491323904," +
                "modelSha256=unavailable,architecture=qwen3,activeBackend=Vulkan," +
                "backendPreference=VULKAN,contextSize=256,batchSize=128," +
                "microBatchRequested=64,microBatchEffective=64,threads=6,batchThreads=6," +
                "gpuLayersRequested=99,flashAttention=AUTO,vulkanMatvecWorkgroup=AUTO," +
                "gdnPolicy=ARCH_DEFAULT,temperatureDefault=0.7,temperatureOverride=0.0," +
                "topPDefault=0.9,topPOverride=1.0,topK=20,minP=0.05,maxPredictTokens=96," +
                "enableThinking=false,messageCount=1,inputChars=220," +
                "timeoutSource=loaded_model_config,timeoutServiceBindMs=10000," +
                "timeoutModelLoadMs=180000,timeoutPrefillMs=120000," +
                "timeoutFirstTokenMs=120000,timeoutInterTokenMs=30000," +
                "timeoutTotalInferenceMs=600000,timeoutAbortGracePeriodMs=3000," +
                "timeoutReconnectMs=15000"

        val report = EngineDiagnostics.buildCompactReportForTest(listOf(line))

        assertTrue(report.contains("INFERENCE_RUN_CONTEXT"))
        assertTrue(report.contains("runId=native-timeout-run"))
        assertTrue(report.contains("timeoutSource=loaded_model_config"))
        assertTrue(report.contains("Timeout IPC-Bindung (ms): 10000"))
        assertTrue(report.contains("Timeout Modellladen (ms): 180000"))
        assertTrue(report.contains("Timeout Prefill-Stall (ms): 120000"))
        assertTrue(report.contains("Timeout bis erstes Token (ms): 120000"))
        assertTrue(report.contains("Timeout zwischen Tokens (ms): 30000"))
        assertTrue(report.contains("Timeout gesamte Inferenz (ms): 600000"))
        assertTrue(report.contains("Abbruch-Gnadenfrist (ms): 3000"))
        assertTrue(report.contains("Timeout Reconnect (ms): 15000"))
    }

    @Test
    fun compactExportKeepsAllChunksForRecentAssistantOutputsAndReportsSmokeGateFailure() {
        val outputChunks = (1..48).map { part ->
            val suffix = if (part == 1) "FIRST_OUTPUT_CHUNK" else if (part == 48) "LAST_OUTPUT_CHUNK" else "chunk-$part"
            "2026-11-18 12:00:00.000 [INFO] [inference] INFERENCE_OUTPUT_PART_%d_OF_48 - ".format(part) +
                "runId=latest-run,finishReason=MAX_TOKENS,tokensGenerated=48,part=$part/48,text=$suffix"
        }
        val smokeResult =
            "2026-11-18 12:01:00.000 [INFO] [config_test] CONFIG_SMOKE_RESULT - " +
                "profile=vulkan-single-run,finishReason=MAX_TOKENS,exactOutput=false,normalStop=false"
        val report = EngineDiagnostics.buildCompactReportForTest(outputChunks + smokeResult)

        assertTrue(report.contains("FIRST_OUTPUT_CHUNK"))
        assertTrue(report.contains("LAST_OUTPUT_CHUNK"))
        assertTrue(report.indexOf("FIRST_OUTPUT_CHUNK") < report.indexOf("LAST_OUTPUT_CHUNK"))
        assertTrue(report.contains("CONFIG_SMOKE_FAIL=1"))
        assertFalse(report.contains("Keine Korrektheits-Gate-Ergebnisse vorhanden."))
    }

    @Test
    fun compactExportSeparatesUnavailableSmokeBackendFromCorrectnessFailure() {
        val lines = listOf(
            "2026-11-18 12:01:00.000 [INFO] [config_test] CONFIG_SMOKE_RESULT - " +
                "profile=cpu-reference,status=PASS,exactOutput=true,normalStop=true",
            "2026-11-18 12:01:01.000 [INFO] [config_test] CONFIG_SMOKE_RESULT - " +
                "profile=vulkan-candidate,status=UNAVAILABLE,backend=UNAVAILABLE"
        )

        val report = EngineDiagnostics.buildCompactReportForTest(lines)

        assertTrue(report.contains("CONFIG_SMOKE_PASS=1"))
        assertTrue(report.contains("CONFIG_SMOKE_FAIL=0"))
        assertTrue(report.contains("CONFIG_SMOKE_UNAVAILABLE=1"))
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


    @Test
    fun explicitTestTextAndGeneratedAssistantOutputArePersistedAndExported() {
        val testInput = "Copy exactly this text: 322,324,326"
        val expected = "322,324,326"
        val generated = "322,324,326 " + "extra-answer-fragment ".repeat(180)

        EngineDiagnostics.testText(
            tag = "config_test",
            event = "CONFIG_SMOKE_TEST_INPUT",
            details = "prompt=$testInput,expectedOutput=$expected"
        )
        EngineDiagnostics.recordInferenceOutput(
            runId = "diagnostic-output-run",
            text = generated,
            finishReason = "STOP",
            tokensGenerated = 181
        )
        EngineDiagnostics.testText(
            tag = "config_test",
            event = "CONFIG_SMOKE_TEST_OUTPUT",
            details = "runId=diagnostic-output-run,expectedOutput=$expected,text=$generated"
        )

        val logs = EngineDiagnostics.getLogs()
        assertTrue(logs.any { it.contains("CONFIG_SMOKE_TEST_INPUT") && it.contains(testInput) })
        assertTrue(logs.any { it.contains("INFERENCE_OUTPUT") && it.contains("runId=diagnostic-output-run") })
        assertTrue(logs.any { it.contains("INFERENCE_OUTPUT") && it.contains("extra-answer-fragment") })
        val report = EngineDiagnostics.buildCompactReportForTest(logs)
        assertTrue(report.contains("GENERIERTE LLM-AUSGABEN"))
        assertTrue(report.contains("diagnostic-output-run"))
        assertTrue(report.contains("extra-answer-fragment"))
        assertTrue(report.contains("CONFIG_SMOKE_TEST_INPUT"))
        assertTrue(
            report.indexOf("extra-answer-fragment") <
                report.indexOf("[1] CPU/VULKAN-ABNAHMETEST")
        )
    }

    @Test
    fun compactExportKeepsNativeTracesForAllRecentAssistantOutputs() {
        val lines = listOf(
            "2026-11-18 12:00:00.000 [INFO] [inference] INFERENCE_OUTPUT - " +
                "runId=normal-run,finishReason=STOP,tokensGenerated=1,part=1/1,text=incorrect generated answer",
            "2026-11-18 12:00:01.000 [NATIVE] LOCAL_LLM_TEXT_TRACE - " +
                "runId=normal-run,kind=INPUT_MESSAGES,text=original user question",
            "2026-11-18 12:00:02.000 [NATIVE] LOCAL_LLM_TEXT_TRACE - " +
                "runId=normal-run,kind=RENDERED_PROMPT,text=rendered user prompt",
            "2026-11-18 12:01:00.000 [INFO] [inference] INFERENCE_OUTPUT - " +
                "runId=cpu-run,finishReason=STOP,tokensGenerated=1,part=1/1,text=cpu output",
            "2026-11-18 12:01:01.000 [NATIVE] LOCAL_LLM_TEXT_TRACE - " +
                "runId=cpu-run,kind=INPUT_MESSAGES,text=cpu test input",
            "2026-11-18 12:02:00.000 [INFO] [inference] INFERENCE_OUTPUT - " +
                "runId=vulkan-run,finishReason=STOP,tokensGenerated=1,part=1/1,text=vulkan output",
            "2026-11-18 12:02:01.000 [NATIVE] LOCAL_LLM_TEXT_TRACE - " +
                "runId=vulkan-run,kind=INPUT_MESSAGES,text=vulkan test input"
        )

        val report = EngineDiagnostics.buildCompactReportForTest(lines)

        assertTrue(report.contains("incorrect generated answer"))
        assertTrue(report.contains("runId=normal-run,kind=INPUT_MESSAGES"))
        assertTrue(report.contains("original user question"))
        assertTrue(report.contains("runId=normal-run,kind=RENDERED_PROMPT"))
        assertTrue(report.contains("rendered user prompt"))
        assertTrue(report.contains("cpu test input"))
        assertTrue(report.contains("vulkan test input"))
    }

    @Test
    fun compactExportIncludesNativeTextTracesButOmitsUnmarkedBenchmarkText() {
        val lines = listOf(
            "2026-11-13 09:00:00.000 [NATIVE] llama_context: LOCAL_LLM_QWEN35_GDN_POLICY - autoregressive=unfused,chunked=unfused,vulkanGdn=cpu-fallback\\n",
            "2026-11-13 09:00:01.000 [NATIVE] llama_context: LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION - " +
                "runId=gate-run,graphNodes=100,graphSplits=2,cpuNodes=5,vulkanNodes=90,otherNodes=1,unassignedNodes=4,backendIdsChanged=0,graphSizeChanged=1",
            "2026-11-13 09:00:02.000 [INFO] [benchmark] CORRECTNESS_GATE_RESULT - " +
                "model=qwen35,profile=vulkan-auto,backend=Vulkan,status=PASS,reason=TOP1_IDENTICAL_TOP5_OVERLAP_GE_4,minimumTop5Overlap=4/5",
            "2026-11-13 09:00:03.000 [NATIVE] LOCAL_LLM_TEXT_TRACE - runId=gate-run,kind=RENDERED_PROMPT,text=private prompt text",
            "2026-11-13 09:00:04.000 [NATIVE] LOCAL_LLM_TEXT_TRACE - runId=gate-run,kind=TOKEN_SELECTION,selectedPiece=secret-token-piece",
            "2026-11-13 09:00:05.000 [INFO] [benchmark] BACKEND_BENCHMARK_TEST_OUTPUT - backend=Vulkan,text=private generated response"
        )
        val report = EngineDiagnostics.buildCompactReportForTest(lines)
        assertTrue(report.contains("gdnPolicy=autoregressive=unfused,chunked=unfused"))
        assertTrue(report.contains("vulkanGdn=cpu-fallback"))
        assertTrue(report.contains("graphSplits=2"))
        assertTrue(report.contains("cpuNodes=5(5.0%)"))
        assertTrue(report.contains("vulkanNodes=90(90.0%)"))
        assertTrue(report.contains("PASS=1"))
        assertTrue(report.contains("private prompt text"))
        assertTrue(report.contains("secret-token-piece"))
        assertFalse(report.contains("private generated response"))
        assertFalse(report.contains("BACKEND_BENCHMARK_TEST_OUTPUT"))
        assertTrue(report.contains("LOCAL_LLM_TEXT_TRACE"))
        assertTrue(report.contains("Export-Schema: 2"))
        assertTrue(report.contains("textDiagnostics=true"))
        assertTrue(report.contains("Korrektheitsprüfung [CORRECTNESS_GATE_RESULT]"))
        assertTrue(report.contains("• Backend: Vulkan"))
        assertTrue(report.contains("• Status: PASS"))
    }

    @Test
    fun compactExportIncludesOutputGateOutcome() {
        val line = "2026-11-18 14:00:00.000 [INFO] [inference] OUTPUT_GATE_RESULT - " +
            "runId=output-gate-run,stage=FINAL,status=TRUNCATED,reason=MAX_TOKENS," +
            "finishReason=MAX_TOKENS,tokens=12,chars=120"
        val report = EngineDiagnostics.buildCompactReportForTest(listOf(line))
        assertTrue(report.contains("OUTPUT_GATE_TRUNCATED=1"))
        assertTrue(report.contains("Ausgabekorrektheits-Gate [OUTPUT_GATE_RESULT]"))
        assertTrue(report.contains("reason=MAX_TOKENS"))
    }


    @Test
    fun compactExportRetainsAppBuildInfoAndMarksOlderExportsUnavailable() {
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(
                "2026-11-21 16:00:00.000 [INFO] [app] APP_BUILD_INFO - " +
                    "versionName=1.0.652,versionCode=652,buildType=debug," +
                    "applicationId=com.aistudio.localllm.mdoanx.dmmv,debug=true"
            )
        )

        assertTrue(report.contains("appBuildInfo=versionName=1.0.652,versionCode=652,buildType=debug"))
        assertTrue(report.contains("applicationId=com.aistudio.localllm.mdoanx.dmmv"))
        assertTrue(report.contains("App-Build-Identität [APP_BUILD_INFO]"))
        assertTrue(report.contains("App-Version: 1.0.652"))
        assertTrue(report.contains("Build-Code: 652"))
        assertTrue(report.contains("Build-Variante: debug"))

        val legacyReport = EngineDiagnostics.buildCompactReportForTest(
            listOf("2026-11-21 16:00:00.000 [INFO] [ui] TAB_SELECTED - tab=CHAT")
        )
        assertTrue(legacyReport.contains("appBuildInfo=UNAVAILABLE"))
    }

    @Test
    fun compactExportRetainsSmokeThreadRecommendation() {
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(
                "2026-11-21 16:00:00.000 [INFO] [config_test] CONFIG_SMOKE_THREAD_POLICY - " +
                    "availableProcessors=6,recommendedThreads=4,selectedThreads=4,profileCount=3"
            )
        )

        assertTrue(report.contains("CPU-Threadempfehlung des Konfigurationstests"))
        assertTrue(report.contains("availableProcessors=6"))
        assertTrue(report.contains("recommendedThreads=4"))
        assertTrue(report.contains("selectedThreads=4"))
    }

    @Test
    fun compactExportRetainsD0ButtonStateClickAndStartDecision() {
        val report = EngineDiagnostics.buildCompactReportForTest(
            listOf(
                "2026-11-21 16:00:00.000 [INFO] [config_test] CONFIG_SMOKE_D0_BUTTON_STATE - " +
                    "enabled=false,reason=Ein anderer Benchmark läuft noch. D0 startet danach.,modelLoaded=true," +
                    "modelSourceType=SAF_CONTENT_URI,benchmarking=true,configSmokeRunning=false",
                "2026-11-21 16:00:01.000 [INFO] [config_test] CONFIG_SMOKE_D0_BUTTON_CLICKED - " +
                    "modelLoaded=true,modelSourceType=SAF_CONTENT_URI,modelSizeBytes=2491323904",
                "2026-11-21 16:00:01.001 [INFO] [config_test] CONFIG_SMOKE_START_REQUESTED - " +
                    "mode=D0_CPU_REFERENCE_3_RUNS,modelLoaded=true",
                "2026-11-21 16:00:01.002 [INFO] [config_test] CONFIG_SMOKE_START_REJECTED - " +
                    "reason=direct_source_or_size_unavailable"
            )
        )

        assertTrue(report.contains("D0-Button-Zustand und Sperrgrund"))
        assertTrue(report.contains("enabled=false"))
        assertTrue(report.contains("CONFIG_SMOKE_D0_BUTTON_CLICKED"))
        assertTrue(report.contains("Konfigurationstest-Start angefordert"))
        assertTrue(report.contains("Konfigurationstest nicht gestartet"))
    }


    @Test
    fun compactExportRetainsProcessExitAndDisconnectEventsBeyondGeneralEventTail() {
        val processEvents = listOf(
            "2026-11-21 19:05:13.000 [INFO] [ipc_client] SERVICE_DISCONNECTED - " +
                "disconnectedPid=26257,reason=binder_died",
            "2026-11-21 19:05:13.010 [INFO] [ipc_client] PROCESS_EXIT_INFO - " +
                "pid=26257,callback=SERVICE_DISCONNECTED,reason=LOW_MEMORY,description=low memory",
            "2026-11-21 19:05:13.020 [INFO] [ipc_client] SERVICE_RECONNECT_STARTED - " +
                "attempt=1,pid=26257",
            "2026-11-21 19:05:13.030 [INFO] [ipc_client] SERVICE_RECONNECTED - " +
                "attempt=1,pid=28001",
            "2026-11-21 19:05:13.040 [WARN] [inference] INFERENCE_TIMEOUT - " +
                "runId=cpu-run,timeoutSource=FIRST_TOKEN,timeoutMs=600000,pid=26257",
            "2026-11-21 19:05:13.050 [INFO] [ipc_client] TIMEOUT_COOPERATIVE_CANCEL_ESCALATED - " +
                "runId=cpu-run,reason=service_unresponsive,pid=26257"
        )
        val appKillEvents = listOf(
            "2026-11-21 19:05:13.005 [WARN] [ipc_client] HARD_KILL - " +
                "source=client_side_fallback,targetPid=26257",
            "2026-11-21 19:05:13.006 [INFO] [ipc_client] PROCESS_KILL_REQUESTED - " +
                "reason=client_side_fallback,targetPid=26257,processKillRequests=1,cooperativeCancels=1"
        )
        val laterModelEvents = (1..36).map { index ->
            "2026-11-21 19:06:%02d.000 [INFO] [engine] MODEL_LOAD_PROGRESS - ".format(index) +
                "percent=$index,pid=28000"
        }

        val report = EngineDiagnostics.buildCompactReportForTest(processEvents + appKillEvents + laterModelEvents)
        val processSection = report.substringAfter("[5a] PROZESS- UND SERVICE-LEBENSZYKLUS")
            .substringBefore("[6] TOKEN-DECODE- UND SYNCHRONISATIONSZEITEN")

        assertTrue(processSection.contains("SERVICE_DISCONNECTED"))
        assertTrue(processSection.contains("disconnectedPid=26257"))
        assertTrue(processSection.contains("PROCESS_EXIT_INFO"))
        assertTrue(processSection.contains("pid=26257"))
        assertTrue(processSection.contains("reason=LOW_MEMORY"))
        assertTrue(processSection.contains("description=low memory"))
        assertTrue(processSection.contains("SERVICE_RECONNECT_STARTED"))
        assertTrue(processSection.contains("SERVICE_RECONNECTED"))
        assertTrue(processSection.contains("INFERENCE_TIMEOUT"))
        assertTrue(processSection.contains("timeoutSource=FIRST_TOKEN"))
        assertTrue(processSection.contains("TIMEOUT_COOPERATIVE_CANCEL_ESCALATED"))
        assertTrue(processSection.contains("HARD_KILL"))
        assertTrue(processSection.contains("source=client_side_fallback"))
        assertTrue(processSection.contains("PROCESS_KILL_REQUESTED"))
        assertTrue(processSection.contains("targetPid=26257"))
        assertTrue(processSection.contains("processKillRequests=1"))
        assertTrue(processSection.contains("cooperativeCancels=1"))
        assertTrue(report.contains("MODEL_LOAD_PROGRESS"))
    }

    @Test
    fun compactExportBoundsProcessLifecycleEventsAndKeepsNewestEvidence() {
        val processEvents = (1..60).map { index ->
            "2026-11-21 19:07:00.000 [INFO] [ipc_client] PROCESS_EXIT_INFO - " +
                "pid=${1000 + index},reason=REASON_$index"
        }

        val report = EngineDiagnostics.buildCompactReportForTest(processEvents)
        val processSection = report.substringAfter("[5a] PROZESS- UND SERVICE-LEBENSZYKLUS")
            .substringBefore("[6] TOKEN-DECODE- UND SYNCHRONISATIONSZEITEN")
        val retainedExitRows = processSection.lineSequence()
            .filter { it.contains("[PROCESS_EXIT_INFO]") }
            .toList()

        assertEquals(48, retainedExitRows.size)
        assertFalse(processSection.contains("pid=1001"))
        assertTrue(processSection.contains("pid=1060"))
        assertTrue(processSection.contains("reason=REASON_60"))
    }

}
