cmake_minimum_required(VERSION 3.22.1)
project("localllm_engine" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Export real compile commands so CI can verify optimization flags for each ABI.
set(CMAKE_EXPORT_COMPILE_COMMANDS ON CACHE BOOL "Export native compile commands for CI verification" FORCE)

# This CMake project is only built by the Android NDK. Keep the Android Debug
# variant debuggable, but never run llama.cpp/GGML at the compiler's -O0 default.
# The Android NDK seeds these as normal variables. A CACHE-only set does not
# replace an existing normal variable, so append to the normal value first and
# then mirror it into the cache. Release flags remain unchanged.
string(FIND "${CMAKE_C_FLAGS_DEBUG}" "-O2" LOCAL_LLM_C_DEBUG_O2_INDEX)
if(LOCAL_LLM_C_DEBUG_O2_INDEX EQUAL -1)
    string(APPEND CMAKE_C_FLAGS_DEBUG " -O2")
endif()
string(FIND "${CMAKE_CXX_FLAGS_DEBUG}" "-O2" LOCAL_LLM_CXX_DEBUG_O2_INDEX)
if(LOCAL_LLM_CXX_DEBUG_O2_INDEX EQUAL -1)
    string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O2")
endif()
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG}" CACHE STRING "Optimized native Debug C flags" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG}" CACHE STRING "Optimized native Debug C++ flags" FORCE)
string(FIND "${CMAKE_C_FLAGS_DEBUG}" "-O2" LOCAL_LLM_C_DEBUG_O2_VERIFY)
string(FIND "${CMAKE_CXX_FLAGS_DEBUG}" "-O2" LOCAL_LLM_CXX_DEBUG_O2_VERIFY)
if(LOCAL_LLM_C_DEBUG_O2_VERIFY EQUAL -1 OR LOCAL_LLM_CXX_DEBUG_O2_VERIFY EQUAL -1)
    message(FATAL_ERROR "LocalLLM: native Debug optimization -O2 was not applied to both C and C++ flags")
endif()
message("LocalLLM native Debug C flags: ${CMAKE_C_FLAGS_DEBUG}")
message("LocalLLM native Debug CXX flags: ${CMAKE_CXX_FLAGS_DEBUG}")

if(DEFINED ANDROID_ABI)
    message(STATUS "LocalLLM detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        # Match the official llama.cpp Android reference app. KleidiAI adds
        # Arm-optimized kernels where the runtime CPU feature set supports them.
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "ggml: use KleidiAI optimized kernels if applicable" FORCE)
        message(STATUS "LocalLLM: enabling GGML_CPU_KLEIDIAI for arm64-v8a")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "ggml: use KleidiAI optimized kernels if applicable" FORCE)
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)

