package com.example.ui

import com.example.ui.diagnostics.Q4kPipelineFallbackPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class Q4kPipelineFallbackPolicyTest {
    @Test
    fun acceptsFallbackOnlyWhenQ8MatvecIsMissingAndF32BaseKernelExecutes() {
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.VALID,
            validate(validPayload()).status
        )
    }

    @Test
    fun acceptsFallbackWhenOnlyTheQ8QuantizerIsMissing() {
        val payload = validPayload()
            .replace("q8_1MatvecAvailable=0", "q8_1MatvecAvailable=1")
            .replace("fallbackStage=matvec_pipeline", "fallbackStage=quantizer_pipeline")
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.VALID,
            validate(payload).status
        )
    }

    @Test
    fun rejectsFallbackWithoutARealBasePipeline() {
        val payload = validPayload()
            .replace("baseMatvecAvailable=1", "baseMatvecAvailable=0")
            .replace("selected=BASE_TYPE_MMV", "selected=UNAVAILABLE")
            .replace(
                "reason=" + Q4kPipelineFallbackPolicy.FALLBACK_REASON,
                "reason=q8_1_path_unavailable_base_pipeline_unavailable"
            )
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.INVALID,
            validate(payload).status
        )
    }

    @Test
    fun rejectsWrongActivationKernelOrStillQuantizedY() {
        val badKernel = validate(validPayload().replace("yKernelType=f32", "yKernelType=f16"))
        val stillQuantized = validate(validPayload().replace("quantizeY=0", "quantizeY=1"))
        val wrongQuantizerState = validate(validPayload().replace("q8_1QuantizeAvailable=0", "q8_1QuantizeAvailable=1"))
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, badKernel.status)
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, stillQuantized.status)
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, wrongQuantizerState.status)
    }

    @Test
    fun rejectsFallbackIfQ8EligibilityOrIntegerDotEvidenceIsContradictory() {
        val noIntegerDot = validate(validPayload().replace("intDot=1", "intDot=0"))
        val ineligible = validate(validPayload().replace("q8_1Eligibility=eligible", "q8_1Eligibility=src1_not_f32"))
        val nonF32Source = validate(validPayload().replace("src1Type=f32", "src1Type=f16"))
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, noIntegerDot.status)
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, ineligible.status)
        assertEquals(Q4kPipelineFallbackPolicy.Status.INVALID, nonF32Source.status)
    }

    @Test
    fun missingOrMalformedFallbackEvidenceIsNotReportedAsValidated() {
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.NOT_APPLICABLE,
            Q4kPipelineFallbackPolicy.validateNativeEvidence(emptyList()).status
        )
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.NOT_APPLICABLE,
            validate("selected=BASE_TYPE_MMV,reason=mali_auto_base_measured_faster").status
        )
        assertEquals(
            Q4kPipelineFallbackPolicy.Status.INVALID,
            validate("selected=BASE_TYPE_MMV").status
        )
    }

    private fun validPayload(): String =
        "intDot=1,q8_1Eligible=1,q8_1Eligibility=eligible,quantizeY=0," +
            "src1Type=f32,yKernelType=f32,batchColumns=1," +
            "q8_1MatvecAvailable=0,q8_1QuantizeAvailable=0,baseMatvecAvailable=1," +
            "fallbackStage=matvec_pipeline,selected=BASE_TYPE_MMV,reason=" +
            Q4kPipelineFallbackPolicy.FALLBACK_REASON

    private fun validate(payload: String): Q4kPipelineFallbackPolicy.Validation =
        Q4kPipelineFallbackPolicy.validateNativeEvidence(
            listOf("ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - runId=test,device=Mali-G57,$payload")
        )
}
