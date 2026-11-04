package com.mydocanizer.llm.engine

import android.content.Context
import com.mydocanizer.llm.engine.ipc.IInferenceService
import com.mydocanizer.llm.engine.ipc.InferenceClient
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Public IPC client encapsulating the AIDL communication to the isolated :inference process.
 */
typealias InferenceClient = com.mydocanizer.llm.engine.ipc.InferenceClient
typealias InferenceServiceClient = com.mydocanizer.llm.engine.ipc.InferenceClient