# Keep the pinned llama.cpp tree pristine while compiling a generated copy of
# llama-context.cpp that counts graph nodes assigned to CPU/Vulkan per request.
function(local_llm_prepare_graph_backend_diagnostics source_dir)
    set(_context_source "${source_dir}/src/llama-context.cpp")
    if(NOT EXISTS "${_context_source}")
        message(FATAL_ERROR "LocalLLM: pinned llama-context.cpp is missing: ${_context_source}")
    endif()

    file(READ "${_context_source}" _context_contents)
    set(_graph_callback_anchor [=[
void llama_context::sched_graph_realloc(ggml_backend_sched_t sched, ggml_cgraph * gf, bool backend_ids_changed, bool graph_size_changed, void * user_data) {
    // reallocating after the backend assignments changed is expected, trace only the unexpected ones
    if (backend_ids_changed) {
        return;
    }
]=])
    string(FIND "${_context_contents}" "${_graph_callback_anchor}" _graph_callback_position)
    if(_graph_callback_position LESS 0)
        message(FATAL_ERROR "LocalLLM: pinned llama_context scheduler callback anchor changed; graph backend distribution cannot be instrumented.")
    endif()

    # The pinned source already uses std::string; add the explicit mutex header
    # for thread-safe per-run deduplication of identical graph layouts.
    string(REPLACE "#include <limits>" "#include <limits>\n#include <mutex>" _context_contents "${_context_contents}")

    set(_graph_callback_replacement [=[
extern "C" const char * local_llm_vulkan_profile_run_id();

void llama_context::sched_graph_realloc(ggml_backend_sched_t sched, ggml_cgraph * gf, bool backend_ids_changed, bool graph_size_changed, void * user_data) {
    // Count nodes against their actual scheduler backend. This is stronger evidence
    // of CPU fallback than requested gpuLayers or an aggregate split count alone.
    const char * run_id = local_llm_vulkan_profile_run_id();
    if (run_id != nullptr && std::strcmp(run_id, "none") != 0 && gf != nullptr) {
        int cpu_nodes = 0;
        int vulkan_nodes = 0;
        int other_nodes = 0;
        int unassigned_nodes = 0;
        const int graph_nodes = ggml_graph_n_nodes(gf);
        for (int i = 0; i < graph_nodes; ++i) {
            ggml_tensor * node = ggml_graph_node(gf, i);
            ggml_backend_t backend = ggml_backend_sched_get_tensor_backend(sched, node);
            if (backend == nullptr) {
                ++unassigned_nodes;
                continue;
            }
            const char * backend_name = ggml_backend_name(backend);
            if (backend_name != nullptr && std::strcmp(backend_name, "CPU") == 0) {
                ++cpu_nodes;
            } else if (backend_name != nullptr && std::strncmp(backend_name, "Vulkan", 6) == 0) {
                ++vulkan_nodes;
            } else {
                ++other_nodes;
            }
        }

        // The callback may be invoked several times for an unchanged graph. Keep
        // one evidence row per distinct graph layout and inference run, while
        // retaining separate prefill/decode shapes when their node assignments differ.
        static std::mutex local_llm_graph_diagnostic_mutex;
        static std::string local_llm_last_graph_signature;
        const std::string graph_signature =
            std::string(run_id) + ":" + std::to_string(graph_nodes) + ":" +
            std::to_string(ggml_backend_sched_get_n_splits(sched)) + ":" +
            std::to_string(cpu_nodes) + ":" + std::to_string(vulkan_nodes) + ":" +
            std::to_string(other_nodes) + ":" + std::to_string(unassigned_nodes);
        bool should_log_graph_distribution = false;
        {
            std::lock_guard<std::mutex> lock(local_llm_graph_diagnostic_mutex);
            if (graph_signature != local_llm_last_graph_signature) {
                local_llm_last_graph_signature = graph_signature;
                should_log_graph_distribution = true;
            }
        }
        if (should_log_graph_distribution) {
            LLAMA_LOG_INFO(
                "llama_context: LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION - runId=%s,graphNodes=%d,graphSplits=%d,cpuNodes=%d,vulkanNodes=%d,otherNodes=%d,unassignedNodes=%d,backendIdsChanged=%d,graphSizeChanged=%d\n",
                run_id,
                graph_nodes,
                ggml_backend_sched_get_n_splits(sched),
                cpu_nodes,
                vulkan_nodes,
                other_nodes,
                unassigned_nodes,
                backend_ids_changed ? 1 : 0,
                graph_size_changed ? 1 : 0);
        }
    }

    // reallocating after the backend assignments changed is expected, trace only the unexpected ones
    if (backend_ids_changed) {
        return;
    }
]=])
    string(REPLACE "${_graph_callback_anchor}" "${_graph_callback_replacement}" _context_contents "${_context_contents}")

    set(_patched_dir "${CMAKE_CURRENT_BINARY_DIR}/local-android-llama")
    file(MAKE_DIRECTORY "${_patched_dir}")
    set(_patched_context "${_patched_dir}/llama-context.cpp")
    file(WRITE "${_patched_context}" "${_context_contents}")
    if(NOT _context_contents MATCHES "LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION" OR
       NOT _context_contents MATCHES "ggml_backend_sched_get_tensor_backend")
        message(FATAL_ERROR "LocalLLM: graph backend distribution overlay did not apply completely.")
    endif()
    set(LOCAL_LLM_PATCHED_LLAMA_CONTEXT_CPP "${_patched_context}" PARENT_SCOPE)
endfunction()

# The pinned upstream tree is kept pristine. For Android ARM64 we compile a
# build-directory copy of ggml-vulkan.cpp with the small loader/extension
# compatibility adjustments required by Android API 24+ and Mali drivers.
function(local_llm_prepare_android_vulkan source_dir)
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
#include <unistd.h>

extern "C" int local_llm_vulkan_dmmv_workgroup_override();

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
    string(REPLACE "${_include_anchor}" "${_include_anchor}\n${_helper}" _vulkan_contents "${_vulkan_contents}")
    string(REPLACE "vkGetPhysicalDeviceFeatures2(" "ggml_vk_get_physical_device_features2(" _vulkan_contents "${_vulkan_contents}")

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
        static std::atomic<uint32_t> local_llm_q4k_prefill_log_count{0};
        const uint32_t sample_index = local_llm_q4k_prefill_log_count.fetch_add(1, std::memory_order_relaxed);
        if (sample_index < 12) {
            GGML_LOG_INFO(
                "ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_PREFILL_PATH - device=%s,intDot=%d,coopMatInt=%d,canQuantizeY=%d,selected=%s,yKernelType=%s,batchTokens=%llu,hidden=%llu,weightRows=%llu\n",
                ctx->device->name.c_str(),
                ctx->device->integer_dot_product ? 1 : 0,
                ctx->device->coopmat_int_support ? 1 : 0,
                can_quantize_y ? 1 : 0,
                quantize_y ? "Q8_1_MATMUL" : "F16_F32_MATMUL",
                ggml_type_name(y_kernel_type),
                static_cast<unsigned long long>(ne11),
                static_cast<unsigned long long>(ne10),
                static_cast<unsigned long long>(ne01));
        }
    }]=])
    string(REPLACE "${_q4_prefill_anchor}" "${_q4_prefill_replacement}" _vulkan_contents "${_vulkan_contents}")

    # Force only Q4_K decode to use the existing base DMMV implementation.
    # Q4_K prefill and all non-Q4_K tensor types retain their normal MMVQ selection.
    set(_q4k_base_dmmv_anchor [=[
    // Check for mmq first
    vk_pipeline dmmv = quantize_y ? ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, GGML_TYPE_Q8_1, ne11, ne20, ne00) : nullptr;]=])
    string(FIND "${_vulkan_contents}" "${_q4k_base_dmmv_anchor}" _q4k_base_dmmv_position)
    if(_q4k_base_dmmv_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Q4_K MMVQ selection anchor changed.")
    endif()
    set(_q4k_base_dmmv_replacement [=[
    // LOCAL_LLM_Q4K_BASE_DMMV_OVERRIDE: mode 3 explicitly forces base DMMV for Q4_K.
    // LOCAL_LLM_MALI_Q4K_AUTO_BASE_DMMV: Mali Q4_K decode skips Q8_1 conversion in AUTO.
    // Modes 3/4/5 expose explicit Q4_K/Q5_K/Q6_K A/B paths; only Q4_K changes AUTO.
    const int local_llm_quant_override = local_llm_vulkan_dmmv_workgroup_override();
    const bool local_llm_mali_q4k_auto =
        local_llm_quant_override == 0 && ctx->device->vendor_id == 0x13B5;
    if (src0->type == GGML_TYPE_Q4_K && ne11 == 1 &&
        (local_llm_quant_override == 3 || local_llm_mali_q4k_auto)) {
        quantize_y = false;
    } else if (src0->type == GGML_TYPE_Q5_K && ne11 == 1 && local_llm_quant_override == 4) {
        quantize_y = false;
    } else if (src0->type == GGML_TYPE_Q6_K && ne11 == 1 && local_llm_quant_override == 5) {
        quantize_y = false;
    }
    // Check for mmq first
    vk_pipeline dmmv = quantize_y ? ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, GGML_TYPE_Q8_1, ne11, ne20, ne00) : nullptr;]=])
    string(REPLACE "${_q4k_base_dmmv_anchor}" "${_q4k_base_dmmv_replacement}" _vulkan_contents "${_vulkan_contents}")
    set(_q4_decode_anchor [=[
    if (dmmv == nullptr) {
        // Fall back to f16 dequant mul mat
        dmmv = ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, y_kernel_type, ne11, ne20, ne00);
        quantize_y = false;
    }]=])
    string(FIND "${_vulkan_contents}" "${_q4_decode_anchor}" _q4_decode_position)
    if(_q4_decode_position LESS 0)
        message(FATAL_ERROR "LocalLLM: upstream Q4_K decode pipeline anchor changed.")
    endif()
    set(_q4_decode_replacement [=[
    if (dmmv == nullptr) {
        // Fall back to f16 dequant mul mat
        dmmv = ggml_vk_get_dequantize_mul_mat_vec(ctx, src0->type, y_kernel_type, ne11, ne20, ne00);
        quantize_y = false;
    }
    if (src0->type == GGML_TYPE_Q4_K) {
        static std::atomic<int> local_llm_last_q4k_decode_path{-1};
        const int selected_path = quantize_y ? 1 : 0;
        const int previous_path = local_llm_last_q4k_decode_path.exchange(
            selected_path, std::memory_order_relaxed);
        if (previous_path != selected_path) {
            GGML_LOG_INFO(
                "ggml_vulkan: LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH - device=%s,intDot=%d,quantizeY=%d,selected=%s,src1Type=%s,yKernelType=%s,batchColumns=%llu,hidden=%llu,weightRows=%llu\n",
                ctx->device->name.c_str(),
                ctx->device->integer_dot_product ? 1 : 0,
                quantize_y ? 1 : 0,
                quantize_y ? "Q8_1_INTEGER_DOT_MMVQ" : "BASE_TYPE_MMV",
                ggml_type_name(src1->type),
                ggml_type_name(y_kernel_type),
                static_cast<unsigned long long>(ne11),
                static_cast<unsigned long long>(ne10),
                static_cast<unsigned long long>(ne01));
        }
    }]=])
    string(REPLACE "${_q4_decode_anchor}" "${_q4_decode_replacement}" _vulkan_contents "${_vulkan_contents}")

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
       NOT _vulkan_contents MATCHES "LOCAL_LLM_MEMORY_BUDGET" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_OP_TIMING" OR
       NOT _vulkan_contents MATCHES "local_llm_vulkan_dmmv_workgroup_override" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_DMMV_WG_SELECTED" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_Q4K_BASE_DMMV_OVERRIDE" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_MALI_Q4K_AUTO_BASE_DMMV" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_Q4K_PREFILL_F16_OVERRIDE" OR
       _vulkan_contents MATCHES "vkGetPhysicalDeviceFeatures2\\(")
        message(FATAL_ERROR "LocalLLM: Android Vulkan compatibility/performance diagnostics overlay did not apply completely.")
    endif()
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

