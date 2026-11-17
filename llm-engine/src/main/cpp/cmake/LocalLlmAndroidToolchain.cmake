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
