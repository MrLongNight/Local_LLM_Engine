package com.example.ui.diagnostics

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
) : BenchmarkDiagnosticsViewModel(application, engine) {

    private val _isConfigSmokeTesting = MutableStateFlow(false)
    val isConfigSmokeTesting: StateFlow<Boolean> = _isConfigSmokeTesting.asStateFlow()

    private val _configSmokeReport = MutableStateFlow<String?>(null)
    val configSmokeReport: StateFlow<String?> = _configSmokeReport.asStateFlow()

    private var configurationSmokeJob: Job? = null

    /**
     * Short, fixed CPU-reference/Vulkan-candidate comparison using the same model
     * source, prompt and shared effective settings. Backend-specific GPU offload
     * is the only intentional configuration difference. The original configuration
     * is restored in finally; bounded test input/output text is Debug-diagnostic only.
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
        if (profiles.isEmpty()) {
            _errorMessage.value = "Kein Vulkan-Diagnoseprofil verfügbar."
            return
        }

        _isBenchmarking.value = true
        _isConfigSmokeTesting.value = true
        _configSmokeReport.value = "Konfigurationstest wird vorbereitet…"
        _errorMessage.value = null

        configurationSmokeJob = viewModelScope.launch {
            val rows = mutableListOf<String>()
            val profileSamples = mutableMapOf<String, ConfigurationSmokeSample>()
            var restoreRequired = false
            val confirmedBackends = mutableListOf<String>()
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
                    val loadResult = engine.loadModel(source, profile.config)
                    val loadMs = (System.nanoTime() - loadStart) / 1_000_000L
                    if (loadResult.isFailure) {
                        val loadError = loadResult.exceptionOrNull() ?: IllegalStateException("Unbekannter Modellladefehler")
                        if (profile.expectedBackend == "VULKAN" && isVulkanBackendUnavailable(loadError)) {
                            val reason = "VULKAN_DEVICE_UNAVAILABLE_OR_INITIALIZATION_FAILED"
                            rows += "Vulkan nicht verfügbar (nicht als Pass gewertet): " +
                                (loadError.message ?: loadError.javaClass.simpleName)
                            stoppedReason = "Vulkan-Backend nicht verfügbar; CPU-Referenz bleibt separat dokumentiert."
                            EngineDiagnostics.info(
                                "config_test",
                                "CONFIG_SMOKE_RESULT",
                                "profile=" + profile.id + ",requestedBackend=VULKAN,backend=UNAVAILABLE," +
                                    "status=UNAVAILABLE,reason=" + reason + ",loadMs=" + loadMs
                            )
                            break
                        }
                        throw loadError
                    }
                    val loaded = loadResult.getOrThrow()
                    val backend = backendFamily(loaded.activeBackend)
                    if (backend == "UNKNOWN") throw IllegalStateException("Aktives Backend nicht eindeutig: " + loaded.activeBackend)
                    if (backend != profile.expectedBackend) {
                        throw IllegalStateException(
                            "Angefordert wurde " + profile.expectedBackend +
                                ", tatsächlich aktiv ist " + loaded.activeBackend
                        )
                    }
                    confirmedBackends += profile.id + "=" + backend
                    if (loaded.sizeBytes != model.sizeBytes ||
                        (!model.sha256.isNullOrBlank() && model.sha256 != loaded.sha256)
                    ) throw IllegalStateException("Nach Neuladen wurde nicht dieselbe Modelldatei bestätigt (Größe/SHA-256).")
                    val shaStatus = if (!model.sha256.isNullOrBlank() && model.sha256 == loaded.sha256) {
                        "VERIFIED"
                    } else {
                        "UNAVAILABLE"
                    }

                    val sample = collectConfigurationSmokeSample(
                        InferenceConfigSmokePlan.MEASURED_TOKENS,
                        profile.id + ":measured"
                    )
                    val cfg = profile.config
                    val normalizedOutput = sample.result.text.filterNot { it.isWhitespace() }
                    val expectedOutput = "322,324,326,328,330"
                    val outputMatches = normalizedOutput == expectedOutput
                    val completedNormally = sample.result.finishReason == com.mydocanizer.llm.engine.FinishReason.STOP
                    val q4kFallbackValidation = if (profile.expectedBackend == "VULKAN") {
                        Q4kPipelineFallbackPolicy.validateNativeEvidence(
                            EngineDiagnostics.vulkanQ4kDecodePathLines(
                                getApplication<Application>(),
                                sample.result.nativeRunId
                            )
                        )
                    } else {
                        Q4kPipelineFallbackPolicy.Validation(
                            Q4kPipelineFallbackPolicy.Status.NOT_APPLICABLE,
                            "CPU_REFERENCE"
                        )
                    }
                    val outputGatePassed = outputMatches && completedNormally &&
                        sample.result.tokensGenerated >= 4 &&
                        q4kFallbackValidation.status != Q4kPipelineFallbackPolicy.Status.INVALID
                    profileSamples[profile.id] = sample.copy(outputGatePassed = outputGatePassed)
                    val report = String.format(
                        java.util.Locale.US,
                        "%s | requested=%s | backend=%s | load=%dms | n_threads=%d | n_threads_batch=%d | n_batch=%d | n_ubatch=%d | prompt=%d/%s ms | TTFT=%s ms | decode=%dms | output=%d | %.3f tok/s | finish=%s | exactOutput=%s | gate=%s | graphSplits=%d | run=%s",
                        profile.label, profile.expectedBackend, backend, loadMs, cfg.threads, cfg.effectiveBatchThreads,
                        cfg.batchSize, InferenceConfigSmokePlan.resolvedUbatchForTest(cfg, loaded.activeBackend), sample.promptTokens,
                        sample.promptEvaluationMs?.toString() ?: "n/a",
                        sample.firstTokenMs?.toString() ?: "n/a",
                        sample.result.durationMs, sample.result.tokensGenerated,
                        sample.result.tokensPerSecond, sample.result.finishReason.name,
                        outputMatches, if (outputGatePassed) "PASS" else "FAIL", sample.result.graphSplits,
                        sample.result.diagnosticRunId.ifBlank { "n/a" }
                    ) + " | Q4K-Fallback=" + q4kFallbackValidation.status.name +
                        " (" + q4kFallbackValidation.reason + ")"
                    rows += report
                    EngineDiagnostics.info(
                        "config_test",
                        "CONFIG_SMOKE_RESULT",
                        "profile=" + profile.id + ",requestedBackend=" + profile.expectedBackend +
                            ",backend=" + backend + ",status=" +
                            (if (outputGatePassed) "PASS" else "FAIL") +
                            ",modelBytes=" + loaded.sizeBytes + ",modelSha256=" + (loaded.sha256 ?: "unavailable") +
                            ",modelShaStatus=" + shaStatus + ",gpuLayersRequested=" + cfg.gpuLayers +
                            ",loadMs=" + loadMs + ",nThreads=" + cfg.threads +
                            ",nThreadsBatch=" + cfg.effectiveBatchThreads +
                            ",nBatch=" + cfg.batchSize +
                            ",nUbatch=" + InferenceConfigSmokePlan.resolvedUbatchForTest(cfg, loaded.activeBackend) +
                            ",diagnosticRunId=" + sample.result.diagnosticRunId +
                            ",nativeRunId=" + sample.result.nativeRunId +
                            ",graphSplits=" + sample.result.graphSplits +
                            ",promptTokens=" + sample.promptTokens +
                            ",promptElapsedMs=" + (sample.promptEvaluationMs ?: -1L) +
                            ",ttftMs=" + (sample.firstTokenMs ?: -1L) +
                            ",decodeMs=" + sample.result.durationMs +
                            ",outputTokens=" + sample.result.tokensGenerated +
                            ",tokensPerSecond=" + sample.result.tokensPerSecond +
                            ",finishReason=" + sample.result.finishReason.name +
                            ",exactOutput=" + outputMatches +
                            ",normalStop=" + completedNormally +
                            ",outputGate=" + (if (outputGatePassed) "PASS" else "FAIL") +
                            ",q4kFallbackStatus=" + q4kFallbackValidation.status.name +
                            ",q4kFallbackReason=" + q4kFallbackValidation.reason
                    )
                    if (!outputGatePassed) {
                        val rejection = "profile=" + profile.id +
                            ",outputTokens=" + sample.result.tokensGenerated +
                            ",exactOutput=" + outputMatches +
                            ",finishReason=" + sample.result.finishReason.name
                        rows += "Korrektheits-Gate fehlgeschlagen: " + rejection
                        EngineDiagnostics.info("config_test", "CONFIG_SMOKE_OUTPUT_REJECTED", rejection)
                    }
                    if (index < profiles.lastIndex) delay(InferenceConfigSmokePlan.COOLDOWN_MS)
                }
                val cpuSample = profileSamples["cpu-reference"]
                val vulkanSample = profileSamples["vulkan-candidate"]
                val gate = when {
                    cpuSample == null || vulkanSample == null ->
                        EngineDiagnostics.BackendCorrectnessResult("UNAVAILABLE", "CPU_OR_VULKAN_RUN_MISSING")
                    !cpuSample.outputGatePassed || !vulkanSample.outputGatePassed ->
                        EngineDiagnostics.BackendCorrectnessResult("FAIL", "EXPECTED_OUTPUT_OR_STOP_GATE_FAILED")
                    else -> EngineDiagnostics.evaluateCpuVulkanCorrectnessGate(
                        getApplication<Application>(),
                        cpuSample.result.nativeRunId,
                        vulkanSample.result.nativeRunId
                    )
                }
                val gateDetails = "profile=CPU_VULKAN_SMOKE,backend=Vulkan," +
                    "gateRunId=" + (vulkanSample?.result?.nativeRunId ?: "unavailable") +
                    ",cpuReferenceRunId=" + (cpuSample?.result?.nativeRunId ?: "unavailable") +
                    ",status=" + gate.status +
                    ",reason=" + gate.reason +
                    ",comparedSteps=" + gate.comparedSteps +
                    ",minimumTop5Overlap=" + gate.minimumTop5Overlap + "/5" +
                    ",firstDivergenceStep=" + (gate.firstDivergenceStep ?: "n/a")
                EngineDiagnostics.info("benchmark", "CORRECTNESS_GATE_RESULT", gateDetails)
                rows += "CPU/Vulkan-Korrektheits-Gate: " + gate.status +
                    " (" + gate.reason + ", Schritte=" + gate.comparedSteps +
                    ", min. Top-5-Überlappung=" + gate.minimumTop5Overlap + "/5)"
                if (!gate.passed) {
                    rows += "Keine Performance-/Kernel-Konfiguration durch diesen Lauf freigegeben."
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
                    appendLine("Backend-Ergebnisse: " + confirmedBackends.joinToString(", ").ifBlank { "keines bestätigt" })
                    appendLine("Kontext: " + originalConfig.contextSize + "; Batch: " + originalConfig.batchSize +
                        "; Threads: " + originalConfig.threads)
                    appendLine("CPU-Referenz und Vulkan-Kandidat mit identischer Testeingabe und deterministischem Sampling; " +
                        InferenceConfigSmokePlan.MEASURED_TOKENS + " Token-Limit pro Lauf. GPU-Layer: CPU=0, Vulkan=99 angefordert.")
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

    private fun isVulkanBackendUnavailable(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            val message = current?.message
            if (message?.contains("Vulkan backend was explicitly requested", ignoreCase = true) == true) {
                return true
            }
            current = current?.cause
        }
        return false
    }

    private data class ConfigurationSmokeSample(
        val result: com.mydocanizer.llm.engine.InferenceResult,
        val firstTokenMs: Long?,
        val promptEvaluationMs: Long?,
        val promptTokens: Int,
        val outputGatePassed: Boolean = false
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
        val prompt = "Copy exactly this text and output nothing else: 322, 324, 326, 328, 330"
        val expectedOutput = "322,324,326,328,330"
        EngineDiagnostics.testText(
            "config_test",
            "CONFIG_SMOKE_TEST_INPUT",
            "stage=$diagnosticLabel,prompt=$prompt,expectedOutput=$expectedOutput"
        )

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
                ",tokens=" + result.tokensGenerated + ",expectedOutput=" + expectedOutput +
                ",exactOutput=" + (result.text.filterNot { it.isWhitespace() } == expectedOutput) +
                ",text=" + result.text
        )
        if (!result.text.contains("322")) {
            EngineDiagnostics.info("config_test", "CONFIG_SMOKE_OUTPUT_CHECK_FAILED", "expectedFirstValue=322")
        }
        val outputGatePassed = result.finishReason == com.mydocanizer.llm.engine.FinishReason.STOP &&
            result.text.filterNot { it.isWhitespace() } == expectedOutput &&
            result.tokensGenerated >= 4
        return ConfigurationSmokeSample(
            result, firstTokenMs, promptElapsedMs, promptTokens, outputGatePassed
        )
    }
}