local_llm_prepare_graph_backend_diagnostics("${LLAMA_SRC}")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "build shared libraries" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "llama: build the unified binary" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama: build common utils library" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server example" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "llama: use openssl" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "ggml: native" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "ggml: backend dl" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "ggml: build all variants of the CPU backend" FORCE)
# Keep GGML_BACKEND_DL/CPU_ALL_VARIANTS off: upstream's all-variants mode
# converts every backend (including Vulkan) to MODULE targets, incompatible with
# this app's static Vulkan link. ARM acceleration is provided by GGML_CPU_KLEIDIAI:
# pinned ggml-cpu/kleidiai performs Android HWCAP runtime detection for DOTPROD/I8MM
# and selects compatible kernels with NEON fallbacks. This preserves static Vulkan.
set(GGML_LLAMAFILE OFF CACHE BOOL "ggml: llamafile" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "ggml: use OpenMP" FORCE)

# Release native kernels use -O3 and NDEBUG. Debug keeps the verified -O2 policy.
# LTO remains opt-in until APK size and device behavior are measured.
foreach(_local_llm_lang C CXX)
    set(_local_llm_release_flags "${CMAKE_${_local_llm_lang}_FLAGS_RELEASE}")
    if(NOT _local_llm_release_flags MATCHES "(^| )-O3($| )")
        string(APPEND _local_llm_release_flags " -O3")
    endif()
    if(NOT _local_llm_release_flags MATCHES "(^| )-DNDEBUG($| )")
        string(APPEND _local_llm_release_flags " -DNDEBUG")
    endif()
    set(CMAKE_${_local_llm_lang}_FLAGS_RELEASE "${_local_llm_release_flags}" CACHE STRING
        "Optimized native Release ${_local_llm_lang} flags" FORCE)
    string(FIND "${CMAKE_${_local_llm_lang}_FLAGS_RELEASE}" "-O3" _local_llm_release_o3)
    string(FIND "${CMAKE_${_local_llm_lang}_FLAGS_RELEASE}" "-DNDEBUG" _local_llm_release_ndebug)
    if(_local_llm_release_o3 EQUAL -1 OR _local_llm_release_ndebug EQUAL -1)
        message(FATAL_ERROR "LocalLLM: native Release ${_local_llm_lang} flags must include -O3 and -DNDEBUG")
    endif()
