#pragma once

namespace local_llm {

enum class q4k_q8_1_fallback_stage {
    none,
    matvec_pipeline,
    quantize_pipeline,
};

inline const char * q4k_q8_1_fallback_stage_name(q4k_q8_1_fallback_stage stage) noexcept {
    switch (stage) {
        case q4k_q8_1_fallback_stage::matvec_pipeline:
            return "matvec_pipeline";
        case q4k_q8_1_fallback_stage::quantize_pipeline:
            return "quantizer_pipeline";
        default:
            return "none";
    }
}

template <typename Pipeline>
struct q4k_pipeline_resolution {
    Pipeline pipeline;
    bool quantize_y;
    bool fallback;
    bool available;
    q4k_q8_1_fallback_stage fallback_stage;
};

// The Q8_1 MMVQ route is usable only when both its Q4_K matvec pipeline and
// its F32->Q8_1 quantizer exist. Otherwise use the ordinary F32 base-DMMV
// pipeline; never report a successful GPU path when that fallback is absent.
template <typename Pipeline>
inline q4k_pipeline_resolution<Pipeline> resolve_q4k_pipeline(
        bool q8_1_requested,
        const Pipeline & q8_1_matvec,
        const Pipeline & q8_1_quantizer,
        const Pipeline & base_matvec) {
    if (!q8_1_requested) {
        return {
            base_matvec,
            false,
            false,
            static_cast<bool>(base_matvec),
            q4k_q8_1_fallback_stage::none,
        };
    }

    if (!static_cast<bool>(q8_1_matvec)) {
        return {
            base_matvec,
            false,
            true,
            static_cast<bool>(base_matvec),
            q4k_q8_1_fallback_stage::matvec_pipeline,
        };
    }

    if (!static_cast<bool>(q8_1_quantizer)) {
        return {
            base_matvec,
            false,
            true,
            static_cast<bool>(base_matvec),
            q4k_q8_1_fallback_stage::quantize_pipeline,
        };
    }

    return {
        q8_1_matvec,
        true,
        false,
        true,
        q4k_q8_1_fallback_stage::none,
    };
}

} // namespace local_llm
