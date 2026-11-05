package com.mydocanizer.llm.engine.internal

import android.util.Log

/**
 * Receives llama.cpp GGUF weight-loading progress (0.0..1.0).
 * Called synchronously from native model loading.
 */
internal fun interface NativeModelLoadProgressCallback {
    fun onProgress(progress: Float)
}

/** Receives exact prompt-token progress after each completed llama.cpp decode batch. */
internal fun interface NativeInferenceProgressCallback {
    fun onProgress(tokensProcessed: Int, totalPromptTokens: Int)
}

/**
 * JNI bridge interfacing with the native C++ llama.cpp wrapper `localllm_engine`.
 */
internal object NativeEngineHandle {
    private const val TAG = "NativeEngineHandle"
    private const val LIB_NAME = "localllm_engine"

    @Volatile
    private var isLoaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (!isLoaded) {
            try {
                System.loadLibrary(LIB_NAME)
                isLoaded = true
                nativeInitBackend()
                Log.i(TAG, "Native library $LIB_NAME successfully loaded and backend initialized")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load native library $LIB_NAME: ${t.message}", t)
                return false
            }
        }
        return isLoaded
    }

    @JvmStatic
    external fun nativeInitBackend()

    @JvmStatic
    external fun nativeShutdownBackend()

    @JvmStatic
    external fun nativeGetSystemInfo(): String

    @JvmStatic
    external fun nativeCreateEngine(): Long

    @JvmStatic
    external fun nativeLoadModel(
        handle: Long,
        modelPath: String,
        contextSize: Int,
        batchSize: Int,
        threads: Int,
        temperature: Float,
        topP: Float,
        backendMode: Int,
        gpuLayers: Int,
        progressCallback: NativeModelLoadProgressCallback
    ): Int

    @JvmStatic
    external fun nativeGetLastPromptMetrics(handle: Long): String

    @JvmStatic
    external fun nativeGetActiveBackend(handle: Long): String

    @JvmStatic
    external fun nativeGetModelMetadata(handle: Long): String

    @JvmStatic
    external fun nativeProcessChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxPredict: Int,
        enableThinking: Boolean,
        progressCallback: NativeInferenceProgressCallback
    ): Int

    @JvmStatic
    external fun nativeGenerateNextToken(handle: Long): String?

    @JvmStatic
    external fun nativeGetLastFinishReason(handle: Long): Int

    @JvmStatic
    external fun nativeGetInferenceDurationUs(handle: Long): Long

    @JvmStatic
    external fun nativeGetTokensGenerated(handle: Long): Int

    @JvmStatic
    external fun nativeCancel(handle: Long)

    @JvmStatic
    external fun nativeUnloadModel(handle: Long)

    @JvmStatic
    external fun nativeDestroyEngine(handle: Long)
}