endforeach()
option(LOCAL_LLM_ENABLE_NATIVE_LTO "Enable Release LTO for llama.cpp/GGML" OFF)
if(LOCAL_LLM_ENABLE_NATIVE_LTO)
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)
    message(STATUS "LocalLLM: Release LTO enabled")
endif()

# Enable the static Vulkan backend only for Android arm64 builds where the
# target NDK loader and host shader/SPIR-V tools are all available. CPU remains
# the safe fallback for x86_64 and local development environments without them.
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    if(DEFINED ENV{SPIRV_HEADERS_INCLUDE_DIR})
        set(LOCAL_LLM_SPIRV_HEADERS_INCLUDE_DIR "$ENV{SPIRV_HEADERS_INCLUDE_DIR}")
    endif()
    if(DEFINED ENV{VULKAN_HEADERS_INCLUDE_DIR})
        set(LOCAL_LLM_VULKAN_HEADERS_INCLUDE_DIR "$ENV{VULKAN_HEADERS_INCLUDE_DIR}")
    endif()

    find_package(Vulkan COMPONENTS glslc QUIET)

    if(Vulkan_FOUND AND Vulkan_GLSLC_EXECUTABLE AND
       LOCAL_LLM_SPIRV_HEADERS_INCLUDE_DIR AND
       EXISTS "${LOCAL_LLM_SPIRV_HEADERS_INCLUDE_DIR}/spirv/unified1/spirv.hpp" AND
       LOCAL_LLM_VULKAN_HEADERS_INCLUDE_DIR AND
       EXISTS "${LOCAL_LLM_VULKAN_HEADERS_INCLUDE_DIR}/vulkan/vulkan.hpp")
        set(GGML_VULKAN ON CACHE BOOL "ggml: Vulkan backend" FORCE)
        message(STATUS "LocalLLM: enabling GGML_VULKAN for arm64-v8a")
        message(STATUS "LocalLLM: Vulkan glslc: ${Vulkan_GLSLC_EXECUTABLE}")
    else()
        set(GGML_VULKAN OFF CACHE BOOL "ggml: Vulkan backend" FORCE)
        if(DEFINED ENV{LOCAL_LLM_REQUIRE_VULKAN} AND "$ENV{LOCAL_LLM_REQUIRE_VULKAN}" STREQUAL "1")
            message(FATAL_ERROR "LocalLLM: required arm64 Vulkan dependencies missing (Vulkan_FOUND=${Vulkan_FOUND}, glslc=${Vulkan_GLSLC_EXECUTABLE}, SPIR-V headers=${LOCAL_LLM_SPIRV_HEADERS_INCLUDE_DIR}, Vulkan-Hpp headers=${LOCAL_LLM_VULKAN_HEADERS_INCLUDE_DIR})")
        endif()
        message(WARNING "LocalLLM: Vulkan build dependencies missing; using CPU backend")
        message(STATUS "  Vulkan_FOUND=${Vulkan_FOUND}")
        message(STATUS "  Vulkan_GLSLC_EXECUTABLE=${Vulkan_GLSLC_EXECUTABLE}")
        message(STATUS "  SPIR-V header directory=${LOCAL_LLM_SPIRV_HEADERS_INCLUDE_DIR}")
        message(STATUS "  Vulkan-Hpp header directory=${LOCAL_LLM_VULKAN_HEADERS_INCLUDE_DIR}")
    endif()
