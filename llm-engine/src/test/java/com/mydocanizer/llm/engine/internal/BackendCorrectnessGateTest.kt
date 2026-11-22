package com.mydocanizer.llm.engine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendCorrectnessGateTest {
    @Test
    fun acceptsMatchingGreedyChoicesWithAtLeastFourTopFiveCandidatesInCommon() {
        val result = BackendCorrectnessGate.evaluate(fixture(), "cpu-run", "vulkan-run")
        assertEquals("PASS", result.status)
        assertEquals("TOP1_IDENTICAL_TOP5_OVERLAP_GE_4", result.reason)
        assertEquals(4, result.comparedSteps)
        assertEquals(4, result.minimumTop5Overlap)
        assertTrue(result.passed)
    }

    @Test
    fun refusesToPassWhenEitherModelSha256IsUnavailable() {
        val lines = fixture().map {
            if (it.contains("runId=vulkan-run,pid=100")) {
                it.replace("modelSha256=" + "a".repeat(64), "modelSha256=unavailable")
            } else it
        }
        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")
        assertEquals("UNAVAILABLE", result.status)
        assertEquals("MODEL_SHA256_UNAVAILABLE_OR_INVALID", result.reason)
    }

    @Test
    fun rejectsDifferentEffectiveMicroBatchSettings() {
        val lines = fixture().map {
            if (it.contains("runId=vulkan-run,pid=100")) {
                it.replace("microBatchEffective=64", "microBatchEffective=128")
            } else it
        }
        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")
        assertEquals("FAIL", result.status)
        assertEquals("MODEL_OR_INFERENCE_CONFIGURATION_MISMATCH:microBatchEffective", result.reason)
    }

    @Test
    fun rejectsDifferentRenderedPromptsBeforeComparingLogits() {
        val lines = fixture().map {
            if (it.contains("runId=vulkan-run,kind=RENDERED_PROMPT")) {
                it.replace("stable rendered prompt", "changed template rendering")
            } else it
        }
        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")
        assertEquals("FAIL", result.status)
        assertEquals("CHAT_TEMPLATE_OR_RENDERED_PROMPT_MISMATCH", result.reason)
    }

    @Test
    fun rejectsDifferentPromptTokenIdsAsTokenizerMismatch() {
        val lines = fixture().map {
            if (it.contains("runId=vulkan-run,kind=PROMPT_TOKEN_IDS")) {
                it.replace("11 22 33 44", "11 22 33 45")
            } else it
        }
        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")
        assertEquals("FAIL", result.status)
        assertEquals("TOKENIZER_TOKEN_ID_MISMATCH", result.reason)
    }

    @Test
    fun rejectsSamplerMismatchBeforeLookingAtBackendLogits() {
        val lines = fixture().map {
            if (it.contains("LOCAL_LLM_SAMPLER_PARAMETERS") && it.contains("runId=vulkan-run")) {
                it.replace("topK=20", "topK=40")
            } else it
        }
        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")
        assertEquals("FAIL", result.status)
        assertEquals("SAMPLER_CONFIGURATION_MISMATCH", result.reason)
    }

    @Test
    fun rejectsReusedPromptCachePrefix() {
        val lines = fixture().map {
            if (it.contains("Prompt cache plan:") && it.contains("runId=vulkan-run")) {
                it.replace("reusedTokens=0", "reusedTokens=3")
                    .replace("evaluatedTokens=4", "evaluatedTokens=1")
            } else it
        }
        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")
        assertEquals("FAIL", result.status)
        assertEquals("VULKAN_PROMPT_CACHE_NOT_FRESH", result.reason)
    }

    @Test
    fun acceptsMissingCacheTraceWhenCpuAndVulkanUseSeparatelyLoadedModelInstances() {
        val lines = fixture().mapNotNull { line ->
            when {
                line.contains("runId=vulkan-run,pid=100") -> line.replace("model=7", "model=8")
                line.contains("Prompt cache plan:") -> null
                else -> line
            }
        }

        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")

        assertEquals("PASS", result.status)
        assertEquals("TOP1_IDENTICAL_TOP5_OVERLAP_GE_4", result.reason)
    }

    @Test
    fun stillRequiresCacheEvidenceWhenBothRunsShareOneModelInstance() {
        val lines = fixture().filterNot { it.contains("Prompt cache plan:") }

        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")

        assertEquals("UNAVAILABLE", result.status)
        assertEquals("CPU_PROMPT_CACHE_EVIDENCE_MISSING_OR_DUPLICATED", result.reason)
    }

    @Test
    fun rejectsTop1MismatchEvenWhenTheCandidateRemainsInsideTheReferenceTopFive() {
        val lines = fixture().map {
            if (it.contains("runId=vulkan-run,backend=Vulkan,step=2")) {
                it.replace("selectedId=20", "selectedId=21")
                    .replace("top5=20:1.0|21:0.8", "top5=21:1.0|20:0.8")
            } else it
        }
        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")
        assertEquals("FAIL", result.status)
        assertEquals("NUMERICALLY_PLAUSIBLE_TOP1_MISMATCH", result.reason)
        assertEquals(2, result.firstDivergenceStep)
        assertFalse(result.passed)
    }

    @Test
    fun classifiesLargeLaterLogitDivergenceAsDecodeStateOrLogitDivergence() {
        val lines = fixture().map {
            if (it.contains("runId=vulkan-run,backend=Vulkan,step=4")) {
                it.replace(
                    "top5=40:1.0|41:0.8|42:0.6|43:0.4|44:0.2",
                    "top5=40:1.0|91:0.8|92:0.6|93:0.4|94:0.2"
                )
            } else it
        }
        val result = BackendCorrectnessGate.evaluate(lines, "cpu-run", "vulkan-run")
        assertEquals("FAIL", result.status)
        assertEquals("DECODE_STATE_OR_LOGIT_DIVERGENCE", result.reason)
        assertEquals(4, result.firstDivergenceStep)
    }

    @Test
    fun failsClosedWhenRequiredTraceEvidenceIsMissingOrMalformed() {
        val missing = fixture().filterNot { it.contains("runId=vulkan-run,kind=PROMPT_TOKEN_IDS") }
        val missingResult = BackendCorrectnessGate.evaluate(missing, "cpu-run", "vulkan-run")
        assertEquals("UNAVAILABLE", missingResult.status)
        assertEquals("VULKAN_PROMPT_TOKEN_IDS_MISSING_OR_INCOMPLETE", missingResult.reason)

        val malformed = fixture().map {
            if (it.contains("runId=cpu-run,backend=CPU,step=1")) {
                it.replace("10:1.0", "10:NaN")
            } else it
        }
        val malformedResult = BackendCorrectnessGate.evaluate(malformed, "cpu-run", "vulkan-run")
        assertEquals("UNAVAILABLE", malformedResult.status)
        assertEquals("CPU_TOKEN_SELECTION_TRACE_MISSING_OR_MALFORMED", malformedResult.reason)
    }

    private fun fixture(): List<String> {
        val lines = mutableListOf<String>()
        val profiles = listOf(
            arrayOf("cpu-run", "CPU", "CPU", "0"),
            arrayOf("vulkan-run", "Vulkan selected", "VULKAN", "99")
        )
        for (profile in profiles) {
            val runId = profile[0]
            val backend = profile[1]
            val preference = profile[2]
            val layers = profile[3]
            lines += "2026-11-18 10:00:00.000 [INFO] [inference] INFERENCE_RUN_CONTEXT - " +
                "runId=" + runId + ",pid=100,model=7,modelBytes=1000,modelSha256=" + "a".repeat(64) + "," +
                "architecture=qwen3,activeBackend=" + backend + ",backendPreference=" + preference +
                ",contextSize=2048,batchSize=128,microBatchRequested=0,microBatchEffective=64," +
                "threads=2,batchThreads=2,gpuLayersRequested=" + layers + ",flashAttention=AUTO," +
                "temperatureDefault=0.7,temperatureOverride=0,topPDefault=0.9,topPOverride=1," +
                "topK=20,minP=0.05,maxPredictTokens=32,enableThinking=false,messageCount=1," +
                "inputChars=21,timeout=default"
            lines += "2026-11-18 10:00:00.010 [NATIVE] LOCAL_LLM_PROMPT_METADATA - " +
                "runId=" + runId + ",architecture=qwen3,modelName=Qwen3,tokenizerModel=gpt2," +
                "modelHybrid=0,modelRecurrent=0,thinkingEnabled=0,explicitChatTemplate=1," +
                "templateMode=JINJA,usedChatTemplate=1,messageCount=1,vocabSize=151936," +
                "addBOS=0,bosId=1,addEOS=0,eosId=2"
            lines += "2026-11-18 10:00:00.020 [NATIVE] LOCAL_LLM_SAMPLER_PARAMETERS - " +
                "runId=" + runId + ",temperature=0,topP=1,topK=20,minP=0.05,samplerChain=GREEDY"
            lines += "2026-11-18 10:00:00.030 [NATIVE] LOCAL_LLM_TEXT_TRACE - " +
                "runId=" + runId + ",kind=INPUT_MESSAGES,part=1/1,text=user message"
            lines += "2026-11-18 10:00:00.040 [NATIVE] LOCAL_LLM_TEXT_TRACE - " +
                "runId=" + runId + ",kind=RENDERED_PROMPT,part=1/1,text=stable rendered prompt"
            lines += "2026-11-18 10:00:00.050 [NATIVE] LOCAL_LLM_TEXT_TRACE - " +
                "runId=" + runId + ",kind=PROMPT_TOKEN_IDS,part=1/1,text=11 22 33 44"
            lines += "2026-11-18 10:00:00.060 [NATIVE] Prompt cache plan: " +
                "runId=" + runId + ",promptTokens=4,reusedTokens=0,evaluatedTokens=4,hybrid=0"
        }
        for (step in 1..4) {
            val selected = step * 10
            val commonTail = selected.toString() + ":1.0|" + (selected + 1) + ":0.8|" +
                (selected + 2) + ":0.6|" + (selected + 3) + ":0.4|" + (selected + 4) + ":0.2"
            lines += "2026-11-18 10:00:01.000 [INFO] [inference] TOKEN_SELECTION_TRACE - " +
                "runId=cpu-run,backend=CPU,step=" + step + ",selectedId=" + selected + ",top5=" + commonTail
            val candidateTail = if (step == 1) {
                selected.toString() + ":1.0|" + (selected + 1) + ":0.75|" +
                    (selected + 2) + ":0.65|" + (selected + 3) + ":0.35|" + (selected + 5) + ":0.1"
            } else commonTail
            lines += "2026-11-18 10:00:01.010 [INFO] [inference] TOKEN_SELECTION_TRACE - " +
                "runId=vulkan-run,backend=Vulkan,step=" + step + ",selectedId=" + selected + ",top5=" + candidateTail
        }
        return lines
    }
}
