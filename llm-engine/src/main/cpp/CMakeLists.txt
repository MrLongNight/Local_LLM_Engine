cmake_minimum_required(VERSION 3.22.1)
project("localllm_engine" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Export real compile commands so CI can verify optimization flags for each ABI.
set(CMAKE_EXPORT_COMPILE_COMMANDS ON CACHE BOOL "Export native compile commands for CI verification" FORCE)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)

# Load pure function definitions before executing the ordered build phases.
include("${CMAKE_CURRENT_LIST_DIR}/cmake/LocalLlmGraphDiagnostics.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/cmake/LocalLlmAndroidVulkan.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/cmake/LocalLlmBuildVerification.cmake")

# Toolchain and source-overlay preparation must precede upstream target creation.
include("${CMAKE_CURRENT_LIST_DIR}/cmake/LocalLlmAndroidToolchain.cmake")
local_llm_prepare_graph_backend_diagnostics("${LLAMA_SRC}")
local_llm_verify_split_scheduler_race_fix("${LLAMA_SRC}")
include("${CMAKE_CURRENT_LIST_DIR}/cmake/LocalLlmUpstreamConfiguration.cmake")

# Create llama.cpp/GGML targets first, then attach the checked JNI engine target.
include("${CMAKE_CURRENT_LIST_DIR}/cmake/LocalLlmNativeTargets.cmake")
