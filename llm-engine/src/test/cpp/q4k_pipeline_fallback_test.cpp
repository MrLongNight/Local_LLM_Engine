#include "local_llm_q4k_pipeline_fallback.h"

#include <cassert>
#include <string>

int main() {
    int q8_1_matvec = 1;
    int q8_1_quantizer = 2;
    int base_matvec = 3;

    const auto mmvq = local_llm::resolve_q4k_pipeline(
        true, &q8_1_matvec, &q8_1_quantizer, &base_matvec);
    assert(mmvq.available);
    assert(mmvq.pipeline == &q8_1_matvec);
    assert(mmvq.quantize_y);
    assert(!mmvq.fallback);
    assert(mmvq.fallback_stage == local_llm::q4k_q8_1_fallback_stage::none);

    const auto missing_matvec = local_llm::resolve_q4k_pipeline(
        true, static_cast<int *>(nullptr), &q8_1_quantizer, &base_matvec);
    assert(missing_matvec.available);
    assert(missing_matvec.pipeline == &base_matvec);
    assert(!missing_matvec.quantize_y);
    assert(missing_matvec.fallback);
    assert(missing_matvec.fallback_stage == local_llm::q4k_q8_1_fallback_stage::matvec_pipeline);

    const auto missing_quantizer = local_llm::resolve_q4k_pipeline(
        true, &q8_1_matvec, static_cast<int *>(nullptr), &base_matvec);
    assert(missing_quantizer.available);
    assert(missing_quantizer.pipeline == &base_matvec);
    assert(!missing_quantizer.quantize_y);
    assert(missing_quantizer.fallback);
    assert(missing_quantizer.fallback_stage == local_llm::q4k_q8_1_fallback_stage::quantize_pipeline);

    const auto no_compatible_pipeline = local_llm::resolve_q4k_pipeline(
        true, static_cast<int *>(nullptr), static_cast<int *>(nullptr), static_cast<int *>(nullptr));
    assert(!no_compatible_pipeline.available);
    assert(no_compatible_pipeline.pipeline == nullptr);
    assert(!no_compatible_pipeline.quantize_y);
    assert(no_compatible_pipeline.fallback);

    const auto auto_base = local_llm::resolve_q4k_pipeline(
        false, static_cast<int *>(nullptr), static_cast<int *>(nullptr), &base_matvec);
    assert(auto_base.available);
    assert(auto_base.pipeline == &base_matvec);
    assert(!auto_base.quantize_y);
    assert(!auto_base.fallback);

    const auto missing_auto_base = local_llm::resolve_q4k_pipeline(
        false, static_cast<int *>(nullptr), static_cast<int *>(nullptr), static_cast<int *>(nullptr));
    assert(!missing_auto_base.available);
    assert(missing_auto_base.pipeline == nullptr);
    assert(!missing_auto_base.fallback);

    assert(std::string(local_llm::q4k_q8_1_fallback_stage_name(
        local_llm::q4k_q8_1_fallback_stage::matvec_pipeline)) == "matvec_pipeline");
    assert(std::string(local_llm::q4k_q8_1_fallback_stage_name(
        local_llm::q4k_q8_1_fallback_stage::quantize_pipeline)) == "quantizer_pipeline");
    return 0;
}
