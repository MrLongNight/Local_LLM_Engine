package com.mydocanizer.llm.engine

/**
 * State machine representing the lifecycle of [LocalLlmEngine].
 */
sealed interface EngineState {
    /** Native library has not yet been loaded */
    data object Uninitialized : EngineState

    /** Native library and llama.cpp backend are loading */
    data object Initializing : EngineState

    /** Backend is ready, but no model is currently loaded */
    data object Ready : EngineState

    /** A model is currently being loaded from storage */
    data class LoadingModel(val source: ModelSource) : EngineState

    /** A model is loaded into memory and ready for chat inference */
    data class ModelLoaded(val modelInfo: ModelInfo) : EngineState

    /** Prompt evaluation or token generation is in progress */
    data class Inferring(val request: InferenceRequest) : EngineState

    /** Engine encountered an unrecoverable failure */
    data class Error(val message: String, val cause: Throwable? = null) : EngineState

    /** Engine has been shut down and its native resources freed */
    data object Closed : EngineState
}

val EngineState.isModelReady: Boolean
    get() = this is EngineState.ModelLoaded || this is EngineState.Inferring

val EngineState.isBusy: Boolean
    get() = this is EngineState.Initializing ||
            this is EngineState.LoadingModel ||
            this is EngineState.Inferring
