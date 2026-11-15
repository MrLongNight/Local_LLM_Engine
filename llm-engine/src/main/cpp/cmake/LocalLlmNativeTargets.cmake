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

option(LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS "Enable numeric token-ID/logit traces for synthetic correctness tests" OFF)
if(LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS)
    target_compile_definitions(localllm_engine PRIVATE LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS=1)
    message(STATUS "LocalLLM: synthetic numeric token-ID/logit diagnostics ENABLED")
else()
    message(STATUS "LocalLLM: synthetic numeric token-ID/logit diagnostics disabled")
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
