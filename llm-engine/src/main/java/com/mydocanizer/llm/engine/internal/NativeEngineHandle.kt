package com.mydocanizer.llm.engine.internal

import android.util.Log

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
    external fun nativeIsVulkanBackendAvailable(): Boolean

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
        gpuLayers: Int
    ): Int

    @JvmStatic
    external fun nativeGetActiveBackend(handle: Long): String

    @JvmStatic
    external fun nativeGetModelMetadata(handle: Long): String

    @JvmStatic
    external fun nativeGetModelArchitecture(handle: Long): String

    @JvmStatic
    external fun nativeResetCancellation(handle: Long)

    @JvmStatic
    external fun nativeSetRequestTemperature(handle: Long, temperature: Float): Int

    @JvmStatic
    external fun nativeProcessChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxPredict: Int
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
