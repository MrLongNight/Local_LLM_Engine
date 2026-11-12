package com.mydocanizer.llm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.mydocanizer.llm.engine.ipc.InferenceClient
import org.junit.Test

class LocalLlmEngineApiTest {

    @Test
    fun testEngineConfigDefaults() {
        val config = EngineConfig()
        assertEquals(4096, config.contextSize)
        assertEquals(512, config.batchSize)
        assertEquals(4, config.threads)
        assertEquals(0.7f, config.temperature, 0.001f)
        assertEquals(1024, config.maxPredictTokens)
        assertEquals(BackendPreference.AUTO, config.backendPreference)
        assertEquals(99, config.gpuLayers)
        assertEquals(0, config.batchThreads) // raw setting: follow n_threads
        assertEquals(0, config.microBatchSize) // raw setting: follow n_batch
        assertEquals(4, config.effectiveBatchThreads)
        assertEquals(512, config.effectiveMicroBatchSize)
        assertEquals(20, config.topK)
        assertEquals(0.05f, config.minP, 0.0001f)
    }

    @Test
    fun testIndependentBatchParameters() {
        val config = EngineConfig(batchSize = 128, threads = 6, batchThreads = 2, microBatchSize = 32)
        assertEquals(6, config.threads)
        assertEquals(2, config.batchThreads)
        assertEquals(32, config.microBatchSize)
        assertEquals(2, config.effectiveBatchThreads)
        assertEquals(32, config.effectiveMicroBatchSize)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectNegativeBatchThreads() {
        EngineConfig(batchThreads = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectNegativeMicroBatchSize() {
        EngineConfig(microBatchSize = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInvalidSamplerTopK() {
        EngineConfig(topK = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInvalidSamplerMinP() {
        EngineConfig(minP = Float.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectMicroBatchLargerThanBatchSize() {
        EngineConfig(batchSize = 64, microBatchSize = 128)
    }


    @Test
    fun testChatMessageCreation() {
        val sysMsg = ChatMessage.system("System instruction")
        val userMsg = ChatMessage.user("Hello model")
        val assistantMsg = ChatMessage.assistant("Hello user")

        assertEquals(ChatRole.SYSTEM, sysMsg.role)
        assertEquals(ChatRole.USER, userMsg.role)
        assertEquals(ChatRole.ASSISTANT, assistantMsg.role)
        assertEquals("Hello model", userMsg.content)
    }

    @Test
    fun testInferenceRequest() {
        val request = InferenceRequest.single("Explain quantum computing", "You are a physicist")
        assertEquals(2, request.messages.size)
        assertEquals(ChatRole.SYSTEM, request.messages[0].role)
        assertEquals(ChatRole.USER, request.messages[1].role)
        assertEquals(1024, request.maxPredictTokens)
        assertEquals(false, request.enableThinking)

        val thinkingRequest = InferenceRequest.single(
            userPrompt = "Reason about this carefully",
            enableThinking = true
        )
        assertEquals(true, thinkingRequest.enableThinking)
    }


    @Test
    fun inferenceRequestParcelDefaultsThinkingToFalseForLegacyPayload() {
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeTypedList(listOf(ChatMessage.user("legacy request")))
            parcel.writeInt(128)
            parcel.writeByte(0) // temperature absent
            parcel.writeByte(0) // topP absent
            parcel.writeParcelable(null, 0) // timeout config absent; legacy layout ends here
            parcel.setDataPosition(0)

            val restored = InferenceRequest.CREATOR.createFromParcel(parcel)
            assertEquals(false, restored.enableThinking)
            assertEquals("legacy request", restored.messages.single().content)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun testInferenceRequestAcceptsSamplingOverrides() {
        val request = InferenceRequest(
            messages = listOf(ChatMessage.user("test")),
            temperature = 0.25f,
            topP = 0.8f
        )
        assertEquals(0.25f, request.temperature!!, 0.0001f)
        assertEquals(0.8f, request.topP!!, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInferenceRequestRejectsNonFiniteTemperature() {
        InferenceRequest(
            messages = listOf(ChatMessage.user("test")),
            temperature = Float.NaN
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInferenceRequestRejectsOutOfRangeTopP() {
        InferenceRequest(
            messages = listOf(ChatMessage.user("test")),
            topP = 1.01f
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testEngineConfigRejectsOutOfRangeTopP() {
        EngineConfig(topP = -0.1f)
    }

    @Test
    fun testModelInfoFormatting() {
        val info = ModelInfo(
            name = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            path = "/data/models/qwen2.5.gguf",
            sizeBytes = 350 * 1024 * 1024L,
            parameterCount = 490_000_000L,
            contextLengthTrain = 32768,
            architecture = "qwen2",
            description = "Qwen2 0.5B model"
        )
        assertEquals("490.0M", info.formattedParamCount)
        assertTrue(info.sizeMb > 349.0 && info.sizeMb < 351.0)
    }

    @Test
    fun testInferenceEvents() {
        val tokenEvent = InferenceEvent.Token("token", 0)
        assertEquals("token", tokenEvent.token)
        assertEquals(0, tokenEvent.tokenIndex)

        val result = InferenceResult(
            text = "Completed generation",
            tokensGenerated = 20,
            durationMs = 500,
            tokensPerSecond = 40.0,
            finishReason = FinishReason.STOP
        )
        val completedEvent = InferenceEvent.Completed(result)
        assertEquals(20, completedEvent.result.tokensGenerated)
        assertEquals(FinishReason.STOP, completedEvent.result.finishReason)
    }

    @Test
    fun testBackendInfo() {
        val info = BackendInfo(
            backendName = "llama.cpp (ARM/x86 NEON/KleidiAI)",
            systemInfo = "AVX = 1 | NEON = 1",
            isAvailable = true,
            recommendedThreads = 4
        )
        assertTrue(info.isAvailable)
        assertEquals(4, info.recommendedThreads)
    }

    @Test
    fun testEngineStateReconnecting() {
        val state: EngineState = EngineState.Reconnecting
        assertTrue(state.isBusy)
        org.junit.Assert.assertFalse(state.isModelReady)
    }

    @Test
    fun testIpcClientImplementsEngineApi() {
        val fakeService = object : com.mydocanizer.llm.engine.ipc.IInferenceService {
            override fun asBinder(): android.os.IBinder? = null
            override fun initialize() {}
            override fun loadModel(
                p0: String?,
                p1: android.os.ParcelFileDescriptor?,
                p2: String?,
                p3: EngineConfig?,
                p4: com.mydocanizer.llm.engine.ipc.IInferenceCallback?
            ) {}
            override fun unloadModel(p0: com.mydocanizer.llm.engine.ipc.IInferenceCallback?) {}
            override fun streamChat(p0: String?, p1: InferenceRequest?, p2: com.mydocanizer.llm.engine.ipc.IInferenceCallback?) {}
            override fun cancelInference(p0: String?) {}
            override fun armNativeInferenceBlockForTest() {}
            override fun killProcess() {}
            override fun getStatus(): String = "READY"
            override fun getBackendInfo(): BackendInfo = BackendInfo()
            override fun getProcessId(): Int = 1234
            override fun shutdown() {}
        }
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            },
            mockService = fakeService
        )
        assertNotNull(client)
        assertTrue(client is LocalLlmEngine)
        client.close()
    }
}
