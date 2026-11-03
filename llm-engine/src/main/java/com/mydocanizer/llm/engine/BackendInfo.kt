package com.mydocanizer.llm.engine

/**
 * Diagnostic and runtime information regarding the underlying llama.cpp backend.
 *
 * @property backendName Identifier of the active backend (e.g. CPU, KleidiAI).
 * @property systemInfo Detailed build and hardware feature flags reported by llama.cpp.
 * @property isAvailable True if native library loaded successfully.
 * @property recommendedThreads Recommended worker threads for this device.
 */
data class BackendInfo(
    val backendName: String = "llama.cpp (CPU)",
    val systemInfo: String = "",
    val isAvailable: Boolean = false,
    val recommendedThreads: Int = 4
)