else()
    set(GGML_VULKAN OFF CACHE BOOL "ggml: Vulkan backend" FORCE)
endif()

set(LOCAL_LLM_INTEGER_DOT_GLSLC_SUPPORTED OFF)

if(GGML_VULKAN)
    # Do not let an old host glslc silently compile Vulkan without the fast
    # quantized MMVQ integer-dot kernels. Upstream treats this as an optional
    # shader extension; this Android engine requires it for a valid Vulkan build.
    set(_integer_dot_shader "${LLAMA_SRC}/ggml/src/ggml-vulkan/vulkan-shaders/feature-tests/integer_dot.comp")
    if(NOT EXISTS "${_integer_dot_shader}")
        message(FATAL_ERROR "LocalLLM: pinned llama.cpp integer-dot feature test is missing: ${_integer_dot_shader}")
    endif()

    execute_process(
        COMMAND "${Vulkan_GLSLC_EXECUTABLE}" -o - -fshader-stage=compute --target-env=vulkan1.3 "${_integer_dot_shader}"
        RESULT_VARIABLE _integer_dot_glslc_result
        OUTPUT_VARIABLE _integer_dot_glslc_output
        ERROR_VARIABLE _integer_dot_glslc_error
    )
    if(NOT _integer_dot_glslc_result EQUAL 0)
        message(FATAL_ERROR
            "LocalLLM: Vulkan glslc cannot compile GL_EXT_integer_dot_product. "
            "This disables llama.cpp's quantized MMVQ integer-dot kernels and causes slow Q4_K decode. "
            "Use the pinned Vulkan SDK 1.4.363.0 toolchain. glslc error: ${_integer_dot_glslc_error}")
    endif()

    set(LOCAL_LLM_INTEGER_DOT_GLSLC_SUPPORTED ON)
    message(STATUS "LocalLLM: GL_EXT_integer_dot_product shader support VERIFIED by ${Vulkan_GLSLC_EXECUTABLE}")
    local_llm_prepare_android_vulkan("${LLAMA_SRC}")
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

