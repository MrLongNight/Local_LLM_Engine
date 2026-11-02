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

add_subdirectory(${LLAMA_SRC} build-llama)

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
    vulkan
)
