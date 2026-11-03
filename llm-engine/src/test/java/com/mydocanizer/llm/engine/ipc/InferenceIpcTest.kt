package com.mydocanizer.llm.engine.ipc

import android.content.Context
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceResult
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

class InferenceIpcTest {

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private class FakeInferenceService : IInferenceService {
        override fun asBinder(): android.os.IBinder? = null

        val initialized = AtomicBoolean(false)
        val loadedModel = AtomicReference<String?>(null)
        val lastRequestId = AtomicReference<String?>(null)
        val cancelledRequestId = AtomicReference<String?>(null)
        val isShutdown = AtomicBoolean(false)

        override fun initialize() {
            initialized.set(true)
        }

        override fun loadModel(modelPath: String, config: EngineConfig, callback: IInferenceCallback?) {
            loadedModel.set(modelPath)
            val info = ModelInfo(
                name = File(modelPath).name,
                path = modelPath,
                sizeBytes = 1024L * 1024L,
                parameterCount = 500_000_000L,
                activeBackend = "CPU (Fake)"
            )
            callback?.onModelLoaded(info)
        }

        override fun unloadModel(callback: IInferenceCallback?) {
            loadedModel.set(null)
            callback?.onModelLoaded(null)
        }

        override fun streamChat(requestId: String, request: InferenceRequest, callback: IInferenceCallback?) {
            lastRequestId.set(requestId)
            callback?.onStarted(requestId, request.messages.size)
            callback?.onProgress(requestId, 1, 10L)
            callback?.onToken(requestId, "Hallo", 0)
            callback?.onToken(requestId, " Welt", 1)

            val result = InferenceResult(
                text = "Hallo Welt",
                tokensGenerated = 2,
                durationMs = 25L,
                tokensPerSecond = 80.0,
                finishReason = FinishReason.STOP
            )
            callback?.onCompleted(requestId, result)
        }

        override fun cancelInference(requestId: String?) {
            cancelledRequestId.set(requestId)
        }

        override fun getStatus(): String = if (loadedModel.get() != null) "MODEL_LOADED" else "READY"

        override fun getBackendInfo(): BackendInfo = BackendInfo(
            backendName = "llama.cpp IPC Mock",
            systemInfo = "TestProcess",
            isAvailable = true,
            recommendedThreads = 4
        )

        override fun getProcessId(): Int = 99999

        override fun shutdown() {
            isShutdown.set(true)
        }
    }

    @Test
    fun testMockIpcClientInitializationAndQueries() = runBlocking {
        val fakeService = FakeInferenceService()
        // Using null Context because mockService bypasses bindService
        val client = InferenceServiceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService
        )

        client.initialize()
        assertTrue(fakeService.initialized.get())
        assertEquals(99999, client.remotePid.value)
        assertEquals("llama.cpp IPC Mock", client.backendInfo.value.backendName)
    }

    @Test
    fun testMockIpcClientModelLoadingAndChat() = runBlocking {
        val fakeService = FakeInferenceService()
        val tempFile = File.createTempFile("test_model", ".gguf").apply {
            writeBytes(ByteArray(100))
            deleteOnExit()
        }

        val client = InferenceServiceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService
        )

        val loadResult = client.loadModel(ModelSource.FilePath(tempFile.absolutePath), EngineConfig())
        assertTrue(loadResult.isSuccess)
        val modelInfo = loadResult.getOrThrow()
        assertEquals(tempFile.name, modelInfo.name)
        assertEquals("CPU (Fake)", modelInfo.activeBackend)
        assertEquals(modelInfo, client.currentModel.value)

        val request = InferenceRequest.single("Sag Hallo")
        val events = client.streamChat(request).toList()

        assertTrue(events.any { it is InferenceEvent.Started })
        assertTrue(events.any { it is InferenceEvent.Progress })
        val tokens = events.filterIsInstance<InferenceEvent.Token>().map { it.token }
        assertEquals(listOf("Hallo", " Welt"), tokens)

        val completed = events.filterIsInstance<InferenceEvent.Completed>().firstOrNull()
        assertNotNull(completed)
        assertEquals("Hallo Welt", completed?.result?.text)
        assertEquals(2, completed?.result?.tokensGenerated)

        assertNotNull(fakeService.lastRequestId.get())

        client.cancelInference()
        assertNotNull(fakeService.cancelledRequestId.get())

        client.close()
        assertTrue(fakeService.isShutdown.get())
    }
}
