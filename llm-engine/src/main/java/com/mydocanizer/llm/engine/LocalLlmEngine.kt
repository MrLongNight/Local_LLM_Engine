package com.mydocanizer.llm.engine

import android.content.Context
import com.mydocanizer.llm.engine.internal.LocalLlmEngineImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public entrypoint for the on-device Local LLM Engine.
 *
 * Provides thread-safe, handle-managed access to native llama.cpp inference,
 * supporting multi-message conversational chat, real-time token streaming via [Flow],
 * cooperative cancellation, and dynamic model loading/unloading.
 */
interface LocalLlmEngine : AutoCloseable {
    /**
     * Observable state flow of the engine lifecycle.
     */
    val state: StateFlow<EngineState>

    /**
     * Currently loaded model metadata, or null if no model is loaded.
     */
    val currentModel: StateFlow<ModelInfo?>

    /**
     * Information about the active backend and underlying system capabilities.
     */
    val backendInfo: StateFlow<BackendInfo>

    /**
     * Initializes the native library and backend subsystems.
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Loads a GGUF model into memory from the provided [source].
     *
     * @param source The filesystem path, content URI, or asset of the model.
     * @param config Configuration parameters (context size, threads, temperature, etc.).
     * @return [Result] containing [ModelInfo] on success, or exception on failure.
     */
    suspend fun loadModel(
        source: ModelSource,
        config: EngineConfig = EngineConfig()
    ): Result<ModelInfo>

    /**
     * Unloads the active model from RAM and frees associated context buffers.
     */
    suspend fun unloadModel(): Result<Unit>

    /**
     * Streams token generation as an asynchronous [Flow] of [InferenceEvent].
     *
     * Supports cooperative cancellation via coroutine cancellation or by calling [cancelInference].
     *
     * @param request Multi-message chat request.
     * @return Flow emitting [InferenceEvent.Started], [InferenceEvent.Token], and completion/cancellation events.
     */
    fun streamChat(request: InferenceRequest): Flow<InferenceEvent>

    /**
     * Suspends until the complete assistant response is generated.
     *
     * @param request Multi-message chat request.
     * @return [Result] containing the aggregated [InferenceResult].
     */
    suspend fun generateChat(request: InferenceRequest): Result<InferenceResult>

    /**
     * Requests immediate cooperative cancellation of any ongoing token generation.
     */
    fun cancelInference()

    /**
     * Releases all native engine resources, context, and frees backend memory.
     */
    override fun close()

    companion object {
        /**
         * Creates a new instance of [LocalLlmEngine].
         *
         * @param context Android Application Context used for resolving ContentUris and cache storage.
         */
        fun create(context: Context): LocalLlmEngine {
            return LocalLlmEngineImpl(context.applicationContext)
        }
    }
}
