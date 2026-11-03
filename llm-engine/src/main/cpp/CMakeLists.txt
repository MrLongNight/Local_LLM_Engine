cmake_minimum_required(VERSION 3.22.1)
project("localllm_engine" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(DEFINED ANDROID_ABI)
    message(STATUS "LocalLLM detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)

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
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "ggml: cpu all variants" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "ggml: llamafile" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "ggml: openmp" FORCE)

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

add_subdirectory(${LLAMA_SRC} build-llama)

if(TARGET ggml-vulkan)
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
)
