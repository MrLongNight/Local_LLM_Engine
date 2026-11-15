package com.example.ui

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.viewModelScope
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.ChatRole
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.GdnPolicy
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ModelSource
import com.mydocanizer.llm.engine.ModelLibrary
import android.provider.OpenableColumns
import com.mydocanizer.llm.engine.InferenceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration smoke-test state, cancellation and inference sampling.
 */
abstract class ConfigurationSmokeViewModel(
    application: Application,
    engine: LocalLlmEngine
) : VulkanDiagnosticsViewModel(application, engine) {

    private val _isConfigSmokeTesting = MutableStateFlow(false)
    val isConfigSmokeTesting: StateFlow<Boolean> = _isConfigSmokeTesting.asStateFlow()

    private val _configSmokeReport = MutableStateFlow<String?>(null)
    val configSmokeReport: StateFlow<String?> = _configSmokeReport.asStateFlow()

    private var configurationSmokeJob: Job? = null

    /**
     * Short on-device screening of independent llama.cpp context parameters.
     * The same model/backend and fixed prompt are used for every profile. Context
     * settings require a model reload; original settings are restored in finally.
     * Prompt and generated response content are never written to diagnostics.
     */
    fun runConfigurationSmokeTest() {
        if (_isBenchmarking.value || _isConfigSmokeTesting.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Konfigurationstest erst starten, wenn keine Inferenz oder Modell-Operation läuft."
            return
        }
        val model = currentModel.value
        if (model == null) {
            _errorMessage.value = "Bitte zuerst ein GGUF-Modell laden."
            return
        }

        val originalConfig = _engineConfig.value
        val source = if (model.path.startsWith("content://", ignoreCase = true)) {
            ModelSource.ContentUri(model.path, model.name)
        } else {
            ModelSource.FilePath(model.path)
        }
        val profiles = InferenceConfigSmokePlan.create(
            originalConfig,
            Runtime.getRuntime().availableProcessors(),
            activeBackend = model.activeBackend
        )
        if (profiles.size < 2) {
            _errorMessage.value = "Keine abweichenden Testprofile für diese Konfiguration gefunden."
            return
        }

        _isBenchmarking.value = true
        _isConfigSmokeTesting.value = true
        _configSmokeReport.value = "Konfigurationstest wird vorbereitet…"
        _errorMessage.value = null

        configurationSmokeJob = viewModelScope.launch {
            val rows = mutableListOf<String>()
            var restoreRequired = false
            var confirmedBackend: String? = null
            var stoppedReason: String? = null
            EngineDiagnostics.info(
                "config_test",
                "CONFIG_SMOKE_STARTED",
                "model=" + model.name + ",bytes=" + model.sizeBytes +
                    ",profiles=" + profiles.size + ",context=" + originalConfig.contextSize +
                    ",batch=" + originalConfig.batchSize + ",threads=" + originalConfig.threads +
                    ",backend=" + originalConfig.backendPreference + ",gpuLayers=" + originalConfig.gpuLayers
            )
            try {
                for ((index, profile) in profiles.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val stop = configurationTestSafetyBlock()
                    if (stop != null) {
                        stoppedReason = stop
                        rows += "ABBRUCH vor " + profile.id + ": " + stop
                        EngineDiagnostics.info("config_test", "CONFIG_SMOKE_SAFETY_STOP", stop)
                        break
                    }

                    _configSmokeReport.value =
                        "Profil " + (index + 1) + "/" + profiles.size + ": " + profile.label + " – Modell wird neu geladen…"
                    restoreRequired = true
                    val loadStart = System.nanoTime()
                    val loaded = engine.loadModel(source, profile.config).getOrElse { throw it }
                    val loadMs = (System.nanoTime() - loadStart) / 1_000_000L
                    val backend = backendFamily(loaded.activeBackend)
                    if (backend == "UNKNOWN") throw IllegalStateException("Aktives Backend nicht eindeutig: " + loaded.activeBackend)
                    if (confirmedBackend == null) confirmedBackend = backend
                    else if (confirmedBackend != backend) {
                        throw IllegalStateException("Backendwechsel von " + confirmedBackend + " zu " + backend + "; Vergleich abgebrochen.")
                    }
                    if (loaded.sizeBytes != model.sizeBytes ||
                        (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() && model.sha256 != loaded.sha256)
                    ) throw IllegalStateException("Nach Neuladen wurde nicht dieselbe Modelldatei bestätigt.")

                    val warmup = collectConfigurationSmokeSample(
                        InferenceConfigSmokePlan.WARMUP_TOKENS, profile.id + ":warmup")
                    delay(InferenceConfigSmokePlan.COOLDOWN_MS)
                    val measured = collectConfigurationSmokeSample(
                        InferenceConfigSmokePlan.MEASURED_TOKENS, profile.id + ":measured")
                    val cfg = profile.config
                    val report = String.format(
                        java.util.Locale.US,
                        "%s | backend=%s | load=%dms | n_threads=%d | n_threads_batch=%d | n_batch=%d | n_ubatch=%d | prompt=%d/%s ms | TTFT=%s ms | decode=%dms | output=%d | %.3f tok/s | finish=%s | warmup=%.3f tok/s",
                        profile.label, backend, loadMs, cfg.threads, cfg.batchThreads,
                        cfg.batchSize, cfg.microBatchSize, measured.promptTokens,
                        measured.promptEvaluationMs?.toString() ?: "n/a",
                        measured.firstTokenMs?.toString() ?: "n/a",
                        measured.result.durationMs, measured.result.tokensGenerated,
                        measured.result.tokensPerSecond, measured.result.finishReason.name,
                        warmup.result.tokensPerSecond
                    )
                    rows += report
                    EngineDiagnostics.info(
                        "config_test",
                        "CONFIG_SMOKE_RESULT",
                        "profile=" + profile.id + ",backend=" + backend + ",loadMs=" + loadMs +
                            ",nThreads=" + cfg.threads + ",nThreadsBatch=" + cfg.batchThreads +
                            ",nBatch=" + cfg.batchSize + ",nUbatch=" + cfg.microBatchSize +
                            ",promptTokens=" + measured.promptTokens +
                            ",promptElapsedMs=" + (measured.promptEvaluationMs ?: -1L) +
                            ",ttftMs=" + (measured.firstTokenMs ?: -1L) +
                            ",decodeMs=" + measured.result.durationMs +
                            ",outputTokens=" + measured.result.tokensGenerated +
                            ",tokensPerSecond=" + measured.result.tokensPerSecond +
                            ",finishReason=" + measured.result.finishReason.name +
                            ",warmupTokensPerSecond=" + warmup.result.tokensPerSecond
                    )
                    if (measured.result.tokensGenerated < 4) {
                        throw IllegalStateException("Zu wenige Mess-Tokens im Profil " + profile.id)
                    }
                    if (index < profiles.lastIndex) delay(InferenceConfigSmokePlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                stoppedReason = "Vom Nutzer abgebrochen."
                rows += "ABGEBROCHEN: Der laufende Test wurde beendet."
                EngineDiagnostics.info("config_test", "CONFIG_SMOKE_CANCELLED")
            } catch (error: Throwable) {
                stoppedReason = error.message ?: error.javaClass.simpleName
                rows += "FEHLER: " + stoppedReason
                EngineDiagnostics.error("config_test", "CONFIG_SMOKE_FAILED", error)
            } finally {
                if (restoreRequired) {
                    _configSmokeReport.value = "Stelle die ursprüngliche Modellkonfiguration wieder her…"
                    withContext(NonCancellable) {
                        runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                            .onSuccess { restored ->
                                EngineDiagnostics.info(
                                    "config_test",
                                    "CONFIG_SMOKE_RESTORED",
                                    "backendPreference=" + originalConfig.backendPreference +
                                        ",activeBackend=" + restored.activeBackend
                                )
                            }
                            .onFailure { error ->
                                rows += "KRITISCH: Restore fehlgeschlagen: " + (error.message ?: error.javaClass.simpleName)
                                EngineDiagnostics.error("config_test", "CONFIG_SMOKE_RESTORE_FAILED", error)
                            }
                    }
                }
                _configSmokeReport.value = buildString {
                    appendLine("Automatischer In-App-Konfigurationstest")
                    appendLine("Modell: " + model.name + " (" + model.sizeMb.toInt() + " MB)")
                    appendLine("Backend: " + (confirmedBackend ?: "nicht bestätigt"))
                    appendLine("Kontext: " + originalConfig.contextSize + "; GPU-Layer: " + originalConfig.gpuLayers)
                    appendLine("4 Warmup-Tokens + 16 Mess-Tokens; Temperatur 0; Top-P 1.")
                    rows.forEach { appendLine(it) }
                    if (stoppedReason != null) appendLine("Hinweis: " + stoppedReason)
                    append("Restore-Status steht im Diagnoseexport.")
                }
                _isConfigSmokeTesting.value = false
                _isBenchmarking.value = false
                configurationSmokeJob = null
            }
        }
    }

    fun cancelConfigurationSmokeTest() {
        if (!_isConfigSmokeTesting.value) return
        EngineDiagnostics.info("config_test", "CONFIG_SMOKE_CANCEL_REQUESTED")
        engine.cancelInference()
        configurationSmokeJob?.cancel(CancellationException("Configuration smoke test cancelled"))
        _configSmokeReport.value = "Abbruch angefordert – sichere Wiederherstellung wird ausgeführt…"
    }

    private data class ConfigurationSmokeSample(
        val result: com.mydocanizer.llm.engine.InferenceResult,
        val firstTokenMs: Long?,
        val promptEvaluationMs: Long?,
        val promptTokens: Int
    )

    private suspend fun collectConfigurationSmokeSample(
        maxTokens: Int,
        diagnosticLabel: String
    ): ConfigurationSmokeSample {
        val started = System.nanoTime()
        var firstTokenMs: Long? = null
        var promptElapsedMs: Long? = null
        var promptTokens = 0
        var completed: com.mydocanizer.llm.engine.InferenceResult? = null
        val streamedOutput = StringBuilder()
        var cancelledPartialText: String? = null
        var failedPartialText: String? = null
        val sequence = (1..160).joinToString(", ") { (it * 2).toString() }
        val prompt = "Analysiere diese Zahlenfolge und gib die nächsten fünf geraden Zahlen aus. " +
            "Antworte ausschließlich im Format ZAHL, ZAHL, ZAHL, ZAHL, ZAHL. Zahlenfolge: " + sequence

        try {
            engine.streamChat(
                InferenceRequest(
                    messages = listOf(ChatMessage.user(prompt)),
                    maxPredictTokens = maxTokens,
                    temperature = 0f,
                    topP = 1f
                )
            ).collect { event ->
            currentCoroutineContext().ensureActive()
            when (event) {
                is InferenceEvent.Progress -> if (event.phase == InferenceEvent.ProgressPhase.PROMPT_EVALUATION) {
                    promptElapsedMs = event.elapsedMs
                    promptTokens = event.promptTokensTotal
                }
                is InferenceEvent.Token -> {
                    streamedOutput.append(event.token)
                    if (firstTokenMs == null) {
                        firstTokenMs = (System.nanoTime() - started) / 1_000_000L
                    }
                }
                is InferenceEvent.Completed -> completed = event.result
                is InferenceEvent.Error -> {
                    failedPartialText = event.partialText
                    throw event.throwable
                }
                is InferenceEvent.Cancelled -> {
                    cancelledPartialText = event.partialText.ifBlank { streamedOutput.toString() }
                    EngineDiagnostics.info(
                        "config_test",
                        "CONFIG_SMOKE_INFERENCE_CANCELLED",
                        "stage=" + diagnosticLabel + ",tokens=" + event.tokensGenerated +
                            ",partialChars=" + cancelledPartialText!!.length
                    )
                    throw IllegalStateException(
                        "Die Testinferenz wurde abgebrochen: " + diagnosticLabel
                    )
                }
                is InferenceEvent.Started -> Unit
            }
            }
        } catch (cancelled: CancellationException) {
            EngineDiagnostics.testText(
                "config_test",
                "CONFIG_SMOKE_TEST_OUTPUT",
                "stage=" + diagnosticLabel + ",finishReason=CANCELLED,tokens=" +
                    streamedOutput.length + ",text=" +
                    (cancelledPartialText ?: streamedOutput.toString())
            )
            throw cancelled
        } catch (error: Throwable) {
            val finishReason = if (cancelledPartialText != null) "CANCELLED" else "ERROR"
            EngineDiagnostics.testText(
                "config_test",
                "CONFIG_SMOKE_TEST_OUTPUT",
                "stage=" + diagnosticLabel + ",finishReason=" + finishReason +
                    ",error=" + (error.message ?: error.javaClass.simpleName) + ",text=" +
                    (cancelledPartialText ?: failedPartialText ?: streamedOutput.toString())
            )
            throw error
        }
        val result = completed ?: throw IllegalStateException("Keine abgeschlossene Testinferenz erhalten.")
        if (result.text.isBlank()) throw IllegalStateException("Leere Testantwort.")
        EngineDiagnostics.testText(
            "config_test",
            "CONFIG_SMOKE_TEST_OUTPUT",
            "stage=" + diagnosticLabel + ",finishReason=" + result.finishReason.name +
                ",tokens=" + result.tokensGenerated + ",expectedContains322=" +
                result.text.contains("322") + ",text=" + result.text
        )
        if (!result.text.contains("322")) {
            EngineDiagnostics.info("config_test", "CONFIG_SMOKE_OUTPUT_CHECK_FAILED", "expectedFirstValue=322")
        }
        return ConfigurationSmokeSample(result, firstTokenMs, promptElapsedMs, promptTokens)
    }
}
