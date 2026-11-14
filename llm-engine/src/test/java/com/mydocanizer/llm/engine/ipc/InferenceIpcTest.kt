package com.mydocanizer.llm.engine.ipc

import android.content.Context
import android.os.ParcelFileDescriptor
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
import com.mydocanizer.llm.engine.ReloadAfterAbort
import com.mydocanizer.llm.engine.TimeoutConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@RunWith(RobolectricTestRunner::class)
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
        val loadedDescriptorSize = AtomicLong(-1L)
        val hangModelLoad = AtomicBoolean(false)
        val blockNextModelLoad = AtomicBoolean(false)
        val modelLoadEntered = CountDownLatch(1)
        val releaseModelLoad = CountDownLatch(1)
        val modelLoadCalls = AtomicInteger(0)
        val hangUnload = AtomicBoolean(false)
        val lastRequestId = AtomicReference<String?>(null)
        val cancelledRequestId = AtomicReference<String?>(null)
        val isShutdown = AtomicBoolean(false)

        val isKilled = AtomicBoolean(false)
        val acknowledgeCooperativeCancel = AtomicBoolean(false)
        val cancelRequestedAtNanos = AtomicLong(0L)
        val killRequestedAtNanos = AtomicLong(0L)
        val activeCallback = AtomicReference<IInferenceCallback?>(null)
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

        override fun loadModel(
            modelPath: String,
            modelDescriptor: ParcelFileDescriptor,
            displayName: String,
            config: EngineConfig,
            callback: IInferenceCallback?
        ) {
            modelLoadCalls.incrementAndGet()
            if (blockNextModelLoad.compareAndSet(true, false)) {
                modelLoadEntered.countDown()
                check(releaseModelLoad.await(5L, TimeUnit.SECONDS)) {
                    "Timed out waiting for test to release blocked model load"
                }
            }
            loadedModel.set(modelPath)
            loadedDescriptorSize.set(modelDescriptor.statSize)
            replacementProcessIdOnLoad.get().takeIf { it > 0 }?.let(currentProcessId::set)
            if (hangModelLoad.get()) return
            callback?.onModelLoadProgress(23)
            callback?.onModelLoadProgress(79)
            val info = ModelInfo(
                name = displayName,
                path = modelPath,
                sizeBytes = 1024L * 1024L,
                parameterCount = 500_000_000L,
                activeBackend = "CPU (Fake)"
            )
            callback?.onModelLoaded(info)
        }

        override fun unloadModel(callback: IInferenceCallback?) {
            if (hangUnload.get()) return
            loadedModel.set(null)
            callback?.onModelLoaded(null)
        }

        override fun streamChat(requestId: String, request: InferenceRequest, callback: IInferenceCallback?) {
            lastRequestId.set(requestId)
            activeCallback.set(callback)
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
                        // Keep the fake native operation blocked beyond the configured
                        // total-timeout + cooperative grace window so this test exercises
                        // the hard-abort branch rather than a natural completion during grace.
                        Thread.sleep(250L)
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
            cancelRequestedAtNanos.set(System.nanoTime())
            if (acknowledgeCooperativeCancel.get()) {
                activeCallback.get()?.onCancelled(requestId, "", 0)
            }
        }

        override fun armNativeInferenceBlockForTest() {}

        override fun killProcess() {
            killRequestedAtNanos.set(System.nanoTime())
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
    fun procStatTreatsZombieAndDeadStatesAsTerminated() = runBlocking {
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = FakeInferenceService()
        )

        assertFalse(client.isProcStatAlive("1234 (llama-inference) Z 1 2 3 4 5"))
        assertFalse(client.isProcStatAlive("1234 (llama ) worker) X 1 2 3 4 5"))
        assertTrue(client.isProcStatAlive("1234 (llama-inference) S 1 2 3 4 5"))
        assertTrue(client.isProcStatAlive("1234 (llama ) worker) R 1 2 3 4 5"))
        // Unknown/malformed stat data is conservative and must not cause a false kill confirmation.
        assertTrue(client.isProcStatAlive("not-a-proc-stat-line"))
        client.close()
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
    fun escalatedCancelTerminatesStreamAndPreservesPartialText() = runBlocking {
        val fakeService = FakeInferenceService().apply {
            hangAfterFirstToken.set(true)
        }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 1_000L,
            prefillMs = 500L,
            firstTokenMs = 1_000L,
            interTokenMs = 500L,
            totalInferenceMs = 2_000L,
            abortGracePeriodMs = 20L,
            reconnectMs = 1_000L
        )
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig,
            processLivenessCheck = { !fakeService.isKilled.get() }
        )
        client.initialize()

        val stream = async { client.streamChat(InferenceRequest.single("cancel after first token")).toList() }
        withTimeout(1_000L) {
            while (fakeService.lastRequestId.get() == null) delay(5L)
        }
        client.cancelInference()

        val events = withTimeout(1_000L) { stream.await() }
        val cancelled = events.filterIsInstance<InferenceEvent.Cancelled>().single()
        assertEquals("Hallo", cancelled.partialText)
        assertEquals(1, cancelled.tokensGenerated)
        withTimeout(1_000L) {
            while (!fakeService.isKilled.get()) delay(5L)
        }
        assertTrue("Non-cooperative cancellation must escalate to process kill", fakeService.isKilled.get())
        client.close()
    }

    @Test
    fun cancellingStreamCollectorEscalatesWhenServiceIgnoresCooperativeCancel() = runBlocking {
        val fakeService = FakeInferenceService().apply {
            hangAfterFirstToken.set(true)
        }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 1_000L,
            prefillMs = 500L,
            firstTokenMs = 1_000L,
            interTokenMs = 500L,
            totalInferenceMs = 2_000L,
            abortGracePeriodMs = 100L,
            reconnectMs = 1_000L
        )
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig,
            processLivenessCheck = { !fakeService.isKilled.get() }
        )
        client.initialize()

        val collector = launch {
            client.streamChat(InferenceRequest.single("collector cancellation")).collect {}
        }
        withTimeout(1_000L) {
            while (fakeService.lastRequestId.get() == null) delay(5L)
        }

        // Cancelling the Flow collector must still cancel native work and retain
        // the active request until the service acknowledges or the grace expires.
        collector.cancel()
        collector.join()

        withTimeout(2_000L) {
            while (!fakeService.isKilled.get()) delay(5L)
        }
        assertEquals(fakeService.lastRequestId.get(), fakeService.cancelledRequestId.get())
        assertTrue(fakeService.cancelRequestedAtNanos.get() > 0L)
        assertTrue(fakeService.killRequestedAtNanos.get() >= fakeService.cancelRequestedAtNanos.get())
        assertTrue("Non-cooperative collector cancellation must kill the original service process", fakeService.isKilled.get())
        client.close()
    }

    @Test
    fun cooperativeCancelCompletesStreamWithoutKillingAndKeepsPartialText() = runBlocking {
        val fakeService = FakeInferenceService().apply {
            hangAfterFirstToken.set(true)
            acknowledgeCooperativeCancel.set(true)
        }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 1_000L,
            prefillMs = 500L,
            firstTokenMs = 1_000L,
            interTokenMs = 500L,
            totalInferenceMs = 2_000L,
            abortGracePeriodMs = 100L,
            reconnectMs = 1_000L
        )
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )
        client.initialize()

        val stream = async { client.streamChat(InferenceRequest.single("cooperative cancel")).toList() }
        withTimeout(1_000L) {
            while (fakeService.lastRequestId.get() == null) delay(5L)
        }
        client.cancelInference()

        val events = withTimeout(1_000L) { stream.await() }
        val cancelled = events.filterIsInstance<InferenceEvent.Cancelled>().single()
        assertEquals("Hallo", cancelled.partialText)
        assertEquals(1, cancelled.tokensGenerated)
        assertTrue("Acknowledged cancellation must not kill the inference process", !fakeService.isKilled.get())
        client.close()
    }

    @Test
    fun unloadModelTimeoutReturnsFailureInsteadOfWaitingForever() = runBlocking {
        val fakeService = FakeInferenceService().apply { hangUnload.set(true) }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 60L,
            prefillMs = 50L,
            firstTokenMs = 150L,
            interTokenMs = 50L,
            totalInferenceMs = 300L,
            abortGracePeriodMs = 10L,
            reconnectMs = 1_000L
        )
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig,
            processLivenessCheck = { false }
        )
        client.initialize()

        val result = withTimeout(1_000L) { client.unloadModel() }
        assertTrue("Missing unload callback must return a bounded failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is InferenceTimeoutException)
        assertTrue("An unload timeout must abort the unresponsive inference process", fakeService.isKilled.get())
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
    fun reconnectModelReloadIsSerializedWithExplicitBackendChange() = runBlocking {
        val fakeService = FakeInferenceService()
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService
        )
        val originalFile = File.createTempFile("reconnect_original", ".gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(96))
            deleteOnExit()
        }
        val cpuFile = File.createTempFile("reconnect_cpu", ".gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(96))
            deleteOnExit()
        }
        // This test exercises the opt-in eager-reload compatibility path. LAZY is now
        // the default and is covered separately by restoreLastModelAfterReconnect tests.
        val originalConfig = EngineConfig(reloadAfterAbort = ReloadAfterAbort.EAGER)
        val cpuConfig = originalConfig.copy(backendPreference = com.mydocanizer.llm.engine.BackendPreference.CPU)

        try {
            val initial = client.loadModel(ModelSource.FilePath(originalFile.absolutePath), originalConfig)
            assertTrue("Initial model load must succeed", initial.isSuccess)
            assertEquals(1, fakeService.modelLoadCalls.get())

            fakeService.blockNextModelLoad.set(true)
            val recovery = async(kotlinx.coroutines.Dispatchers.IO) {
                client.restoreLastModelAfterReconnect(fakeService)
            }
            withTimeout(1_000L) {
                while (fakeService.modelLoadEntered.count != 0L) delay(5L)
            }

            // A CPU load submitted while automatic reconnect recovery is loading the
            // remembered Vulkan model must queue behind the same operation mutex.
            val explicitCpuLoad = async {
                client.loadModel(ModelSource.FilePath(cpuFile.absolutePath), cpuConfig)
            }
            delay(75L)
            assertEquals(
                "A second model load must not enter the service while recovery owns the load slot",
                2,
                fakeService.modelLoadCalls.get()
            )

            fakeService.releaseModelLoad.countDown()
            val recovered = withTimeout(2_000L) { recovery.await() }
            val cpuLoaded = withTimeout(2_000L) { explicitCpuLoad.await() }

            assertNotNull("A remembered model should be restored", recovered)
            assertTrue("Reconnect recovery load must succeed", recovered!!.isSuccess)
            assertTrue("The queued explicit CPU load must succeed", cpuLoaded.isSuccess)
            assertEquals("The explicit CPU model load must run after recovery", cpuFile.absolutePath, fakeService.loadedModel.get())
            assertEquals(3, fakeService.modelLoadCalls.get())
        } finally {
            fakeService.releaseModelLoad.countDown()
            client.close()
        }
    }

    @Test
    fun testMockIpcClientModelLoadingAndChat() = runBlocking {
        val fakeService = FakeInferenceService()
        val tempFile = File.createTempFile("test_model", ".gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(96))
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
        assertTrue("Mock FilePath model load failed: ${loadResult.exceptionOrNull()}", loadResult.isSuccess)
        progressJob.join()
        assertEquals(listOf(0, 23, 79, 100), progressEvents)
        val modelInfo = loadResult.getOrThrow()
        assertEquals(tempFile.name, modelInfo.name)
        assertEquals("The FilePath descriptor must reach the IPC service as an open file", 100L, fakeService.loadedDescriptorSize.get())
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
            timeoutConfig = TimeoutConfig.fast(),
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

        // Cooperative cancel is dispatched on the client's IO scope. Wait for the
        // Binder call to reach the fake before asserting its request ID.
        withTimeout(1_000L) {
            while (fakeService.cancelledRequestId.get() != fakeService.lastRequestId.get()) {
                delay(5L)
            }
        }
        // The fake deliberately does not acknowledge, so escalation must kill it.
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

        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 5_000L,
            prefillMs = 50L,
            firstTokenMs = 150L,
            interTokenMs = 150L,
            totalInferenceMs = 300L,
            abortGracePeriodMs = 100L,
            reconnectMs = 1_000L
        )

        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )

        val request = InferenceRequest.single("Prefill test")
        val events = client.streamChat(request).toList()

        // A timeout must first signal cooperative native cancellation.
        assertEquals(fakeService.lastRequestId.get(), fakeService.cancelledRequestId.get())
        assertTrue(fakeService.cancelRequestedAtNanos.get() > 0L)

        // Hard process termination is only permitted after the configured grace period.
        assertTrue(fakeService.isKilled.get())
        val elapsedGraceNanos =
            fakeService.killRequestedAtNanos.get() - fakeService.cancelRequestedAtNanos.get()
        assertTrue(
            "Timeout must wait abortGracePeriodMs before hard kill; elapsed=$elapsedGraceNanos ns",
            elapsedGraceNanos >= timeoutConfig.abortGracePeriodMs * 1_000_000L
        )

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
    fun prefillTimeoutStopsAfterCooperativeCancellationAcknowledgement() = runBlocking {
        val fakeService = FakeInferenceService().apply {
            hangBeforeStarted.set(true)
            acknowledgeCooperativeCancel.set(true)
        }
        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 1_000L,
            modelLoadMs = 5_000L,
            prefillMs = 50L,
            firstTokenMs = 150L,
            interTokenMs = 150L,
            totalInferenceMs = 300L,
            abortGracePeriodMs = 150L,
            reconnectMs = 1_000L
        )
        val client = InferenceClient(
            context = object : android.content.ContextWrapper(null) {},
            mockService = fakeService,
            timeoutConfig = timeoutConfig
        )

        val events = client.streamChat(InferenceRequest.single("Cooperative timeout test")).toList()

        assertEquals(fakeService.lastRequestId.get(), fakeService.cancelledRequestId.get())
        assertTrue("A cooperative cancellation acknowledgement must avoid hard process kill", !fakeService.isKilled.get())
        val errorEvent = events.filterIsInstance<InferenceEvent.Error>().firstOrNull()
        assertNotNull(errorEvent)
        assertTrue(errorEvent?.throwable is InferenceTimeoutException)
        assertEquals(
            FinishReason.TIMEOUT_PREFILL,
            (errorEvent?.throwable as InferenceTimeoutException).reason
        )

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
