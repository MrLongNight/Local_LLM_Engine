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
