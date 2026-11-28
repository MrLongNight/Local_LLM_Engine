# The pinned upstream tree is kept pristine. For Android ARM64 we compile a
# build-directory copy of ggml-vulkan.cpp with the small loader/extension
# compatibility adjustments required by Android API 24+ and Mali drivers.
function(local_llm_prepare_android_vulkan source_dir)
    # TEMPORARY P1-01d A/B control. The default remains the existing blocking wait.
    if(NOT DEFINED LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY OR
       "${LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY}" STREQUAL "")
        set(LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY "BLOCK")
    endif()
    string(TOUPPER "${LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY}" _local_llm_mali_fence_wait_strategy)
    if(NOT _local_llm_mali_fence_wait_strategy STREQUAL "BLOCK" AND
       NOT _local_llm_mali_fence_wait_strategy STREQUAL "SPIN")
        message(FATAL_ERROR
            "LocalLLM: LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY must be BLOCK or SPIN.")
    endif()
    set(LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY "${_local_llm_mali_fence_wait_strategy}")
    if(LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY STREQUAL "SPIN")
        if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
            message(FATAL_ERROR "LocalLLM: temporary Mali SPIN fence wait is Debug-only.")
        endif()
        if(NOT LOCAL_LLM_VULKAN_PERF_PROFILER_ENABLED)
            message(FATAL_ERROR "LocalLLM: temporary Mali SPIN fence wait requires the Vulkan per-operation profiler.")
        endif()
    endif()
    message(STATUS
        "LocalLLM: TEMPORARY Mali fence wait strategy=${LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY}")

    set(_vulkan_source "${source_dir}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
    if(NOT EXISTS "${_vulkan_source}")
        message(FATAL_ERROR "LocalLLM: upstream ggml-vulkan.cpp is missing: ${_vulkan_source}")
    endif()

    file(READ "${_vulkan_source}" _vulkan_contents)
    string(REGEX MATCHALL "vkGetPhysicalDeviceFeatures2\\(" _feature2_calls "${_vulkan_contents}")
    list(LENGTH _feature2_calls _feature2_call_count)
    if(NOT _feature2_call_count EQUAL 3)
        message(FATAL_ERROR
            "LocalLLM: unexpected upstream Vulkan source; expected 3 vkGetPhysicalDeviceFeatures2 calls, found ${_feature2_call_count}. Review the Android compatibility overlay before updating llama.cpp.")
    endif()

    # The core Vulkan 1.1 function is not exported by every Android API-24
    # Vulkan loader. Resolve it using the Vulkan-Hpp instance dispatcher.
    set(_helper [=[
#include <vulkan/vulkan.hpp>
#include <stdexcept>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <chrono>
#include <cstdint>
#include <unistd.h>

extern "C" int local_llm_vulkan_dmmv_workgroup_override();
extern "C" const char * local_llm_vulkan_profile_run_id();
extern "C" bool local_llm_vulkan_logits_readback_timing_active();
extern "C" void local_llm_vulkan_logits_readback_record(int phase, uint64_t duration_us);
extern "C" void local_llm_vulkan_sync_phase_record(int phase, uint64_t duration_us);

// Emit each selected Q4_K path once per native inference. This keeps the
// diagnostic bounded and avoids allocating per-operation signature strings.
static bool local_llm_should_log_q4k_path(
        const char * run_id, bool is_prefill, bool uses_q8_1) {
    if (run_id == nullptr || run_id[0] == '\0' || std::strcmp(run_id, "none") == 0) {
        return false;
    }
    static std::mutex mutex;
    static std::string active_run_id;
    static bool logged_paths[2][2] = {{ false, false }, { false, false }};
    std::lock_guard<std::mutex> lock(mutex);
    if (active_run_id != run_id) {
        active_run_id = run_id;
        for (int stage = 0; stage < 2; ++stage) {
            for (int path = 0; path < 2; ++path) {
                logged_paths[stage][path] = false;
            }
        }
    }
    bool & logged = logged_paths[is_prefill ? 0 : 1][uses_q8_1 ? 1 : 0];
    if (logged) return false;
    logged = true;
    return true;
}

// LOCAL_LLM_ANDROID_VULKAN_COMPAT: resolve the Vulkan 1.1 promoted command
// through the instance dispatcher; API-24 libvulkan.so may not export it.
static void ggml_vk_get_physical_device_features2(
        VkPhysicalDevice physical_device,
        VkPhysicalDeviceFeatures2* features) {
    const auto function = VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2;
    if (function == nullptr) {
        throw std::runtime_error("Vulkan vkGetPhysicalDeviceFeatures2 entrypoint is unavailable");
    }
    function(physical_device, features);
}
]=])
    set(_include_anchor "#include \"ggml-vulkan-common.h\"")
    string(FIND "${_vulkan_contents}" "${_include_anchor}" _include_position)
    if(_include_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan include anchor changed; Android compatibility overlay needs review.")
    endif()
    string(REPLACE "${_include_anchor}" "${_include_anchor}\n#include \"local_llm_q4k_pipeline_fallback.h\"\n${_helper}" _vulkan_contents "${_vulkan_contents}")
    string(REPLACE "vkGetPhysicalDeviceFeatures2(" "ggml_vk_get_physical_device_features2(" _vulkan_contents "${_vulkan_contents}")

    # Instrument Vulkan queue submission independently from fence completion.
    set(_queue_submit_anchor [=[
    ctx->p->q->handle->submit(submit_infos, fence);

    ctx->seqs.clear();]=])
    string(FIND "${_vulkan_contents}" "${_queue_submit_anchor}" _queue_submit_position)
    if(_queue_submit_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan queue-submit anchor changed.")
    endif()
    set(_queue_submit_replacement [=[
    const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
    const auto local_llm_submit_start = local_llm_sync_timing
        ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
    ctx->p->q->handle->submit(submit_infos, fence);
    if (local_llm_sync_timing) {
        local_llm_vulkan_sync_phase_record(0, static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - local_llm_submit_start).count()));
    }

    ctx->seqs.clear();]=])
    string(REPLACE "${_queue_submit_anchor}" "${_queue_submit_replacement}" _vulkan_contents "${_vulkan_contents}")

    # The empty-sequence submit also reaches the driver and must be counted.
    set(_empty_submit_anchor [=[
        if (fence) {
            ctx->p->q->handle->submit({}, fence);
        }
        return;]=])
    string(FIND "${_vulkan_contents}" "${_empty_submit_anchor}" _empty_submit_position)
    if(_empty_submit_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream empty queue-submit anchor changed.")
    endif()
    set(_empty_submit_replacement [=[
        if (fence) {
            const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
            const auto local_llm_submit_start = local_llm_sync_timing
                ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
            ctx->p->q->handle->submit({}, fence);
            if (local_llm_sync_timing) {
                local_llm_vulkan_sync_phase_record(0, static_cast<uint64_t>(
                    std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now() - local_llm_submit_start).count()));
            }
        }
        return;]=])
    string(REPLACE "${_empty_submit_anchor}" "${_empty_submit_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Diagnostic selectors: AUTO/SUBGROUP/LARGE compare workgroups; modes 3-5
    # select base-type MMV for Q4_K/Q5_K/Q6_K decode; mode 6 selects Q4_K F16 prefill.
    set(_dmmv_anchor [=[
    // heuristic to choose workgroup size
    uint32_t dmmv_wg = DMMV_WG_SIZE_SUBGROUP;
    if ((ctx->device->vendor_id == VK_VENDOR_ID_NVIDIA && ctx->device->architecture != vk_device_architecture::NVIDIA_PRE_TURING) || ctx->device->vendor_id == VK_VENDOR_ID_INTEL) {
        // Prefer larger workgroups when M is small, to spread the work out more
        // and keep more SMs busy.
        // q6_k seems to prefer small workgroup size even for "medium" values of M.
        if (a_type == GGML_TYPE_Q6_K) {
            if (m < 4096 && k >= 1024) {
                dmmv_wg = DMMV_WG_SIZE_LARGE;
            }
        } else {
            if (m <= 8192 && k >= 1024) {
                dmmv_wg = DMMV_WG_SIZE_LARGE;
            }
        }
    }
]=])
    string(FIND "${_vulkan_contents}" "${_dmmv_anchor}" _dmmv_anchor_position)
    if(_dmmv_anchor_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream DMMV workgroup heuristic changed; review the diagnostic override.")
    endif()
    set(_dmmv_replacement [=[
    // heuristic to choose workgroup size
    const int local_llm_dmmv_override = local_llm_vulkan_dmmv_workgroup_override();
    uint32_t dmmv_wg = DMMV_WG_SIZE_SUBGROUP;
    if (local_llm_dmmv_override == 0 &&
        ((ctx->device->vendor_id == VK_VENDOR_ID_NVIDIA && ctx->device->architecture != vk_device_architecture::NVIDIA_PRE_TURING) || ctx->device->vendor_id == VK_VENDOR_ID_INTEL)) {
        // Preserve the pinned llama.cpp vendor heuristic in AUTO mode.
        // q6_k seems to prefer small workgroup size even for "medium" values of M.
        if (a_type == GGML_TYPE_Q6_K) {
            if (m < 4096 && k >= 1024) {
                dmmv_wg = DMMV_WG_SIZE_LARGE;
            }
        } else {
            if (m <= 8192 && k >= 1024) {
                dmmv_wg = DMMV_WG_SIZE_LARGE;
            }
        }
    } else if (local_llm_dmmv_override == 2) {
        dmmv_wg = DMMV_WG_SIZE_LARGE;
    }

    static std::atomic<int> local_llm_last_logged_dmmv_override{-1};
    const int previous_override = local_llm_last_logged_dmmv_override.exchange(
        local_llm_dmmv_override, std::memory_order_relaxed);
    if (previous_override != local_llm_dmmv_override) {
        const char* override_name =
            local_llm_dmmv_override == 0 ? "AUTO" :
            (local_llm_dmmv_override == 1 ? "SUBGROUP" :
             (local_llm_dmmv_override == 2 ? "LARGE" : "Q4K_BASE_DMMV"));
        GGML_LOG_INFO(
            "ggml_vulkan: LOCAL_LLM_VULKAN_DMMV_WG_SELECTED - override=%s,selected=%s,subgroupSize=%u\n",
            override_name,
            dmmv_wg == DMMV_WG_SIZE_LARGE ? "LARGE" : "SUBGROUP",
            ctx->device->subgroup_size);
    }
]=])
    string(REPLACE "${_dmmv_anchor}" "${_dmmv_replacement}" _vulkan_contents "${_vulkan_contents}")

    # The Intel Q8_1 fallback is part of the upstream AUTO heuristic. Do not
    # let it undo an explicit diagnostic override.
    set(_intel_dmmv_anchor [=[
        if (ctx->device->vendor_id == VK_VENDOR_ID_INTEL) {
            dmmv_wg = DMMV_WG_SIZE_SUBGROUP;
        }
]=])
    set(_intel_dmmv_replacement [=[
        if (ctx->device->vendor_id == VK_VENDOR_ID_INTEL && local_llm_dmmv_override == 0) {
            dmmv_wg = DMMV_WG_SIZE_SUBGROUP;
        }
]=])
    string(FIND "${_vulkan_contents}" "${_intel_dmmv_anchor}" _intel_dmmv_position)
    if(_intel_dmmv_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Intel DMMV workgroup fallback changed.")
    endif()
    string(REPLACE "${_intel_dmmv_anchor}" "${_intel_dmmv_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Android/Mali drivers can expose Vulkan 1.2 features while advertising an
    # API version below 1.2. Enable the promoted KHR extension names explicitly.
    set(_bda_anchor "        device->buffer_device_address = vk12_features.bufferDeviceAddress;\n")
    string(FIND "${_vulkan_contents}" "${_bda_anchor}" _bda_position)
    if(_bda_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan buffer-device-address anchor changed; review compatibility overlay.")
    endif()
    set(_bda_replacement [=[
        device->buffer_device_address = vk12_features.bufferDeviceAddress;
        if (device->buffer_device_address &&
            device->properties.apiVersion < VK_API_VERSION_1_2) {
            device_extensions.push_back("VK_KHR_buffer_device_address");
        }
        if (vk12_features.timelineSemaphore &&
            device->properties.apiVersion < VK_API_VERSION_1_2) {
            device_extensions.push_back("VK_KHR_timeline_semaphore");
        }
]=])
    string(REPLACE "${_bda_anchor}" "${_bda_replacement}" _vulkan_contents "${_vulkan_contents}")

    # 16-bit storage is core in Vulkan 1.1. Some Android 1.1+ drivers no longer
    # enumerate the promoted extension and reject it if requested by name.
    set(_storage_anchor "        device_extensions.push_back(\"VK_KHR_16bit_storage\");")
    string(FIND "${_vulkan_contents}" "${_storage_anchor}" _storage_position)
    if(_storage_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan 16-bit-storage extension request changed; review compatibility overlay.")
    endif()
    string(LENGTH "${_storage_anchor}" _storage_anchor_length)
    math(EXPR _storage_after_position "${_storage_position} + ${_storage_anchor_length}")
    string(SUBSTRING "${_vulkan_contents}" ${_storage_after_position} -1 _storage_tail)
    string(FIND "${_storage_tail}" "${_storage_anchor}" _storage_duplicate_position)
    if(NOT _storage_duplicate_position EQUAL -1)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan 16-bit-storage extension request is duplicated; review compatibility overlay.")
    endif()
    set(_storage_replacement [=[
        if (device->properties.apiVersion < VK_API_VERSION_1_1) {
            device_extensions.push_back("VK_KHR_16bit_storage");
        }
]=])
    string(REPLACE "${_storage_anchor}" "${_storage_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Resolve device-level Vulkan-Hpp functions after vkCreateDevice. Instance
    # dispatch alone can leave extension commands unresolved on Android ICDs.
    set(_device_anchor "        device->device = device->physical_device.createDevice(device_create_info);\n")
    string(FIND "${_vulkan_contents}" "${_device_anchor}" _device_position)
    if(_device_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan device creation anchor changed; review compatibility overlay.")
    endif()
    set(_device_replacement [=[
        device->device = device->physical_device.createDevice(device_create_info);
        VULKAN_HPP_DEFAULT_DISPATCHER.init(device->device);
]=])
    string(REPLACE "${_device_anchor}" "${_device_replacement}" _vulkan_contents "${_vulkan_contents}")

    # These anchors are checked against the pinned upstream Vulkan source, then
    # injected only into the generated Android build copy (the submodule remains pristine).
    # Conservative Vulkan fallback for Gated Delta Net:
    # until device parity is demonstrated, let the CPU backend execute this op.
    # The policy is op-scoped; unrelated Vulkan operators retain their normal support.
    set(_gdn_vk_support_anchor [=[
        case GGML_OP_GATED_DELTA_NET:
            {
                const uint32_t S_v = op->src[2]->ne[0];
                if (S_v != 16 && S_v != 32 && S_v != 64 && S_v != 128) {
                    return false;
                }
                for (int i = 0; i < 6; i++) {
                    if (op->src[i] == nullptr || op->src[i]->type != GGML_TYPE_F32) {
                        return false;
                    }
                }
                return op->type == GGML_TYPE_F32;
            }
]=])
    string(FIND "${_vulkan_contents}" "${_gdn_vk_support_anchor}" _gdn_vk_support_position)
    if(_gdn_vk_support_position LESS 0)
        message(FATAL_ERROR "LocalLLM: pinned Vulkan GATED_DELTA_NET supports_op anchor changed; review the CPU fallback.")
    endif()
    set(_gdn_vk_support_replacement [=[
        case GGML_OP_GATED_DELTA_NET:
            // LOCAL_LLM_QWEN35_GDN_CPU_FALLBACK: Vulkan must not claim GDN support.
            return false;
]=])
    string(REPLACE "${_gdn_vk_support_anchor}" "${_gdn_vk_support_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Q4_K prefill F16/F32 selection for a controlled A/B run.
    set(_q4_prefill_type_anchor [=[
    const ggml_type y_kernel_type = ggml_vk_mul_mat_b_type(ctx, src0->type, src1, f16_type,
                                        ggml_prec_get_acc(dst), false, can_quantize_y, prefer_f16_b);]=])
    string(FIND "${_vulkan_contents}" "${_q4_prefill_type_anchor}" _q4_prefill_type_position)
    if(_q4_prefill_type_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Q4_K prefill activation-type anchor changed.")
    endif()
    set(_q4_prefill_type_replacement [=[
    // LOCAL_LLM_Q4K_PREFILL_F16_OVERRIDE: explicitly prefer F16/F32 activation matmul.
    const bool local_llm_force_q4k_prefill_f16 =
        src0->type == GGML_TYPE_Q4_K && ne11 > 1 &&
        local_llm_vulkan_dmmv_workgroup_override() == 6;
    const ggml_type y_kernel_type = local_llm_force_q4k_prefill_f16
        ? f16_type
        : ggml_vk_mul_mat_b_type(ctx, src0->type, src1, f16_type,
                                 ggml_prec_get_acc(dst), false, can_quantize_y, prefer_f16_b);]=])
    string(REPLACE "${_q4_prefill_type_anchor}" "${_q4_prefill_type_replacement}" _vulkan_contents "${_vulkan_contents}")

    set(_q4_prefill_anchor [=[    vk_pipeline pipeline = ggml_vk_guess_matmul_pipeline_map(ctx, *mmp_map, ne01, ne11, aligned, false);]=])
    string(FIND "${_vulkan_contents}" "${_q4_prefill_anchor}" _q4_prefill_position)
    if(_q4_prefill_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Q4_K prefill pipeline anchor changed.")
    endif()
    set(_q4_prefill_replacement [=[    vk_pipeline pipeline = ggml_vk_guess_matmul_pipeline_map(ctx, *mmp_map, ne01, ne11, aligned, false);
    if (src0->type == GGML_TYPE_Q4_K) {
        const char * run_id = local_llm_vulkan_profile_run_id();
        const char * selected_path = quantize_y ? "Q8_1_MATMUL" : "F16_F32_MATMUL";
        if (local_llm_should_log_q4k_path(run_id, true, quantize_y)) {
            GGML_LOG_INFO(
                "ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_PREFILL_PATH - runId=%s,device=%s,intDot=%d,coopMatInt=%d,canQuantizeY=%d,selected=%s,yKernelType=%s,batchTokens=%llu,hidden=%llu,weightRows=%llu\n",
                run_id,
                ctx->device->name.c_str(),
                ctx->device->integer_dot_product ? 1 : 0,
                ctx->device->coopmat_int_support ? 1 : 0,
                can_quantize_y ? 1 : 0,
                selected_path,
                ggml_type_name(y_kernel_type),
                static_cast<unsigned long long>(ne11),
                static_cast<unsigned long long>(ne10),
                static_cast<unsigned long long>(ne01));
        }
    }]=])
    string(REPLACE "${_q4_prefill_anchor}" "${_q4_prefill_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Q4_K batch-1 decode on Mali. Measured on the target Mali-G57 (LFM3 1.7B Q4_K_M,
    # repeated, runtime-verified dispatch): base DMMV 4.14/3.92 tok/s versus Q8_1
    # integer-dot MMVQ 2.71/2.80 tok/s, i.e. MMVQ is 40-48% slower. AUTO on Mali
    # therefore keeps the base DMMV path; MMVQ stays reachable via override 7.
    # Other vendors retain the pinned upstream AUTO heuristic.
    set(_q4k_base_dmmv_anchor [=[
    // Check for mmq first
    vk_pipeline dmmv = quantize_y ? ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, GGML_TYPE_Q8_1, ne11, ne20, ne00) : nullptr;]=])
    string(FIND "${_vulkan_contents}" "${_q4k_base_dmmv_anchor}" _q4k_base_dmmv_position)
    if(_q4k_base_dmmv_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Q4_K MMVQ selection anchor changed.")
    endif()
    set(_q4k_base_dmmv_replacement [=[
    // LOCAL_LLM_Q4K_BASE_DMMV_OVERRIDE: mode 3 explicitly forces base DMMV for Q4_K.
    // LOCAL_LLM_MALI_Q4K_AUTO_BASE_DMMV: Mali AUTO keeps the (measured faster) base path.
    // Modes 3/4/5 expose explicit Q4_K/Q5_K/Q6_K A/B paths; Q5_K/Q6_K logic is unchanged.
    const int local_llm_quant_override = local_llm_vulkan_dmmv_workgroup_override();
    // LOCAL_LLM_Q4K_Q8_1_ELIGIBILITY: preserve upstream Q8_1 input/layout/alignment/precision requirements.
    // Only bypass ggml_vk_should_use_mmvq()'s shape heuristic for this measured Q4_K batch-1 A/B path.
    const bool local_llm_q4k_q8_1_eligible =
        src0->type == GGML_TYPE_Q4_K &&
        ne11 == 1 &&
        src1->type == GGML_TYPE_F32 &&
        ggml_is_contiguous(src1) &&
        !y_non_contig &&
        (ne11 * ne10) % 4 == 0 &&
        (swap_inputs || ggml_vk_src1_prec_allows_q8(dst));
    const char * local_llm_q4k_q8_1_eligibility_reason =
        src0->type != GGML_TYPE_Q4_K ? "not_q4k" :
        ne11 != 1 ? "batch_not_one" :
        src1->type != GGML_TYPE_F32 ? "src1_not_f32" :
        !ggml_is_contiguous(src1) ? "src1_not_contiguous" :
        y_non_contig ? "y_non_contiguous" :
        (ne11 * ne10) % 4 != 0 ? "alignment_not_multiple_of_four" :
        !(swap_inputs || ggml_vk_src1_prec_allows_q8(dst)) ? "precision_disallows_q8" :
        "eligible";
    const bool local_llm_mali_q4k_auto =
        local_llm_quant_override == 0 && ctx->device->vendor_id == 0x13B5;
    // LOCAL_LLM_Q4K_AUTO_VENDOR_SCOPE: only Mali overrides AUTO; other vendors keep upstream quantize_y.
    // Mali AUTO prefers base DMMV: integer-dot MMVQ measured 40-48% slower on Mali-G57.
    // Flip this constant only after a repeated, runtime-verified A/B shows MMVQ winning.
    constexpr bool local_llm_mali_auto_prefers_mmvq = false;
    const bool local_llm_q4k_integer_dot_batch1 =
        local_llm_q4k_q8_1_eligible &&
        ctx->device->integer_dot_product &&
        local_llm_mali_q4k_auto &&
        local_llm_mali_auto_prefers_mmvq;
    const char * local_llm_q4k_decode_reason = "upstream";
    if (local_llm_q4k_integer_dot_batch1) {
        // Expected log evidence: selected=MMVQ_INTEGER_DOT,reason=intDot_q4k_batch1,yKernelType=q8_1.
        quantize_y = true;
        local_llm_q4k_decode_reason = "intDot_q4k_batch1";
    } else if (src0->type == GGML_TYPE_Q4_K && ne11 == 1 &&
               (local_llm_quant_override == 3 || local_llm_mali_q4k_auto)) {
        quantize_y = false;
        local_llm_q4k_decode_reason = local_llm_quant_override == 3
            ? "explicit_base_override"
            : (!ctx->device->integer_dot_product
                ? "mali_auto_base_without_intDot"
                : (local_llm_q4k_q8_1_eligible
                    ? "mali_auto_base_measured_faster"
                    : "mali_auto_base_q8_1_ineligible"));
    } else if (src0->type == GGML_TYPE_Q5_K && ne11 == 1 && local_llm_quant_override == 4) {
        quantize_y = false;
    } else if (src0->type == GGML_TYPE_Q6_K && ne11 == 1 && local_llm_quant_override == 5) {
        quantize_y = false;
    }
    // LOCAL_LLM_VULKAN_MMVQ_FORCE: mode 7 bypasses the upstream shape heuristic
    // only when Q8_1 integer-dot prerequisites are met. Unsupported pipelines
    // still follow the existing null-pipeline fallback to base DMMV.
    const bool local_llm_mmvq_force_requested =
        local_llm_quant_override == 7 &&
        ctx->device->integer_dot_product &&
        src1->type == GGML_TYPE_F32 &&
        ggml_is_contiguous(src1) && !y_non_contig &&
        (ne11 * ne10) % 4 == 0 &&
        (swap_inputs || ggml_vk_src1_prec_allows_q8(dst));
    if (local_llm_mmvq_force_requested) {
        quantize_y = true;
        if (src0->type == GGML_TYPE_Q4_K) {
            local_llm_q4k_decode_reason = "explicit_mmvq_override";
        }
    }
    // Check for mmq first
    vk_pipeline dmmv = quantize_y ? ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, GGML_TYPE_Q8_1, ne11, ne20, ne00) : nullptr;
]=])
    string(REPLACE "${_q4k_base_dmmv_anchor}" "${_q4k_base_dmmv_replacement}" _vulkan_contents "${_vulkan_contents}")
    set(_q4_decode_anchor [=[
    if (dmmv == nullptr) {
        // Fall back to f16 dequant mul mat
        dmmv = ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, y_kernel_type, ne11, ne20, ne00);
        quantize_y = false;
    }

    if (quantize_y) {
        to_q8_1 = ggml_vk_get_quantize_pipeline(ctx, GGML_TYPE_Q8_1);
    }]=])
    string(FIND "${_vulkan_contents}" "${_q4_decode_anchor}" _q4_decode_position)
    if(_q4_decode_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Q4_K decode pipeline anchor changed.")
    endif()
    set(_q4_decode_replacement [=[
    // Reuse upstream's Q8_1 DMMV (dmmv) and quantizer (to_q8_1) declarations.
    bool q8_1_matvec_available = static_cast<bool>(dmmv);
    bool q8_1_quantizer_available = false;
    bool base_matvec_available = false;
    const char * q8_1_fallback_stage = "none";
    if (src0->type == GGML_TYPE_Q4_K) {
        if (quantize_y && dmmv != nullptr) {
            to_q8_1 = ggml_vk_get_quantize_pipeline(ctx, GGML_TYPE_Q8_1);
        }
        q8_1_quantizer_available = static_cast<bool>(to_q8_1);
        vk_pipeline base_dmmv = nullptr;
        if (!quantize_y || dmmv == nullptr || to_q8_1 == nullptr) {
            base_dmmv = ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, y_kernel_type, ne11, ne20, ne00);
        }
        base_matvec_available = static_cast<bool>(base_dmmv);
        const auto q4k_selection = local_llm::resolve_q4k_pipeline(
            quantize_y, dmmv, to_q8_1, base_dmmv);
        dmmv = q4k_selection.pipeline;
        q8_1_fallback_stage = local_llm::q4k_q8_1_fallback_stage_name(q4k_selection.fallback_stage);
        if (q4k_selection.fallback) {
            local_llm_q4k_decode_reason = q4k_selection.available
                ? "q8_1_pipeline_unavailable_fallback"
                : "q8_1_path_unavailable_base_pipeline_unavailable";
        } else if (!q4k_selection.available) {
            local_llm_q4k_decode_reason = "base_pipeline_unavailable";
        }
        quantize_y = q4k_selection.quantize_y;
        if (!quantize_y) {
            to_q8_1 = nullptr;
        }
    } else {
        if (dmmv == nullptr) {
            // Preserve the pinned upstream fallback for all non-Q4_K types.
            dmmv = ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, y_kernel_type, ne11, ne20, ne00);
            quantize_y = false;
        } else if (quantize_y) {
            to_q8_1 = ggml_vk_get_quantize_pipeline(ctx, GGML_TYPE_Q8_1);
        }
    }
    if (src0->type == GGML_TYPE_Q4_K) {
        const char * run_id = local_llm_vulkan_profile_run_id();
        const char * selected_path = dmmv == nullptr
            ? "UNAVAILABLE"
            : (quantize_y ? "MMVQ_INTEGER_DOT" : "BASE_TYPE_MMV");
        if (dmmv == nullptr || local_llm_should_log_q4k_path(run_id, false, quantize_y)) {
            GGML_LOG_INFO(
                "ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - runId=%s,device=%s,intDot=%d,q8_1Eligible=%d,q8_1Eligibility=%s,quantizeY=%d,q8_1MatvecAvailable=%d,q8_1QuantizeAvailable=%d,baseMatvecAvailable=%d,fallbackStage=%s,selected=%s,reason=%s,src1Type=%s,yKernelType=%s,batchColumns=%llu,hidden=%llu,weightRows=%llu\n",
                run_id,
                ctx->device->name.c_str(),
                ctx->device->integer_dot_product ? 1 : 0,
                local_llm_q4k_q8_1_eligible ? 1 : 0,
                local_llm_q4k_q8_1_eligibility_reason,
                quantize_y ? 1 : 0,
                q8_1_matvec_available ? 1 : 0,
                q8_1_quantizer_available ? 1 : 0,
                base_matvec_available ? 1 : 0,
                q8_1_fallback_stage,
                selected_path,
                local_llm_q4k_decode_reason,
                ggml_type_name(src1->type),
                ggml_type_name(quantize_y ? GGML_TYPE_Q8_1 : y_kernel_type),
                static_cast<unsigned long long>(ne11),
                static_cast<unsigned long long>(ne10),
                static_cast<unsigned long long>(ne01));
        }
    }]=])
    string(REPLACE "${_q4_decode_anchor}" "${_q4_decode_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Guard the Q4_K batch-1 selection in the exact upstream decode function.
    # A mere presence check is insufficient: a future upstream change could move
    # the selector outside this function, after pipeline lookup, or after fallback.
    set(_q4k_decode_function_start_anchor "static void ggml_vk_mul_mat_vec_q_f16(")
    set(_q4k_decode_function_end_anchor "static void ggml_vk_mul_mat_vec_id_q_f16(")
    string(FIND "${_vulkan_contents}" "${_q4k_decode_function_start_anchor}" _q4k_decode_function_start)
    string(FIND "${_vulkan_contents}" "${_q4k_decode_function_end_anchor}" _q4k_decode_function_end)
    if(_q4k_decode_function_start LESS 0 OR
       _q4k_decode_function_end LESS 0 OR
       _q4k_decode_function_end LESS_EQUAL _q4k_decode_function_start)
        message(FATAL_ERROR "LocalLLM: Q4_K decode function boundary changed; review the batch-1 overlay.")
    endif()
    math(EXPR _q4k_decode_function_length "${_q4k_decode_function_end} - ${_q4k_decode_function_start}")
    string(SUBSTRING "${_vulkan_contents}" ${_q4k_decode_function_start} ${_q4k_decode_function_length} _q4k_decode_function_contents)
    # Verify the complete Q4_K pipeline decision, not just the fallback marker:
    # - Q8_1 quantization is looked up only when its matvec pipeline exists.
    # - The compatible base matvec is looked up whenever Q8_1 is not usable.
    # - The shared resolver selects MMVQ only when both Q8_1 pipelines exist.
    # - The reason and actual selected path are recorded before the native assertion.
    set(_q4k_quantizer_lookup_anchor [=[
        if (quantize_y && dmmv != nullptr) {
            to_q8_1 = ggml_vk_get_quantize_pipeline(ctx, GGML_TYPE_Q8_1);
        }]=])
    set(_q4k_base_lookup_guard_anchor [=[
        if (!quantize_y || dmmv == nullptr || to_q8_1 == nullptr) {
            base_dmmv = ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, y_kernel_type, ne11, ne20, ne00);
        }]=])
    set(_q4k_resolver_call_anchor [=[
        const auto q4k_selection = local_llm::resolve_q4k_pipeline(
            quantize_y, dmmv, to_q8_1, base_dmmv);]=])
    set(_q4k_fallback_order_anchor [=[
        if (q4k_selection.fallback) {
            local_llm_q4k_decode_reason = q4k_selection.available
                ? "q8_1_pipeline_unavailable_fallback"
                : "q8_1_path_unavailable_base_pipeline_unavailable";
        } else if (!q4k_selection.available) {
            local_llm_q4k_decode_reason = "base_pipeline_unavailable";
        }
        quantize_y = q4k_selection.quantize_y;
        if (!quantize_y) {
            to_q8_1 = nullptr;
        }]=])
    set(_q4k_unavailable_log_anchor [=[
        const char * selected_path = dmmv == nullptr
            ? "UNAVAILABLE"
            : (quantize_y ? "MMVQ_INTEGER_DOT" : "BASE_TYPE_MMV");
        if (dmmv == nullptr || local_llm_should_log_q4k_path(run_id, false, quantize_y)) {]=])

    string(FIND "${_vulkan_contents}" "${_q4k_quantizer_lookup_anchor}" _q4k_quantizer_guard_position)
    string(FIND "${_vulkan_contents}" "${_q4k_base_lookup_guard_anchor}" _q4k_base_guard_position)
    string(FIND "${_vulkan_contents}" "${_q4k_resolver_call_anchor}" _q4k_resolver_guard_position)
    string(FIND "${_vulkan_contents}" "${_q4k_fallback_order_anchor}" _q4k_fallback_guard_position)
    string(FIND "${_vulkan_contents}" "${_q4k_unavailable_log_anchor}" _q4k_unavailable_guard_position)
    if(_q4k_quantizer_guard_position LESS 0 OR
       _q4k_base_guard_position LESS 0 OR
       _q4k_resolver_guard_position LESS 0 OR
       _q4k_fallback_guard_position LESS 0 OR
       _q4k_unavailable_guard_position LESS 0)
        message(FATAL_ERROR "LocalLLM: Q4_K pipeline resolution guard missing.")
    endif()

    string(FIND "${_q4k_decode_function_contents}" "${_q4k_quantizer_lookup_anchor}" _q4k_quantizer_lookup_position)
    string(FIND "${_q4k_decode_function_contents}" "${_q4k_base_lookup_guard_anchor}" _q4k_base_lookup_guard_position)
    string(FIND "${_q4k_decode_function_contents}" "${_q4k_resolver_call_anchor}" _q4k_resolver_call_position)
    string(FIND "${_q4k_decode_function_contents}" "${_q4k_fallback_order_anchor}" _q4k_fallback_order_position)
    string(FIND "${_q4k_decode_function_contents}" "${_q4k_unavailable_log_anchor}" _q4k_unavailable_log_position)
    string(FIND "${_q4k_decode_function_contents}" "GGML_ASSERT(dmmv != nullptr);" _q4k_pipeline_assert_position)
    if(_q4k_quantizer_lookup_position LESS 0 OR
       _q4k_base_lookup_guard_position LESS 0 OR
       _q4k_resolver_call_position LESS 0 OR
       _q4k_fallback_order_position LESS 0 OR
       _q4k_unavailable_log_position LESS 0 OR
       _q4k_pipeline_assert_position LESS 0)
        message(FATAL_ERROR "LocalLLM: Q4_K quantizer/base lookup, resolver, unavailable log or native assertion is missing.")
    endif()
    if(_q4k_q8_pipeline_position GREATER_EQUAL _q4k_quantizer_lookup_position OR
       _q4k_quantizer_lookup_position GREATER_EQUAL _q4k_base_lookup_guard_position OR
       _q4k_base_lookup_guard_position GREATER_EQUAL _q4k_base_f32_pipeline_position OR
       _q4k_base_f32_pipeline_position GREATER_EQUAL _q4k_resolver_call_position OR
       _q4k_resolver_call_position GREATER_EQUAL _q4k_fallback_order_position OR
       _q4k_fallback_order_position GREATER_EQUAL _q4k_selected_log_position OR
       _q4k_selected_log_position GREATER_EQUAL _q4k_unavailable_log_position OR
       _q4k_unavailable_log_position GREATER_EQUAL _q4k_pipeline_assert_position)
        message(FATAL_ERROR "LocalLLM: Q4_K quantization/fallback/log/assert order changed; refusing an unverified Vulkan path.")
    endif()


    # Track the shared logits readback: map is measured only when the persistent
    # HOST_VISIBLE|HOST_COHERENT staging buffer is allocated; copy/sync are gated
    # to token decode so prompt-prefill execution is not timed or changed.
    set(_logits_readback_anchor [=[
        const size_t staging_size = size * n_copies;
        ggml_vk_ensure_sync_staging_buffer(ctx, staging_size);
        ggml_vk_sync_buffers(nullptr, compute_ctx);

        std::vector<vk::BufferCopy> slices(1);
        if (size == stride_tensor) {
            slices[0].srcOffset = src_offset;
            slices[0].dstOffset = 0;
            slices[0].size = staging_size;
        } else {
            slices.resize(n_copies);
            for (size_t i = 0; i < n_copies; i++) {
                slices[i].srcOffset = src_offset + i * stride_tensor;
                slices[i].dstOffset = i * size;
                slices[i].size = size;
            }
        }

        compute_ctx->s->buffer->buf.copyBuffer(buf->buffer, ctx->sync_staging->buffer, slices);

        if (size == stride_data) {
            deferred_memcpy(data, ctx->sync_staging->ptr, staging_size, &compute_ctx->out_memcpys);
        } else {
            for (size_t i = 0; i < n_copies; i++) {
                deferred_memcpy((uint8_t *)data + i * stride_data, (const uint8_t *)ctx->sync_staging->ptr + i * size, size, &compute_ctx->out_memcpys);
            }
        }
        ggml_vk_synchronize(ctx);
    }]=])
    string(FIND "${_vulkan_contents}" "${_logits_readback_anchor}" _logits_readback_position)
    if(_logits_readback_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan logits-readback anchor changed.")
    endif()
    set(_logits_readback_replacement [=[
        const size_t staging_size = size * n_copies;
        const bool local_llm_readback_timing = local_llm_vulkan_logits_readback_timing_active();
        const bool local_llm_staging_needs_map =
            ctx->sync_staging == nullptr || ctx->sync_staging->size < staging_size;
        const auto local_llm_map_start = local_llm_staging_needs_map
            ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        ggml_vk_ensure_sync_staging_buffer(ctx, staging_size);
        if (local_llm_staging_needs_map) {
            // ggml_vk_create_buffer maps HOST_VISIBLE allocations once; the
            // persistent vk_buffer::ptr is reused by subsequent token readbacks.
            GGML_ASSERT(ctx->sync_staging != nullptr);
            GGML_ASSERT(ctx->sync_staging->memory_property_flags & vk::MemoryPropertyFlagBits::eHostVisible);
            GGML_ASSERT(ctx->sync_staging->memory_property_flags & vk::MemoryPropertyFlagBits::eHostCoherent);
            GGML_ASSERT(ctx->sync_staging->ptr != nullptr);
            const uint64_t local_llm_map_us = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - local_llm_map_start).count());
            local_llm_vulkan_logits_readback_record(0, local_llm_map_us);
        } else if (local_llm_readback_timing) {
            // Cached mapped buffer: no vkMapMemory call is performed for this token.
            GGML_ASSERT(ctx->sync_staging != nullptr);
            GGML_ASSERT(ctx->sync_staging->memory_property_flags & vk::MemoryPropertyFlagBits::eHostVisible);
            GGML_ASSERT(ctx->sync_staging->memory_property_flags & vk::MemoryPropertyFlagBits::eHostCoherent);
            GGML_ASSERT(ctx->sync_staging->ptr != nullptr);
        }

        uint64_t local_llm_sync_us = 0;
        auto local_llm_sync_start = local_llm_readback_timing
            ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        ggml_vk_sync_buffers(nullptr, compute_ctx);
        if (local_llm_readback_timing) {
            local_llm_sync_us += static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - local_llm_sync_start).count());
        }

        std::vector<vk::BufferCopy> slices(1);
        if (size == stride_tensor) {
            slices[0].srcOffset = src_offset;
            slices[0].dstOffset = 0;
            slices[0].size = staging_size;
        } else {
            slices.resize(n_copies);
            for (size_t i = 0; i < n_copies; i++) {
                slices[i].srcOffset = src_offset + i * stride_tensor;
                slices[i].dstOffset = i * size;
                slices[i].size = size;
            }
        }

        const auto local_llm_copy_start = local_llm_readback_timing
            ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        compute_ctx->s->buffer->buf.copyBuffer(buf->buffer, ctx->sync_staging->buffer, slices);
        if (local_llm_readback_timing) {
            const uint64_t local_llm_copy_us = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - local_llm_copy_start).count());
            local_llm_vulkan_logits_readback_record(1, local_llm_copy_us);
        }

        if (size == stride_data) {
            deferred_memcpy(data, ctx->sync_staging->ptr, staging_size, &compute_ctx->out_memcpys);
        } else {
            for (size_t i = 0; i < n_copies; i++) {
                deferred_memcpy((uint8_t *)data + i * stride_data, (const uint8_t *)ctx->sync_staging->ptr + i * size, size, &compute_ctx->out_memcpys);
            }
        }
        local_llm_sync_start = local_llm_readback_timing
            ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        ggml_vk_synchronize(ctx);
        if (local_llm_readback_timing) {
            local_llm_sync_us += static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - local_llm_sync_start).count());
            local_llm_vulkan_logits_readback_record(2, local_llm_sync_us);
        }
    }]=])
    string(REPLACE "${_logits_readback_anchor}" "${_logits_readback_replacement}" _vulkan_contents "${_vulkan_contents}")
    set(_memory_properties_anchor [=[
    vkdev.getMemoryProperties2(&memprops);

    *total = 0;
    *free = 0;]=])
    string(FIND "${_vulkan_contents}" "${_memory_properties_anchor}" _memory_properties_position)
    if(_memory_properties_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan memory-properties anchor changed.")
    endif()
    set(_memory_properties_replacement [=[
    vkdev.getMemoryProperties2(&memprops);

    GGML_LOG_INFO(
        "ggml_vulkan: LOCAL_LLM_MEMORY_BUDGET - device=%d,name=%s,integrated=%d,extensionSupported=%d,heapCount=%u\n",
        device,
        vkdev.getProperties().deviceName.data(),
        is_integrated_gpu ? 1 : 0,
        membudget_supported ? 1 : 0,
        memprops.memoryProperties.memoryHeapCount);

    *total = 0;
    *free = 0;]=])
    string(REPLACE "${_memory_properties_anchor}" "${_memory_properties_replacement}" _vulkan_contents "${_vulkan_contents}")

    set(_memory_heap_anchor [=[
            if (membudget_supported && i < budgetprops.heapUsage.size()) {
                // Driver may report heapBudget > heap.size so we add a safeguard here
                // See https://github.com/ggml-org/llama.cpp/issues/29277
                const vk::DeviceSize budget = std::min(budgetprops.heapBudget[i], heap.size);
                *free += budget > budgetprops.heapUsage[i] ? budget - budgetprops.heapUsage[i] : 0;
            } else {
                *free += heap.size;
            }]=])
    string(FIND "${_vulkan_contents}" "${_memory_heap_anchor}" _memory_heap_position)
    if(_memory_heap_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan heap-budget branch changed.")
    endif()
    set(_memory_heap_replacement [=[
            if (membudget_supported && i < budgetprops.heapUsage.size()) {
                // Driver may report heapBudget > heap.size so we add a safeguard here
                // See https://github.com/ggml-org/llama.cpp/issues/29277
                const vk::DeviceSize budget = std::min(budgetprops.heapBudget[i], heap.size);
                const vk::DeviceSize usage = budgetprops.heapUsage[i];
                const vk::DeviceSize available = budget > usage ? budget - usage : 0;
                GGML_LOG_INFO(
                    "ggml_vulkan: LOCAL_LLM_MEMORY_HEAP - device=%d,heapIndex=%u,budgetKnown=1,heapSizeBytes=%llu,budgetBytes=%llu,usageBytes=%llu,freeBytes=%llu\n",
                    device, i,
                    static_cast<unsigned long long>(heap.size),
                    static_cast<unsigned long long>(budget),
                    static_cast<unsigned long long>(usage),
                    static_cast<unsigned long long>(available));
                *free += available;
            } else {
                GGML_LOG_INFO(
                    "ggml_vulkan: LOCAL_LLM_MEMORY_HEAP - device=%d,heapIndex=%u,budgetKnown=0,heapSizeBytes=%llu,freeEstimateBytes=%llu\n",
                    device, i,
                    static_cast<unsigned long long>(heap.size),
                    static_cast<unsigned long long>(heap.size));
                *free += heap.size;
            }]=])
    string(REPLACE "${_memory_heap_anchor}" "${_memory_heap_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Mali-G57 fence wait: avoid spinning on getFenceStatus for the full GPU
    # execution interval. Blocking on the same fence preserves completion ordering
    # while allowing Android CPU/driver work to be scheduled.
    set(_mali_fence_wait_anchor [=[
    // Spin (w/pause) waiting for the graph to finish executing.
    vk::Result result;
    for (;;) {
        try {
            result = ctx->device->device.getFenceStatus(ctx->fence);
        } catch (vk::DeviceLostError &) {
            ggml_vk_print_device_lost_info(ctx->device);
            GGML_LOG_ERROR("ggml_vulkan: getFenceStatus at %s:%d\n", __FILE__, __LINE__);
            throw;
        }
        if (result == vk::Result::eSuccess) {
            break;
        }
        if (result != vk::Result::eNotReady) {
            GGML_LOG_ERROR("ggml_vulkan: error %s at %s:%d\n", to_string(result).c_str(), __FILE__, __LINE__);
            throw vk::SystemError(vk::make_error_code(result), "ggml_vulkan: getFenceStatus");
        }
        for (uint32_t i = 0; i < 100; ++i) {
            YIELD();
            YIELD();
            YIELD();
            YIELD();
            YIELD();
            YIELD();
            YIELD();
            YIELD();
            YIELD();
            YIELD();
        }
    }
    ctx->device->device.resetFences({ ctx->fence });
]=])
    string(FIND "${_vulkan_contents}" "${_mali_fence_wait_anchor}" _mali_fence_wait_position)
    if(_mali_fence_wait_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan fence-wait anchor changed; review the Mali wait strategy.")
    endif()
    if(LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY STREQUAL "SPIN")
        set(_mali_fence_wait_mode "SPIN_POLL")
        set(_mali_fence_wait_strategy_impl [=[
        // LOCAL_LLM_MALI_FENCE_WAIT_SELECTED_SPIN
        local_llm_poll_fence = true;
]=])
    else()
        set(_mali_fence_wait_mode "BLOCK")
        set(_mali_fence_wait_strategy_impl [=[
        // LOCAL_LLM_MALI_FENCE_WAIT_SELECTED_BLOCK
        GGML_LOG_INFO(
            "ggml_vulkan: LOCAL_LLM_MALI_BLOCKING_FENCE_WAIT - device=%s,mode=waitForFences\n",
            ctx->device->name.c_str());
        VK_CHECK(
            ctx->device->device.waitForFences({ ctx->fence }, true, UINT64_MAX),
            "Mali blocking fence wait",
            ctx->device);
]=])
    endif()

    set(_mali_fence_wait_replacement [=[
    const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
    const auto local_llm_fence_wait_start = local_llm_sync_timing
        ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
    // LOCAL_LLM_MALI_FENCE_WAIT_TEMPORARY_AB
    bool local_llm_poll_fence = false;
    if (ctx->device->vendor_id == 0x13B5) {
        static std::atomic<bool> local_llm_mali_fence_wait_logged{false};
        if (!local_llm_mali_fence_wait_logged.exchange(true, std::memory_order_relaxed)) {
            GGML_LOG_INFO(
                "ggml_vulkan: LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY - device=%s,mode=@LOCAL_LLM_MALI_FENCE_WAIT_MODE@\n",
                ctx->device->name.c_str());
        }
        @LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY_IMPL@
    } else {
        // Preserve upstream low-latency polling for every non-Mali GPU.
        local_llm_poll_fence = true;
    }
    if (local_llm_poll_fence) {
        vk::Result result;
        for (;;) {
            try {
                result = ctx->device->device.getFenceStatus(ctx->fence);
            } catch (vk::DeviceLostError &) {
                ggml_vk_print_device_lost_info(ctx->device);
                GGML_LOG_ERROR("ggml_vulkan: getFenceStatus at %s:%d\n", __FILE__, __LINE__);
                throw;
            }
            if (result == vk::Result::eSuccess) {
                break;
            }
            if (result != vk::Result::eNotReady) {
                GGML_LOG_ERROR("ggml_vulkan: error %s at %s:%d\n", to_string(result).c_str(), __FILE__, __LINE__);
                throw vk::SystemError(vk::make_error_code(result), "ggml_vulkan: getFenceStatus");
            }
            for (uint32_t i = 0; i < 100; ++i) {
                YIELD();
                YIELD();
                YIELD();
                YIELD();
                YIELD();
                YIELD();
                YIELD();
                YIELD();
                YIELD();
                YIELD();
            }
        }
    }
    ctx->device->device.resetFences({ ctx->fence });
    if (local_llm_sync_timing) {
        local_llm_vulkan_sync_phase_record(1, static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - local_llm_fence_wait_start).count()));
    }
]=])
    string(REPLACE "@LOCAL_LLM_MALI_FENCE_WAIT_MODE@" "${_mali_fence_wait_mode}"
        _mali_fence_wait_replacement "${_mali_fence_wait_replacement}")
    string(REPLACE "@LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY_IMPL@" "${_mali_fence_wait_strategy_impl}"
        _mali_fence_wait_replacement "${_mali_fence_wait_replacement}")
    string(REPLACE "${_mali_fence_wait_anchor}" "${_mali_fence_wait_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Measure the serialized-submission fence wait too; this path bypasses
    # ggml_vk_wait_for_fence() and otherwise appears as unexplained sync time.
    set(_serialized_fence_wait_anchor [=[
            ggml_vk_submit(compute_ctx, ctx->fence);
            VK_CHECK(ctx->device->device.waitForFences({ ctx->fence }, true, UINT64_MAX), "synchronize waitForFences", ctx->device);
            ctx->device->device.resetFences({ ctx->fence });]=])
    string(FIND "${_vulkan_contents}" "${_serialized_fence_wait_anchor}" _serialized_fence_wait_position)
    if(_serialized_fence_wait_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream serialized Vulkan fence-wait anchor changed.")
    endif()
    set(_serialized_fence_wait_replacement [=[
            ggml_vk_submit(compute_ctx, ctx->fence);
            const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
            const auto local_llm_fence_wait_start = local_llm_sync_timing
                ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
            VK_CHECK(ctx->device->device.waitForFences({ ctx->fence }, true, UINT64_MAX), "synchronize waitForFences", ctx->device);
            if (local_llm_sync_timing) {
                local_llm_vulkan_sync_phase_record(1, static_cast<uint64_t>(
                    std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now() - local_llm_fence_wait_start).count()));
            }
            ctx->device->device.resetFences({ ctx->fence });]=])
    string(REPLACE "${_serialized_fence_wait_anchor}" "${_serialized_fence_wait_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Additional upstream synchronization paths that bypass ggml_vk_submit()
    # or the main graph fence. Keep these anchors explicit and fail-fast.
    set(_almost_ready_wait_anchor [=[
    if (ctx->almost_ready_fence_pending) {
        VK_CHECK(ctx->device->device.waitForFences({ ctx->almost_ready_fence }, true, UINT64_MAX), "almost_ready_fence", ctx->device);
        ctx->device->device.resetFences({ ctx->almost_ready_fence });
        ctx->almost_ready_fence_pending = false;
    }]=])
    string(FIND "${_vulkan_contents}" "${_almost_ready_wait_anchor}" _almost_ready_wait_position)
    if(_almost_ready_wait_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream almost-ready Vulkan fence-wait anchor changed.")
    endif()
    set(_almost_ready_wait_replacement [=[
    if (ctx->almost_ready_fence_pending) {
        const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
        const auto local_llm_almost_ready_wait_start = local_llm_sync_timing
            ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        // LOCAL_LLM_SYNC_ALMOST_READY_WAIT
        VK_CHECK(ctx->device->device.waitForFences({ ctx->almost_ready_fence }, true, UINT64_MAX), "almost_ready_fence", ctx->device);
        if (local_llm_sync_timing) {
            local_llm_vulkan_sync_phase_record(1, static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - local_llm_almost_ready_wait_start).count()));
        }
        ctx->device->device.resetFences({ ctx->almost_ready_fence });
        ctx->almost_ready_fence_pending = false;
    }]=])
    string(REPLACE "${_almost_ready_wait_anchor}" "${_almost_ready_wait_replacement}" _vulkan_contents "${_vulkan_contents}")

    set(_serialized_graph_wait_anchor [=[
                auto res = ctx->device->device.waitForFences({ ctx->fence }, true, UINT64_MAX);
                if (res != vk::Result::eSuccess) {]=])
    string(FIND "${_vulkan_contents}" "${_serialized_graph_wait_anchor}" _serialized_graph_wait_position)
    if(_serialized_graph_wait_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream serialized graph-split wait anchor changed.")
    endif()
    set(_serialized_graph_wait_replacement [=[
                const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
                const auto local_llm_graph_wait_start = local_llm_sync_timing
                    ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
                // LOCAL_LLM_SYNC_SERIALIZED_GRAPH_WAIT
                auto res = ctx->device->device.waitForFences({ ctx->fence }, true, UINT64_MAX);
                if (local_llm_sync_timing) {
                    local_llm_vulkan_sync_phase_record(1, static_cast<uint64_t>(
                        std::chrono::duration_cast<std::chrono::microseconds>(
                            std::chrono::steady_clock::now() - local_llm_graph_wait_start).count()));
                }
                if (res != vk::Result::eSuccess) {]=])
    string(REPLACE "${_serialized_graph_wait_anchor}" "${_serialized_graph_wait_replacement}" _vulkan_contents "${_vulkan_contents}")

    set(_direct_compute_submit_anchor [=[
            si.setPNext(&tl_info);
            ctx->device->compute_queue->handle->submit({ si }, ctx->fence);
            ctx->transfer_semaphore_last_submitted = ctx->transfer_semaphore.value;]=])
    string(FIND "${_vulkan_contents}" "${_direct_compute_submit_anchor}" _direct_compute_submit_position)
    if(_direct_compute_submit_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream direct compute-queue submit anchor changed.")
    endif()
    set(_direct_compute_submit_replacement [=[
            si.setPNext(&tl_info);
            const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
            const auto local_llm_direct_submit_start = local_llm_sync_timing
                ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
            // LOCAL_LLM_SYNC_DIRECT_COMPUTE_QUEUE_SUBMIT
            ctx->device->compute_queue->handle->submit({ si }, ctx->fence);
            if (local_llm_sync_timing) {
                local_llm_vulkan_sync_phase_record(0, static_cast<uint64_t>(
                    std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now() - local_llm_direct_submit_start).count()));
            }
            ctx->transfer_semaphore_last_submitted = ctx->transfer_semaphore.value;]=])
    string(REPLACE "${_direct_compute_submit_anchor}" "${_direct_compute_submit_replacement}" _vulkan_contents "${_vulkan_contents}")

    set(_direct_empty_compute_submit_anchor [=[
        } else {
            ctx->device->compute_queue->handle->submit({}, ctx->fence);
        }
        if (!ctx->device->serialize_submissions) {]=])
    string(FIND "${_vulkan_contents}" "${_direct_empty_compute_submit_anchor}" _direct_empty_compute_submit_position)
    if(_direct_empty_compute_submit_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream direct empty compute-queue submit anchor changed.")
    endif()
    set(_direct_empty_compute_submit_replacement [=[
        } else {
            const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
            const auto local_llm_direct_submit_start = local_llm_sync_timing
                ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
            // LOCAL_LLM_SYNC_DIRECT_COMPUTE_QUEUE_SUBMIT
            ctx->device->compute_queue->handle->submit({}, ctx->fence);
            if (local_llm_sync_timing) {
                local_llm_vulkan_sync_phase_record(0, static_cast<uint64_t>(
                    std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now() - local_llm_direct_submit_start).count()));
            }
        }
        if (!ctx->device->serialize_submissions) {]=])
    string(REPLACE "${_direct_empty_compute_submit_anchor}" "${_direct_empty_compute_submit_replacement}" _vulkan_contents "${_vulkan_contents}")

    set(_profiler_wait_anchor [=[
        ggml_vk_submit(compute_ctx, ctx->device->fence);
        VK_CHECK(ctx->device->device.waitForFences({ ctx->device->fence }, true, UINT64_MAX), "GGML_VULKAN_PERF waitForFences", ctx->device);
        ctx->device->device.resetFences({ ctx->device->fence });]=])
    string(FIND "${_vulkan_contents}" "${_profiler_wait_anchor}" _profiler_wait_position)
    if(_profiler_wait_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan profiler fence-wait anchor changed.")
    endif()
    set(_profiler_wait_replacement [=[
        ggml_vk_submit(compute_ctx, ctx->device->fence);
        const bool local_llm_sync_timing = local_llm_vulkan_logits_readback_timing_active();
        const auto local_llm_profile_wait_start = local_llm_sync_timing
            ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        // LOCAL_LLM_SYNC_PROFILER_WAIT
        VK_CHECK(ctx->device->device.waitForFences({ ctx->device->fence }, true, UINT64_MAX), "GGML_VULKAN_PERF waitForFences", ctx->device);
        if (local_llm_sync_timing) {
            local_llm_vulkan_sync_phase_record(1, static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - local_llm_profile_wait_start).count()));
        }
        ctx->device->device.resetFences({ ctx->device->fence });]=])
    string(REPLACE "${_profiler_wait_anchor}" "${_profiler_wait_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Fail configuration if any direct synchronization path disappeared.
    foreach(_required_sync_marker IN ITEMS
        "local_llm_vulkan_sync_phase_record(0"
        "local_llm_vulkan_sync_phase_record(1"
        "LOCAL_LLM_SYNC_ALMOST_READY_WAIT"
        "LOCAL_LLM_SYNC_SERIALIZED_GRAPH_WAIT"
        "LOCAL_LLM_SYNC_DIRECT_COMPUTE_QUEUE_SUBMIT"
        "LOCAL_LLM_SYNC_PROFILER_WAIT")
        string(FIND "${_vulkan_contents}" "${_required_sync_marker}" _sync_marker_position)
        if(_sync_marker_position LESS 0)
            message(FATAL_ERROR "LocalLLM: Vulkan queue-submit/fence-wait timing bridge is incomplete.")
        endif()
    endforeach()

    # Upstream writes GGML_VK_PERF_LOGGER output to std::cerr, which is not
    # persisted by the engine's llama/ggml log callback. Replace only this
    # function in the generated Android source so each GPU timestamp sample
    # enters the same timestamped, exportable native diagnostics file.
    set(_perf_logger_start_anchor "void vk_perf_logger::print_timings(bool force) {")
    set(_perf_logger_end_anchor "std::string vk_perf_logger::get_node_fusion_name")
    string(FIND "${_vulkan_contents}" "${_perf_logger_start_anchor}" _perf_logger_start)
    string(FIND "${_vulkan_contents}" "${_perf_logger_end_anchor}" _perf_logger_end)
    if(_perf_logger_start LESS 0 OR _perf_logger_end LESS _perf_logger_start)
        message(FATAL_ERROR "LocalLLM: upstream Vulkan per-operation profiler anchor changed.")
    endif()

    set(_perf_logger_replacement [=[
extern "C" const char* local_llm_vulkan_profile_run_id();

void vk_perf_logger::print_timings(bool force) {
    if (timings.empty()) {
        return;
    }
    print_count++;
    if ((print_count % vk_perf_logger_frequency) != 0 && !force) {
        return;
    }
    print_count = 0;

    static std::atomic<uint64_t> local_llm_snapshot_counter{0};
    const uint64_t snapshot_id =
        local_llm_snapshot_counter.fetch_add(1, std::memory_order_relaxed);
    const int process_id = static_cast<int>(getpid());
    const char* active_run_id = local_llm_vulkan_profile_run_id();
    if (active_run_id == nullptr || active_run_id[0] == '\0') {
        active_run_id = "none";
    }
    uint64_t total_all_op_times = 0;
    uint64_t logged_ops = 0;

    for (const auto & t : timings) {
        const size_t sample_count = t.second.size();
        if (sample_count == 0) {
            continue;
        }

        uint64_t total_op_times = 0;
        for (const auto & time : t.second) {
            total_op_times += time;
        }
        const double average_us =
            static_cast<double>(total_op_times) /
            static_cast<double>(sample_count) / 1000.0;
        const double total_us = static_cast<double>(total_op_times) / 1000.0;
        std::string operation_name = t.first;
        // The compact Kotlin parser uses commas as field separators.
        // Sanitize the operation descriptor so shapes like FLASH_ATTN_EXT
        // remain complete in the exported diagnostic.
        std::replace(operation_name.begin(), operation_name.end(), ',', ';');
        std::replace(operation_name.begin(), operation_name.end(), '\n', ' ');
        std::replace(operation_name.begin(), operation_name.end(), '\r', ' ');

        auto it = flops.find(t.first);
        if (it != flops.end() && it->second.size() == sample_count && total_op_times > 0) {
            uint64_t total_op_flops = 0;
            for (const auto & elem : it->second) {
                total_op_flops += elem;
            }
            const double gflops =
                static_cast<double>(total_op_flops) /
                static_cast<double>(total_op_times);
            GGML_LOG_INFO(
                "ggml_vulkan: LOCAL_LLM_VULKAN_OP_TIMING - pid=%d,runId=%s,snapshot=%llu,op=%s,samples=%llu,avgUs=%.3f,totalUs=%.3f,gflops=%.3f\n",
                process_id,
                active_run_id,
                static_cast<unsigned long long>(snapshot_id),
                operation_name.c_str(),
                static_cast<unsigned long long>(sample_count),
                average_us,
                total_us,
                gflops);
        } else {
            GGML_LOG_INFO(
                "ggml_vulkan: LOCAL_LLM_VULKAN_OP_TIMING - pid=%d,runId=%s,snapshot=%llu,op=%s,samples=%llu,avgUs=%.3f,totalUs=%.3f,gflops=n/a\n",
                process_id,
                active_run_id,
                static_cast<unsigned long long>(snapshot_id),
                operation_name.c_str(),
                static_cast<unsigned long long>(sample_count),
                average_us,
                total_us);
        }

        total_all_op_times += total_op_times;
        ++logged_ops;
    }

    GGML_LOG_INFO(
        "ggml_vulkan: LOCAL_LLM_VULKAN_OP_TIMING_SUMMARY - pid=%d,runId=%s,snapshot=%llu,ops=%llu,sumOpUs=%.3f\n",
        process_id,
        active_run_id,
        static_cast<unsigned long long>(snapshot_id),
        static_cast<unsigned long long>(logged_ops),
        static_cast<double>(total_all_op_times) / 1000.0);

    timings.clear();
    flops.clear();
}
]=])

    string(SUBSTRING "${_vulkan_contents}" 0 ${_perf_logger_start} _perf_logger_prefix)
    string(SUBSTRING "${_vulkan_contents}" ${_perf_logger_end} -1 _perf_logger_suffix)
    set(_vulkan_contents "${_perf_logger_prefix}${_perf_logger_replacement}${_perf_logger_suffix}")

    set(_patched_dir "${CMAKE_CURRENT_BINARY_DIR}/local-android-vulkan")
    file(MAKE_DIRECTORY "${_patched_dir}")
    set(_patched_cpp "${_patched_dir}/ggml-vulkan.cpp")
    file(WRITE "${_patched_cpp}" "${_vulkan_contents}")

    if(NOT _vulkan_contents MATCHES "LOCAL_LLM_ANDROID_VULKAN_COMPAT" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_Q4_K_PREFILL_PATH" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH" OR
       NOT _vulkan_contents MATCHES "local_llm_q4k_integer_dot_batch1" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_Q4K_AUTO_VENDOR_SCOPE" OR
       NOT _vulkan_contents MATCHES "local_llm_mali_q4k_auto" OR
       NOT _vulkan_contents MATCHES "local_llm_mali_auto_prefers_mmvq" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_Q4K_Q8_1_ELIGIBILITY" OR
       NOT _vulkan_contents MATCHES "src1->type == GGML_TYPE_F32" OR
       NOT _vulkan_contents MATCHES "ggml_is_contiguous\\(src1\\)" OR
       NOT _vulkan_contents MATCHES "ggml_vk_src1_prec_allows_q8\\(dst\\)" OR
       NOT _vulkan_contents MATCHES "selected=MMVQ_INTEGER_DOT" OR
       NOT _vulkan_contents MATCHES "q8_1Eligible=%d,q8_1Eligibility=%s" OR
       NOT _vulkan_contents MATCHES "q8_1_pipeline_unavailable_fallback" OR
       NOT _vulkan_contents MATCHES "local_llm::resolve_q4k_pipeline" OR
       NOT _vulkan_contents MATCHES "q8_1MatvecAvailable=%d,q8_1QuantizeAvailable=%d,baseMatvecAvailable=%d,fallbackStage=%s" OR
       NOT _vulkan_contents MATCHES "mali_auto_base_q8_1_ineligible" OR
       NOT _vulkan_contents MATCHES "mali_auto_base_measured_faster" OR
       NOT _vulkan_contents MATCHES "reason=%s" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_MEMORY_BUDGET" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_OP_TIMING" OR
       NOT _vulkan_contents MATCHES "local_llm_vulkan_dmmv_workgroup_override" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_DMMV_WG_SELECTED" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_Q4K_BASE_DMMV_OVERRIDE" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_MALI_Q4K_AUTO_BASE_DMMV" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_Q4K_PREFILL_F16_OVERRIDE" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_QWEN35_GDN_CPU_FALLBACK" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_MMVQ_FORCE" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_MALI_FENCE_WAIT_SELECTED_${LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY}" OR
       NOT _vulkan_contents MATCHES "local_llm_vulkan_logits_readback_record" OR
       NOT _vulkan_contents MATCHES "eHostCoherent" OR
       _vulkan_contents MATCHES "vkGetPhysicalDeviceFeatures2\\(")
        message(FATAL_ERROR "LocalLLM: Android Vulkan compatibility/performance diagnostics overlay did not apply completely.")
    endif()

    # BLOCK has an additional blocking API call; validate it only in BLOCK mode.
    # SPIN deliberately replaces that call with a non-blocking fence-poll strategy.
    if(LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY STREQUAL "BLOCK" AND
       NOT _vulkan_contents MATCHES "LOCAL_LLM_MALI_BLOCKING_FENCE_WAIT")
        message(FATAL_ERROR "LocalLLM: blocking Mali fence-wait implementation did not apply.")
    endif()

    # Verify the selected strategy is inside the Mali-only branch and that
    # SPIN really polls while BLOCK really calls waitForFences. A marker alone
    # is insufficient: it could survive even if the generated control flow
    # accidentally stopped using it.
    set(_mali_wait_vendor_anchor "if (ctx->device->vendor_id == 0x13B5) {")
    string(FIND "${_vulkan_contents}" "${_mali_wait_vendor_anchor}" _mali_wait_vendor_position)
    set(_mali_wait_poll_anchor "if (local_llm_poll_fence) {")
    string(FIND "${_vulkan_contents}" "${_mali_wait_poll_anchor}" _mali_wait_poll_position)
    if(_mali_wait_vendor_position LESS 0 OR
       _mali_wait_poll_position LESS 0 OR
       _mali_wait_vendor_position GREATER_EQUAL _mali_wait_poll_position)
        message(FATAL_ERROR "LocalLLM: Mali fence-wait strategy is not connected to the shared fence-poll path.")
    endif()

    string(SUBSTRING "${_vulkan_contents}" ${_mali_wait_vendor_position} -1 _mali_wait_from_vendor)
    string(FIND "${_mali_wait_from_vendor}" "} else {" _mali_wait_vendor_else_position)
    if(_mali_wait_vendor_else_position LESS 0)
        message(FATAL_ERROR "LocalLLM: Mali fence-wait vendor guard lost its non-Mali fallback.")
    endif()
    string(SUBSTRING "${_mali_wait_from_vendor}" 0 ${_mali_wait_vendor_else_position} _mali_wait_mali_branch)

    if(LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY STREQUAL "SPIN")
        string(FIND "${_mali_wait_mali_branch}" "LOCAL_LLM_MALI_FENCE_WAIT_SELECTED_SPIN" _mali_spin_marker_position)
        string(FIND "${_mali_wait_mali_branch}" "local_llm_poll_fence = true;" _mali_spin_enable_position)
        string(FIND "${_mali_wait_mali_branch}" "waitForFences" _mali_spin_blocking_position)
        if(_mali_spin_marker_position LESS 0 OR
           _mali_spin_enable_position LESS 0 OR
           NOT _mali_spin_blocking_position LESS 0)
            message(FATAL_ERROR "LocalLLM: SPIN must select Mali fence polling and must not block with waitForFences.")
        endif()
    else()
        string(FIND "${_mali_wait_mali_branch}" "LOCAL_LLM_MALI_FENCE_WAIT_SELECTED_BLOCK" _mali_block_marker_position)
        string(FIND "${_mali_wait_mali_branch}" "waitForFences" _mali_block_call_position)
        string(FIND "${_mali_wait_mali_branch}" "local_llm_poll_fence = true;" _mali_block_poll_position)
        if(_mali_block_marker_position LESS 0 OR
           _mali_block_call_position LESS 0 OR
           NOT _mali_block_poll_position LESS 0)
            message(FATAL_ERROR "LocalLLM: BLOCK must call waitForFences and must not enable Mali fence polling.")
        endif()
    endif()

    # Both modes share the same checked poll loop for SPIN and non-Mali GPUs:
    # success exits, unexpected Vulkan results throw, and the fence resets only
    # after completion. This guards ordering and prevents a busy-loop regression.
    string(SUBSTRING "${_vulkan_contents}" ${_mali_wait_poll_position} -1 _mali_wait_poll_tail)
    set(_mali_poll_success_position -1)
    set(_mali_poll_not_ready_position -1)
    set(_mali_poll_yield_position -1)
    set(_mali_poll_reset_position -1)
    string(FIND "${_mali_wait_poll_tail}" "getFenceStatus(ctx->fence)" _mali_poll_status_position)
    string(FIND "${_mali_wait_poll_tail}" "vk::Result::eSuccess" _mali_poll_success_position)
    string(FIND "${_mali_wait_poll_tail}" "vk::Result::eNotReady" _mali_poll_not_ready_position)
    string(FIND "${_mali_wait_poll_tail}" "YIELD();" _mali_poll_yield_position)
    string(FIND "${_mali_wait_poll_tail}" "resetFences({ ctx->fence })" _mali_poll_reset_position)
    if(_mali_poll_status_position LESS 0 OR
       _mali_poll_success_position LESS _mali_poll_status_position OR
       _mali_poll_not_ready_position LESS _mali_poll_success_position OR
       _mali_poll_yield_position LESS _mali_poll_not_ready_position OR
       _mali_poll_reset_position LESS _mali_poll_yield_position)
        message(FATAL_ERROR "LocalLLM: Mali fence-poll completion/error/yield/reset ordering is invalid.")
    endif()

    message(STATUS
        "LocalLLM: verified Mali fence-wait strategy=${LOCAL_LLM_MALI_FENCE_WAIT_STRATEGY} (Mali-only branch, exact wait implementation, checked completion loop)")

    string(FIND "${_q4k_decode_function_contents}" "local_llm_q4k_integer_dot_batch1" _q4k_selector_position)
    string(FIND "${_q4k_decode_function_contents}"
        "vk_pipeline dmmv = quantize_y"
        _q4k_q8_pipeline_position)
    string(FIND "${_q4k_decode_function_contents}"
        "\"q8_1_pipeline_unavailable_fallback\""
        _q4k_fallback_reason_position)
    string(FIND "${_q4k_decode_function_contents}" "selected_path = dmmv == nullptr" _q4k_selected_log_position)
    string(FIND "${_q4k_decode_function_contents}" "base_dmmv = ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, y_kernel_type" _q4k_base_f32_pipeline_position)
    string(FIND "${_q4k_decode_function_contents}" "local_llm::resolve_q4k_pipeline(" _q4k_resolver_position)
    if(_q4k_selector_position LESS 0 OR
       _q4k_q8_pipeline_position LESS 0 OR
       _q4k_fallback_reason_position LESS 0 OR
       _q4k_selected_log_position LESS 0 OR
       _q4k_base_f32_pipeline_position LESS 0 OR
       _q4k_resolver_position LESS 0)
        message(FATAL_ERROR "LocalLLM: Q4_K selector, Q8_1 pipeline, fallback or runtime evidence left ggml_vk_mul_mat_vec_q_f16.")
    endif()
    if(_q4k_selector_position GREATER_EQUAL _q4k_q8_pipeline_position OR
       _q4k_q8_pipeline_position GREATER_EQUAL _q4k_base_f32_pipeline_position OR
       _q4k_base_f32_pipeline_position GREATER_EQUAL _q4k_resolver_position OR
       _q4k_resolver_position GREATER_EQUAL _q4k_fallback_reason_position OR
       _q4k_fallback_reason_position GREATER_EQUAL _q4k_selected_log_position)
        message(FATAL_ERROR "LocalLLM: Q4_K selector/pipeline/fallback/runtime-log order changed; refusing an unverified Vulkan overlay.")
    endif()
    math(EXPR _q4k_after_fallback_position "${_q4k_fallback_reason_position} + 1")
    string(SUBSTRING "${_q4k_decode_function_contents}" ${_q4k_after_fallback_position} -1 _q4k_fallback_tail)
    string(FIND "${_q4k_fallback_tail}" "quantize_y = q4k_selection.quantize_y;" _q4k_fallback_disable_position)
    if(_q4k_fallback_disable_position LESS 0)
        message(FATAL_ERROR "LocalLLM: Q4_K missing-pipeline fallback no longer selects the base F32 quantization mode.")
    endif()
    message(STATUS "LocalLLM: verified Q4_K selector, Q8_1 matvec/quantizer availability, F32 base fallback and runtime path evidence")

    # Verify every quantization-specific override remains scoped to its intended
    # tensor type and batch shape. These are source-overlay guards, not device
    # runtime proof; the latter remains in the P0-02 acceptance matrix.
    set(_q4k_prefill_scope [=[
    const bool local_llm_force_q4k_prefill_f16 =
        src0->type == GGML_TYPE_Q4_K && ne11 > 1 &&
        local_llm_vulkan_dmmv_workgroup_override() == 6;]=])
    set(_q4k_decode_batch1_scope [=[
    } else if (src0->type == GGML_TYPE_Q4_K && ne11 == 1 &&
               (local_llm_quant_override == 3 || local_llm_mali_q4k_auto)) {]=])
    set(_q5k_decode_scope [=[
    } else if (src0->type == GGML_TYPE_Q5_K && ne11 == 1 && local_llm_quant_override == 4) {]=])
    set(_q6k_decode_scope [=[
    } else if (src0->type == GGML_TYPE_Q6_K && ne11 == 1 && local_llm_quant_override == 5) {]=])
    set(_mali_auto_scope [=[
    const bool local_llm_mali_q4k_auto =
        local_llm_quant_override == 0 && ctx->device->vendor_id == 0x13B5;]=])
    set(_mmvq_force_scope [=[
    const bool local_llm_mmvq_force_requested =
        local_llm_quant_override == 7 &&
        ctx->device->integer_dot_product &&
        src1->type == GGML_TYPE_F32 &&
        ggml_is_contiguous(src1) && !y_non_contig &&
        (ne11 * ne10) % 4 == 0 &&
        (swap_inputs || ggml_vk_src1_prec_allows_q8(dst));]=])
    foreach(_scope_name IN ITEMS
            _q4k_prefill_scope _q4k_decode_batch1_scope _q5k_decode_scope
            _q6k_decode_scope _mali_auto_scope _mmvq_force_scope)
        string(FIND "${_vulkan_contents}" "${${_scope_name}}" _scope_position)
        if(_scope_position LESS 0)
            message(FATAL_ERROR "LocalLLM: Vulkan quantization override scope changed: ${_scope_name}")
        endif()
    endforeach()
    if(NOT _vulkan_contents MATCHES "ggml_vk_should_use_mmvq\\(")
        message(FATAL_ERROR "LocalLLM: pinned upstream MMVQ heuristic disappeared from AUTO path.")
    endif()
    message(STATUS "LocalLLM: verified Q4_K prefill/batch-1, Q5_K/Q6_K, Mali AUTO and guarded MMVQ override scopes")

    set(LOCAL_LLM_PATCHED_VULKAN_CPP "${_patched_cpp}" PARENT_SCOPE)

    # Current upstream only uses this package lookup as a header-package
    # availability check. The actual pinned headers are supplied by the parent
    # CMakeLists via target_include_directories below.
    set(_spirv_package_dir "${CMAKE_CURRENT_BINARY_DIR}/local-spirv-headers-package")
    file(MAKE_DIRECTORY "${_spirv_package_dir}")
    file(WRITE "${_spirv_package_dir}/SPIRV-HeadersConfig.cmake"
        "# Header-only SPIRV-Headers are provided through the target include path.\n")
    set("SPIRV-Headers_DIR" "${_spirv_package_dir}" CACHE PATH
        "LocalLLM header-only SPIRV-Headers package shim" FORCE)
    message(STATUS "LocalLLM: prepared Android Vulkan compatibility overlay for pinned llama.cpp")
endfunction()