package com.mydocanizer.llm.engine.ipc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.FinishReason
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.TimeoutConfig
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GrammarConstraintTest : InferenceServiceProcessTestBase() {

    @Test
    fun validGrammarConstrainsOutputAndInvalidGrammarKeepsEngineUsable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelFile = File(context.cacheDir, "grammar-constraint-tiny1m.gguf")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("tiny1m.Q4_K_M.gguf").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }

        val timeoutConfig = TimeoutConfig(
            serviceBindMs = 15_000L,
            modelLoadMs = 45_000L,
            prefillMs = 30_000L,
            firstTokenMs = 30_000L,
            interTokenMs = 10_000L,
            totalInferenceMs = 60_000L,
            abortGracePeriodMs = 3_000L,
            reconnectMs = 15_000L
        )
        val config = EngineConfig(
            contextSize = 256,
            batchSize = 64,
            threads = 2,
            temperature = 0.0f,
            topP = 1.0f,
            maxPredictTokens = 16,
            backendPreference = BackendPreference.CPU,
            gpuLayers = 0,
            microBatchSize = 16,
            timeoutConfig = timeoutConfig
        )
        val client = InferenceClient(context, timeoutConfig = timeoutConfig)
        try {
            val initialized = client.initialize()
            assertTrue("Inference service must initialize: ${initialized.exceptionOrNull()}", initialized.isSuccess)
            val loaded = client.loadModel(ModelSource.FilePath(modelFile.absolutePath), config)
            assertTrue("Tiny CPU GGUF must load: ${loaded.exceptionOrNull()}", loaded.isSuccess)
            assertTrue("The grammar regression must use CPU", loaded.getOrThrow().activeBackend.contains("CPU", true))
            val servicePid = client.remotePid.value
                ?: throw AssertionError("Inference service PID was not published")

            val validEvents = withTimeout(timeoutConfig.totalInferenceMs + 5_000L) {
                client.streamChat(
                    InferenceRequest.single(
                        "Return the single letter A.",
                        maxPredictTokens = 8,
                        grammar = "root ::= \"A\""
                    ).copy(temperature = 0.0f)
                ).toList()
            }
            val validResult = validEvents.filterIsInstance<InferenceEvent.Completed>().single().result
            assertEquals("The GBNF grammar must constrain the generated output", "A", validResult.text)
            assertTrue(
                "Constrained generation must finish normally: ${validResult.finishReason}",
                validResult.finishReason == FinishReason.STOP ||
                    validResult.finishReason == FinishReason.MAX_TOKENS
            )
            assertTrue("The constrained request must emit at least one token", validResult.tokensGenerated > 0)
            assertEquals("Valid grammar must not restart the service", servicePid, client.remotePid.value)

            repeat(3) {
                val invalidEvents = withTimeout(timeoutConfig.totalInferenceMs + 5_000L) {
                    client.streamChat(
                        InferenceRequest.single("Return anything.", maxPredictTokens = 8)
                            .copy(grammar = "root ::= (")
                    ).toList()
                }
                val failures = invalidEvents.filterIsInstance<InferenceEvent.Error>()
                assertEquals("Invalid GBNF must produce exactly one inference error", 1, failures.size)
                assertTrue(
                    "The error must identify invalid grammar: ${failures.single().throwable.message}",
                    failures.single().throwable.message.orEmpty().contains("GBNF", ignoreCase = true)
                )
                assertTrue("Invalid grammar must not report a completion", invalidEvents.none { it is InferenceEvent.Completed })
                assertEquals("Invalid grammar must not kill/restart the native process", servicePid, client.remotePid.value)
            }

            val followUpEvents = withTimeout(timeoutConfig.totalInferenceMs + 5_000L) {
                client.streamChat(
                    InferenceRequest.single("Reply with one short word: OK.", maxPredictTokens = 12)
                        .copy(temperature = 0.0f)
                ).toList()
            }
            val followUp = followUpEvents.filterIsInstance<InferenceEvent.Completed>().single().result
            assertTrue("An unconstrained request after grammar failure must still return text", followUp.text.isNotBlank())
            assertTrue("The follow-up must generate tokens", followUp.tokensGenerated > 0)
            assertTrue(
                "The follow-up must finish normally",
                followUp.finishReason == FinishReason.STOP || followUp.finishReason == FinishReason.MAX_TOKENS
            )
            assertEquals("The follow-up must use the same inference process", servicePid, client.remotePid.value)
        } finally {
            client.close()
            modelFile.delete()
        }
    }
}