# Replace only the compiled source path; the pinned upstream submodule remains pristine.
if(NOT LOCAL_LLM_PATCHED_LLAMA_CONTEXT_CPP OR NOT EXISTS "${LOCAL_LLM_PATCHED_LLAMA_CONTEXT_CPP}")
    message(FATAL_ERROR "LocalLLM: generated llama-context.cpp diagnostic overlay is missing.")
endif()
get_target_property(_llama_sources llama SOURCES)
set(_llama_sources_patched)
set(_llama_context_source_replaced FALSE)
foreach(_llama_source IN LISTS _llama_sources)
    get_filename_component(_llama_source_name "${_llama_source}" NAME)
    if(_llama_source_name STREQUAL "llama-context.cpp")
        list(APPEND _llama_sources_patched "${LOCAL_LLM_PATCHED_LLAMA_CONTEXT_CPP}")
        set(_llama_context_source_replaced TRUE)
    else()
        list(APPEND _llama_sources_patched "${_llama_source}")
    endif()
endforeach()
if(NOT _llama_context_source_replaced)
    message(FATAL_ERROR "LocalLLM: could not replace llama-context.cpp with the graph backend diagnostic build copy.")
endif()
set_property(TARGET llama PROPERTY SOURCES "${_llama_sources_patched}")

# Keep the diagnostic overlay visible as its own compile_commands entry. llama.cpp
# enables CMake unity builds; without this per-source opt-out, llama-context.cpp is
# textually included in a generated Unity/unity_*.cxx translation unit and CI cannot
# independently verify that the graph instrumentation overlay is the source compiled.
set_source_files_properties(
    "${LOCAL_LLM_PATCHED_LLAMA_CONTEXT_CPP}"
    PROPERTIES SKIP_UNITY_BUILD_INCLUSION ON
)

# Internal diagnostic bridge: expose the current scheduler split count without
# modifying llama.cpp's public C API or the pinned submodule.
if(NOT TARGET llama)
    message(FATAL_ERROR "LocalLLM: pinned llama target was not created; graph-split diagnostics cannot be installed.")
