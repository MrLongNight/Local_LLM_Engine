package com.example.ui

import com.example.ui.diagnostics.Q4kPipelineFallbackPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class Q4kPipelineFallbackPolicyTest {
    @Test
    fun acceptsFallbackWhenQ8MatvecIsMissingAndF32BaseKernelExecutes() {
        assertEquals(Q4kPipelineFallbackPolicy.Status.VALID, validate(fallbackPayload()).status)
    }

    @Test
    fun acceptsFallbackWhenOnlyTheQ8QuantizerIsMissing() {
        val payload = fallbackPayload()
            .replace("q8_1MatvecAvailable=0", "q8_1MatvecAvailable=1")
            .replace("fallbackStage=matvec_pipeline", "fallbackStage=quantizer_pipeline")
        assertEquals(Q4kPipelineFallbackPolicy.Status.VALID, validate(payload).status)
    }

    @Test
    fun rejectsFallbackWithoutARealBasePipeline() {
        val payload = fallbackPayload()
            .replace("baseMatvecAvailable=1", "baseMatvecAvailable=0")
            .replace("selected=BASE_TYPE_MMV", "selected=UNAVAILABLE")
            .replace(
                "reason=" + Q4kPipelineFallbackPolicy.FALLBACK_REASON,
                "reason=q8_1_path_unavailable_base_pipeline_unavailable"
            )
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, validate(payload).status)
    }

    @Test
    fun rejectsWrongActivationKernelOrStillQuantizedY() {
        val badKernel = validate(fallbackPayload().replace("yKernelType=f32", "yKernelType=f16"))
        val stillQuantized = validate(fallbackPayload().replace("quantizeY=0", "quantizeY=1"))
        val wrongQuantizerState = validate(fallbackPayload().replace("q8_1QuantizeAvailable=0", "q8_1QuantizeAvailable=1"))
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, badKernel.status)
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, stillQuantized.status)
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, wrongQuantizerState.status)
    }

    @Test
    fun rejectsFallbackIfEligibilityOrIntegerDotEvidenceIsContradictory() {
        val noIntegerDot = validate(fallbackPayload().replace("intDot=1", "intDot=0"))
        val ineligible = validate(fallbackPayload().replace("q8_1Eligibility=eligible", "q8_1Eligibility=src1_not_f32"))
        val nonF32Source = validate(fallbackPayload().replace("src1Type=f32", "src1Type=f16"))
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, noIntegerDot.status)
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, ineligible.status)
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, nonF32Source.status)
    }

    @Test
    fun acceptsExpectedMaliBasePathWithoutCallingItAFallback() {
        val payload =
            "intDot=1,q8_1Eligible=1,q8_1Eligibility=eligible,quantizeY=0," +
                "q8_1MatvecAvailable=0,q8_1QuantizeAvailable=0,baseMatvecAvailable=1," +
                "fallbackStage=none,selected=BASE_TYPE_MMV,reason=mali_auto_base_measured_faster," +
                "src1Type=f32,yKernelType=f32,batchColumns=1"
        val result = validate(payload)
        assertEquals(Q4kPipelineFallbackPolicy.Status.VALID, result.status)
        assertEquals(0, result.fallbackEvents)
    }

    @Test
    fun acceptsOnlyACompleteMMVQPath() {
        val payload =
            "intDot=1,q8_1Eligible=1,q8_1Eligibility=eligible,quantizeY=1," +
                "q8_1MatvecAvailable=1,q8_1QuantizeAvailable=1,baseMatvecAvailable=0," +
                "fallbackStage=none,selected=MMVQ_INTEGER_DOT,reason=explicit_mmvq_override," +
                "src1Type=f32,yKernelType=q8_1,batchColumns=1"
        assertEquals(Q4kPipelineFallbackPolicy.Status.VALID, validate(payload).status)
    }

    @Test
    fun rejectsMissingOrContradictoryPathEvidence() {
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.NOT_APPLICABLE,
            Q4kPipelineFallbackPolicy.validateNativeEvidence(emptyList()).status
        )
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.INVALID,
            validate("selected=BASE_TYPE_MMV").status
        )
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.INVALID,
            validate(fallbackPayload().replace("baseMatvecAvailable=1", "baseMatvecAvailable=0")).status
        )
    }

    private fun fallbackPayload(): String =
        "intDot=1,q8_1Eligible=1,q8_1Eligibility=eligible,quantizeY=0," +
            "q8_1MatvecAvailable=0,q8_1QuantizeAvailable=0,baseMatvecAvailable=1," +
            "fallbackStage=matvec_pipeline,selected=BASE_TYPE_MMV,reason=" +
            Q4kPipelineFallbackPolicy.FALLBACK_REASON +
            ",src1Type=f32,yKernelType=f32,batchColumns=1"

    private fun validate(payload: String): Q4kPipelineFallbackPolicy.Validation =
        Q4kPipelineFallbackPolicy.validateNativeEvidence(
            listOf("ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - runId=test,device=Mali-G57,$payload")
        )
}
