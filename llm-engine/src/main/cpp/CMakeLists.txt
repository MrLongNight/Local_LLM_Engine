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
        static std::atomic<uint32_t> local_llm_q4k_decode_log_count{0};
        const uint32_t sample_index = local_llm_q4k_decode_log_count.fetch_add(1, std::memory_order_relaxed);
        if (sample_index < 12) {
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

    set(_patched_dir "${CMAKE_CURRENT_BINARY_DIR}/local-android-vulkan")
    file(MAKE_DIRECTORY "${_patched_dir}")
    set(_patched_cpp "${_patched_dir}/ggml-vulkan.cpp")
    file(WRITE "${_patched_cpp}" "${_vulkan_contents}")

    if(NOT _vulkan_contents MATCHES "LOCAL_LLM_ANDROID_VULKAN_COMPAT" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_Q4_K_PREFILL_PATH" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH" OR
       NOT _vulkan_contents MATCHES "LOCAL_LLM_MEMORY_BUDGET" OR
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
set(GGML_LLAMAFILE OFF CACHE BOOL "ggml: llamafile" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "ggml: use OpenMP" FORCE)

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