endif()
set(_local_llm_graph_metrics_source "${CMAKE_CURRENT_BINARY_DIR}/local_llm_graph_metrics.cpp")
file(WRITE "${_local_llm_graph_metrics_source}" [=[
#include "llama-context.h"
#include "ggml-backend.h"

extern "C" int local_llm_get_graph_splits(struct llama_context * context) {
    return context ? ggml_backend_sched_get_n_splits(context->get_sched()) : -1;
}
]=])
target_sources(llama PRIVATE "${_local_llm_graph_metrics_source}")

if(TARGET ggml-vulkan)
    if(LOCAL_LLM_PATCHED_VULKAN_CPP)
        get_target_property(_ggml_vulkan_sources ggml-vulkan SOURCES)
        set(_ggml_vulkan_sources_patched)
        set(_ggml_vulkan_source_replaced FALSE)
        foreach(_ggml_source IN LISTS _ggml_vulkan_sources)
            get_filename_component(_ggml_source_name "${_ggml_source}" NAME)
            if(_ggml_source_name STREQUAL "ggml-vulkan.cpp")
                list(APPEND _ggml_vulkan_sources_patched "${LOCAL_LLM_PATCHED_VULKAN_CPP}")
                set(_ggml_vulkan_source_replaced TRUE)
            else()
                list(APPEND _ggml_vulkan_sources_patched "${_ggml_source}")
            endif()
        endforeach()
        if(NOT _ggml_vulkan_source_replaced)
            message(FATAL_ERROR "LocalLLM: could not replace ggml-vulkan.cpp with the Android compatibility build copy.")
        endif()
        set_property(TARGET ggml-vulkan PROPERTY SOURCES "${_ggml_vulkan_sources_patched}")
        target_include_directories(ggml-vulkan PRIVATE "${LLAMA_SRC}/ggml/src/ggml-vulkan")
    endif()
    if(LOCAL_LLM_SPIRV_HEADERS_INCLUDE_DIR)
        # SPIRV-Headers is fetched as a pinned host-side header source. Pass its
        # include directory explicitly; merely checking the file at configure
        # time does not make <spirv/unified1/spirv.hpp> visible to the compiler.
        target_include_directories(ggml-vulkan SYSTEM PRIVATE "${LOCAL_LLM_SPIRV_HEADERS_INCLUDE_DIR}")
    endif()
    if(LOCAL_LLM_VULKAN_HEADERS_INCLUDE_DIR)
        # Vulkan-Hpp is not shipped by the Android NDK; use Khronos headers only
        # for compilation while keeping the Vulkan loader from the Android NDK.
        target_include_directories(ggml-vulkan SYSTEM PRIVATE "${LOCAL_LLM_VULKAN_HEADERS_INCLUDE_DIR}")
    endif()
endif()

add_library(localllm_engine SHARED
    local_llm_engine_jni.cpp
)

if(LOCAL_LLM_INTEGER_DOT_GLSLC_SUPPORTED)
    target_compile_definitions(localllm_engine PRIVATE LOCAL_LLM_INTEGER_DOT_GLSLC_SUPPORTED=1)
endif()

option(LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS "Enable prompt/token/logit text diagnostics" OFF)
if(LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS)
    target_compile_definitions(localllm_engine PRIVATE LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS=1)
    message(STATUS "LocalLLM: prompt/token/logit text diagnostics ENABLED")
else()
    message(STATUS "LocalLLM: prompt/token/logit text diagnostics disabled")
endif()

option(LOCAL_LLM_ENABLE_TEST_HOOKS "Enable debug-only native inference fault injection" OFF)
if(LOCAL_LLM_ENABLE_TEST_HOOKS)
    target_compile_definitions(localllm_engine PRIVATE LOCAL_LLM_ENABLE_TEST_HOOKS=1)
    message(STATUS "LocalLLM: debug-only native inference test hooks ENABLED")
else()
    message(STATUS "LocalLLM: native inference test hooks disabled")
endif()

target_include_directories(localllm_engine PRIVATE
    ${LLAMA_SRC}
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/ggml/src
)

target_link_libraries(localllm_engine PRIVATE
    llama
    llama-common
    android
    log
    vulkan
)
