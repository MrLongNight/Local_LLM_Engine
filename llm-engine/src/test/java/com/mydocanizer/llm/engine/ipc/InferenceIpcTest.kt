package com.mydocanizer.llm.engine.ipc

import android.content.Context
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.InferenceResult
import com.mydocanizer.llm.engine.InferenceTimeoutException
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.TimeoutConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
        val hangModelLoad = AtomicBoolean(false)
        val lastRequestId = AtomicReference<String?>(null)
        val cancelledRequestId = AtomicReference<String?>(null)
        val isShutdown = AtomicBoolean(false)

        val isKilled = AtomicBoolean(false)
        val currentProcessId = AtomicInteger(99999)
        val replacementProcessIdOnLoad = AtomicInteger(0)
        val shouldHangInference = AtomicBoolean(false)
        val hangBeforeStarted = AtomicBoolean(false)
        val hangAfterStartedBeforeToken = AtomicBoolean(false)
        val hangAfterFirstToken = AtomicBoolean(false)
        val slowStreaming = AtomicBoolean(false)
        val delayedPrefillMs = AtomicLong(0L)
        val delayedFirstTokenMs = AtomicLong(0L)

        override fun initialize() {
            initialized.set(true)
        }

        override fun loadModel(modelPath: String, config: EngineConfig, callback: IInferenceCallback?) {
            loadedModel.set(modelPath)
            replacementProcessIdOnLoad.get().takeIf { it > 0 }?.let(currentProcessId::set)
            if (hangModelLoad.get()) return
            callback?.onModelLoadProgress(23)
            callback?.onModelLoadProgress(79)
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
            if (hangBeforeStarted.get()) {
                return
            }

            val prefillDelay = delayedPrefillMs.get()
            val firstTokenDelay = delayedFirstTokenMs.get()
            if (prefillDelay > 0L || firstTokenDelay > 0L) {
                kotlin.concurrent.thread {
                    try {
                        Thread.sleep(prefillDelay)
                        callback?.onStarted(requestId, request.messages.size)
                        Thread.sleep(firstTokenDelay)
                        callback?.onProgress(requestId, 1, prefillDelay + firstTokenDelay)
                        callback?.onToken(requestId, "Hallo", 0)
                        callback?.onCompleted(
                            requestId,
                            InferenceResult(
                                text = "Hallo",
                                tokensGenerated = 1,
                                durationMs = prefillDelay + firstTokenDelay,
                                tokensPerSecond = 1000.0 / (prefillDelay + firstTokenDelay).coerceAtLeast(1L),
                                finishReason = FinishReason.STOP
                            )
                        )
                    } catch (ignored: Exception) {}
                }
                return
            }

            callback?.onPromptProgress(requestId, 2, 4, 5L)
            callback?.onStarted(requestId, request.messages.size)
            if (shouldHangInference.get() || hangAfterStartedBeforeToken.get()) {
                return
            }
            callback?.onProgress(requestId, 1, 10L)
            callback?.onToken(requestId, "Hallo", 0)
            if (hangAfterFirstToken.get()) {
                return
            }
            if (slowStreaming.get()) {
                kotlin.concurrent.thread {
                    try {
                        Thread.sleep(30L)
                        callback?.onToken(requestId, " Welt", 1)
                        Thread.sleep(100L)
                        val result = InferenceResult(
                            text = "Hallo Welt",
                            tokensGenerated = 2,
                            durationMs = 130L,
                            tokensPerSecond = 15.0,
                            finishReason = FinishReason.STOP
                        )
                        callback?.onCompleted(requestId, result)
                    } catch (ignored: Exception) {}
                }
                return
            }

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

        override fun killProcess() {
            isKilled.set(true)
        }

        override fun getStatus(): String = if (loadedModel.get() != null) "MODEL_LOADED" else "READY"

        override fun getBackendInfo(): BackendInfo = BackendInfo(
            backendName = "llama.cpp IPC Mock",
            systemInfo = "TestProcess",
            isAvailable = true,
            recommendedThreads = 4
        )

        override fun getProcessId(): Int = if (isKilled.get()) 0 else currentProcessId.get()

        override fun shutdown() {
            isShutdown.set(true)
        }
    }

    @Test
    fun cancelWithoutActiveInferenceDoesNotKillRemoteProcess() = runBlocking {
        val fakeService = FakeInferenceService()
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService
        )

        client.initialize()
        assertTrue(fakeService.initialized.get())
        assertEquals(99999, client.remotePid.value)

        client.cancelInference()

        assertTrue("Idle cancel must preserve the inference process", !fakeService.isKilled.get())
        assertEquals(99999, client.remotePid.value)
        client.close()
    }

    @Test
    fun modelLoadTimeoutKillsHungInferenceProcessWithoutInferenceRequestId() = runBlocking {
        val fakeService = FakeInferenceService().apply { hangModelLoad.set(true) }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 50L,
            prefillMs = 50L,
            firstTokenMs = 150L,
            interTokenMs = 50L,
            totalInferenceMs = 300L,
            abortGracePeriodMs = 10L,
            reconnectMs = 1_000L
        )
        val modelFile = File.createTempFile("hung_model", ".gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            deleteOnExit()
        }
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )

        val result = client.loadModel(
            ModelSource.FilePath(modelFile.absolutePath),
            EngineConfig(timeoutConfig = timeoutConfig)
        )

        assertTrue("Stalled model loading must fail by its deadline", result.isFailure)
        assertTrue(result.exceptionOrNull() is InferenceTimeoutException)
        assertTrue("A model-load timeout must abort the isolated process", fakeService.isKilled.get())
        client.close()
    }


    @Test
    fun modelLoadTimeoutNeverKillsReplacementInferenceProcess() = runBlocking {
        val fakeService = FakeInferenceService().apply {
            hangModelLoad.set(true)
            replacementProcessIdOnLoad.set(22222)
        }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 50L,
            prefillMs = 50L,
            firstTokenMs = 150L,
            interTokenMs = 50L,
            totalInferenceMs = 300L,
            abortGracePeriodMs = 10L,
            reconnectMs = 1_000L
        )
        val modelFile = File.createTempFile("replacement_model", ".gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            deleteOnExit()
        }
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig,
            processLivenessCheck = { false }
        )

        client.initialize()
        assertEquals(99999, client.remotePid.value)

        val result = client.loadModel(
            ModelSource.FilePath(modelFile.absolutePath),
            EngineConfig(timeoutConfig = timeoutConfig)
        )

        assertTrue("The original hung load must still time out", result.isFailure)
        assertTrue(result.exceptionOrNull() is InferenceTimeoutException)
        assertEquals("The fake service now represents the replacement process", 22222, fakeService.processId)
        assertTrue(
            "A timeout from the old process must not kill the replacement service",
            !fakeService.isKilled.get()
        )
        client.close()
    }

    @Test
    fun concurrentStreamIsRejectedWithoutReplacingFirstRequestWatchdog() = runBlocking {
        val fakeService = FakeInferenceService().apply { hangBeforeStarted.set(true) }
        val timeoutConfig = TimeoutConfig.fast(
            prefillMs = 2_000L,
            firstTokenMs = 2_000L,
            interTokenMs = 2_000L,
            totalInferenceMs = 4_000L
        )
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )
        val first = launch { client.streamChat(InferenceRequest.single("first")).collect {} }
        withTimeout(1_000L) {
            while (fakeService.lastRequestId.get() == null) delay(5L)
        }

        val secondEvents = client.streamChat(InferenceRequest.single("second")).toList()
        assertTrue(secondEvents.single() is InferenceEvent.Error)
        assertTrue("The rejected stream must not abort the active request", !fakeService.isKilled.get())
        first.cancel()
        client.close()
    }

    @Test
    fun testMockIpcClientInitializationAndQueries() = runBlocking {
        val fakeService = FakeInferenceService()
        val client = InferenceClient(
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

        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService
        )

        val progressEvents = mutableListOf<Int>()
        val progressJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            client.modelLoadProgressEvents.take(4).toList(progressEvents)
        }

        val loadResult = client.loadModel(ModelSource.FilePath(tempFile.absolutePath), EngineConfig())
        assertTrue(loadResult.isSuccess)
        progressJob.join()
        assertEquals(listOf(0, 23, 79, 100), progressEvents)
        val modelInfo = loadResult.getOrThrow()
        assertEquals(tempFile.name, modelInfo.name)
        assertEquals("CPU (Fake)", modelInfo.activeBackend)
        assertEquals(modelInfo, client.currentModel.value)
        assertEquals(100, client.modelLoadProgress.value)

        val request = InferenceRequest.single("Sag Hallo")
        val events = client.streamChat(request).toList()

        assertTrue(events.any { it is InferenceEvent.Started })
        assertTrue(events.any {
            it is InferenceEvent.Progress &&
                it.phase == InferenceEvent.ProgressPhase.PROMPT_EVALUATION &&
                it.promptTokensProcessed == 2 &&
                it.promptTokensTotal == 4
        })
        assertTrue(events.any {
            it is InferenceEvent.Progress &&
                it.phase == InferenceEvent.ProgressPhase.GENERATING &&
                it.tokensGenerated > 0
        })
        val tokens = events.filterIsInstance<InferenceEvent.Token>().map { it.token }
        assertEquals(listOf("Hallo", " Welt"), tokens)

        val completed = events.filterIsInstance<InferenceEvent.Completed>().firstOrNull()
        assertNotNull(completed)
        assertEquals("Hallo Welt", completed?.result?.text)
        assertEquals(2, completed?.result?.tokensGenerated)

        assertNotNull(fakeService.lastRequestId.get())

        client.close()
        assertTrue(fakeService.isShutdown.get())
    }

    @Test
    fun testBlockingInferenceProcessKillAndRecovery() = runBlocking {
        val fakeService = FakeInferenceService()
        fakeService.shouldHangInference.set(true)

        val initialServicePid = 99999

        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            processLivenessCheck = { pid ->
                pid == initialServicePid && !fakeService.isKilled.get()
            }
        )

        val appPid = android.os.Process.myPid()

        // 1. Start blocking inference in background coroutine
        val request = InferenceRequest.single("Long running query")
        // Start collecting undispatched so the fake service has received the request
        // before the explicit cancellation is issued. This test must not depend on scheduler timing.
        val flowJob = launch {
            client.streamChat(request).collect {
                // Ingest events
            }
        }
        withTimeout(1_000L) {
            while (fakeService.lastRequestId.get() == null) {
                delay(5L)
            }
        }
        assertNotNull("The inference request must be active before cancellation", fakeService.lastRequestId.get())

        // 2. Trigger process-level cancelInference()
        client.cancelInference()

        // Cooperative cancel is sent first; this fake deliberately does not acknowledge.
        assertEquals(fakeService.lastRequestId.get(), fakeService.cancelledRequestId.get())
        withTimeout(1_000L) {
            while (!fakeService.isKilled.get()) delay(5L)
        }
        assertTrue(fakeService.isKilled.get())

        // 4. Verify PID of service process is no longer alive
        withTimeout(1_000L) {
            while (client.isProcessAlive(initialServicePid)) {
                delay(5L)
            }
        }
        org.junit.Assert.assertFalse(client.isProcessAlive(initialServicePid))

        // 5. Verify App Process PID is intact and unaffected
        assertEquals(appPid, android.os.Process.myPid())

        // 6. Resume service with new PID and perform new inference successfully
        fakeService.isKilled.set(false)
        fakeService.shouldHangInference.set(false)

        val recoveryResult = client.generateChat(InferenceRequest.single("Neue Inferenz nach Kill"))
        assertTrue(recoveryResult.isSuccess)
        assertEquals("Hallo Welt", recoveryResult.getOrThrow().text)

        flowJob.cancel()
        client.close()
    }

    @Test
    fun testPrefillTimeoutTriggersProcessKillAndReportsFinishReason() = runBlocking {
        val fakeService = FakeInferenceService()
        fakeService.hangBeforeStarted.set(true)

        val timeoutConfig = TimeoutConfig.fast(
            prefillMs = 50L,
            firstTokenMs = 150L,
            interTokenMs = 150L,
            totalInferenceMs = 300L
        )

        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )

        val request = InferenceRequest.single("Prefill test")
        val events = client.streamChat(request).toList()

        // 1. Ultima ratio process kill was invoked
        assertTrue(fakeService.isKilled.get())

        // Timeout is a failure event, never a successful completion.
        assertTrue(events.none { it is InferenceEvent.Completed })

        // 3. Error event contains InferenceTimeoutException
        val errorEvent = events.filterIsInstance<InferenceEvent.Error>().firstOrNull()
        assertNotNull(errorEvent)
        assertTrue(errorEvent?.throwable is InferenceTimeoutException)
        val ex = errorEvent?.throwable as InferenceTimeoutException
        assertEquals(FinishReason.TIMEOUT_PREFILL, ex.reason)

        client.close()
    }

    @Test
    fun firstTokenDeadlineStartsAfterPromptPrefillInsteadOfRequestStart() = runBlocking {
        val fakeService = FakeInferenceService().apply {
            delayedPrefillMs.set(80L)
            delayedFirstTokenMs.set(80L)
        }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 5_000L,
            prefillMs = 150L,
            firstTokenMs = 150L,
            interTokenMs = 150L,
            totalInferenceMs = 500L,
            abortGracePeriodMs = 100L,
            reconnectMs = 1_000L
        )
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )

        // Total latency is 160 ms (> firstTokenMs 150 ms), but prompt prefill takes
        // only 80 ms and the first token arrives 80 ms after prefill. It must succeed.
        val events = client.streamChat(InferenceRequest.single("Delayed prefill")).toList()

        assertTrue("A valid slow prefill must not be killed by the first-token deadline", !fakeService.isKilled.get())
        assertTrue(events.any { it is InferenceEvent.Started })
        assertEquals(listOf("Hallo"), events.filterIsInstance<InferenceEvent.Token>().map { it.token })
        assertTrue(events.any { it is InferenceEvent.Completed })
        client.close()
    }

    @Test
    fun testFirstTokenTimeoutTriggersProcessKillAndReportsFinishReason() = runBlocking {
        val fakeService = FakeInferenceService()
        fakeService.hangAfterStartedBeforeToken.set(true)

        val timeoutConfig = TimeoutConfig.fast(
            prefillMs = 40L,
            firstTokenMs = 50L,
            interTokenMs = 150L,
            totalInferenceMs = 300L
        )

        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )

        val request = InferenceRequest.single("First token test")
        val events = client.streamChat(request).toList()

        // 1. Ultima ratio process kill was invoked
        assertTrue(fakeService.isKilled.get())

        // 2. Started event was received before timeout
        assertTrue(events.any { it is InferenceEvent.Started })

        // Timeout is a failure event, never a successful completion.
        assertTrue(events.none { it is InferenceEvent.Completed })

        val errorEvent = events.filterIsInstance<InferenceEvent.Error>().firstOrNull()
        assertNotNull(errorEvent)
        assertEquals(FinishReason.TIMEOUT_FIRST_TOKEN, (errorEvent?.throwable as? InferenceTimeoutException)?.reason)

        client.close()
    }

    @Test
    fun testInterTokenTimeoutTriggersProcessKillAndReportsFinishReason() = runBlocking {
        val fakeService = FakeInferenceService()
        fakeService.hangAfterFirstToken.set(true)

        val timeoutConfig = TimeoutConfig.fast(
            prefillMs = 150L,
            firstTokenMs = 150L,
            interTokenMs = 50L,
            totalInferenceMs = 300L
        )

        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )

        val request = InferenceRequest.single("Inter token test")
        val events = client.streamChat(request).toList()

        // 1. First token was received
        val tokens = events.filterIsInstance<InferenceEvent.Token>().map { it.token }
        assertEquals(listOf("Hallo"), tokens)

        // 2. Ultima ratio process kill was invoked
        assertTrue(fakeService.isKilled.get())

        // Timeout is a failure event, never a successful completion.
        assertTrue(events.none { it is InferenceEvent.Completed })

        val errorEvent = events.filterIsInstance<InferenceEvent.Error>().firstOrNull()
        assertNotNull(errorEvent)
        assertEquals(FinishReason.TIMEOUT_INTER_TOKEN, (errorEvent?.throwable as? InferenceTimeoutException)?.reason)
        assertEquals("Hallo", errorEvent?.partialText)

        client.close()
    }

    @Test
    fun testTotalInferenceTimeoutTriggersProcessKillAndReportsFinishReason() = runBlocking {
        val fakeService = FakeInferenceService()
        fakeService.slowStreaming.set(true)

        val timeoutConfig = TimeoutConfig.fast(
            prefillMs = 50L,
            firstTokenMs = 50L,
            interTokenMs = 60L,
            totalInferenceMs = 70L
        )

        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )

        val request = InferenceRequest.single("Total inference test")
        val events = client.streamChat(request).toList()

        // 1. Ultima ratio process kill was invoked
        assertTrue(fakeService.isKilled.get())

        // Timeout is a failure event, never a successful completion.
        assertTrue(events.none { it is InferenceEvent.Completed })

        val errorEvent = events.filterIsInstance<InferenceEvent.Error>().firstOrNull()
        assertNotNull(errorEvent)
        assertEquals(FinishReason.TIMEOUT_TOTAL_INFERENCE, (errorEvent?.throwable as? InferenceTimeoutException)?.reason)

        client.close()
    }
}
