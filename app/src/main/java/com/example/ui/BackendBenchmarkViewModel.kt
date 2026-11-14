package com.example.ui

import com.example.domain.BackendBenchmarkPlan
import com.example.domain.CorrectnessGatePlan

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
 * Backend acceptance and CPU/Vulkan benchmark flows. The sample collector is
 * protected because Vulkan diagnostics reuse the same inference measurement.
 */
abstract class BackendBenchmarkViewModel(
    application: Application,
    engine: LocalLlmEngine
) : BenchmarkDiagnosticsViewModel(application, engine) {

    /**
     * Full G1 matrix: backend × decode path × n_ubatch × Flash Attention.
     * Each profile gets a fresh model load and deterministic inference; failures are
     * reported without silently falling back to a different backend.
     */
    fun runBackendAcceptanceTest() {
        if (!com.example.BuildConfig.DEBUG ||
            !com.mydocanizer.llm.engine.BuildConfig.TEXT_DIAGNOSTICS_ENABLED
        ) {
            _errorMessage.value = "Das Korrektheits-Gate benötigt einen Debug-Build mit TEXT_DIAGNOSTICS."
            return
        }
        if (_isBenchmarking.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Den Abnahmetest erst starten, wenn keine Inferenz oder Modell-Operation läuft."
            return
        }
        val model = currentModel.value ?: run {
            _errorMessage.value = "Bitte zuerst ein GGUF-Modell laden."
            return
        }
        val originalConfig = _engineConfig.value
        val systemPromptSnapshot = _systemPrompt.value
        val source = if (model.path.startsWith("content://", ignoreCase = true)) {
            ModelSource.ContentUri(model.path, model.name)
        } else ModelSource.FilePath(model.path)
        val profiles = BackendBenchmarkPlan.backendAcceptanceProfiles(originalConfig)
        val benchmarkPrompt =
            "Gib die Zahlen 1 bis 48 fortlaufend aus, ausschließlich durch Leerzeichen getrennt. Keine Erklärung."

        _isBenchmarking.value = true
        _benchmarkReport.value = "Kompakter CPU/Vulkan-Abnahmetest wird vorbereitet (${profiles.size} Profile)…"
        _errorMessage.value = null

        viewModelScope.launch {
            val rows = mutableListOf<String>()
            var restoreRequired = false
            var stoppedReason: String? = null
            var successfulRuns = 0
            val cpuGateReferences = mutableMapOf<Triple<Int, com.mydocanizer.llm.engine.FlashAttentionMode, GdnPolicy>, CorrectnessGatePlan.GateRun>()
            EngineDiagnostics.info(
                "benchmark",
                "BACKEND_ACCEPTANCE_STARTED",
                "model=${model.name},modelBytes=${model.sizeBytes},profiles=${profiles.size}," +
                    "backends=VULKAN,CPU,kernelProfiles=AUTO,SUBGROUP,LARGE,QUANTIZATION_MATCHED_AB_PATHS,nUbatch=32,64,128," +
                    "flashAttention=DISABLED,ENABLED,AUTO_UB64,gdnPolicyMatrix=AR_CH_FUSION_COMBINATIONS,warmupTokens=${BackendBenchmarkPlan.WARMUP_TOKENS}," +
                    "measuredTokens=${BackendBenchmarkPlan.MEASURED_TOKENS},context=${originalConfig.contextSize}," +
                    "batch=${originalConfig.batchSize},prompt=fixed_counting_sequence"
            )
            try {
                for ((index, profile) in profiles.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val safetyStop = configurationTestSafetyBlock()
                    if (safetyStop != null) {
                        stoppedReason = safetyStop
                        rows += "ABBRUCH vor ${profile.id}: ${safetyStop}"
                        EngineDiagnostics.info("benchmark", "BACKEND_ACCEPTANCE_SAFETY_STOP", safetyStop)
                        break
                    }
                    _benchmarkReport.value =
                        "Abnahmetest ${index + 1}/${profiles.size}: ${profile.id} – Modell wird geladen…"
                    EngineDiagnostics.info(
                        "benchmark",
                        "BACKEND_ACCEPTANCE_PROFILE_STARTED",
                        "index=${index + 1},total=${profiles.size},profile=${profile.id}," +
                            "backend=${profile.backend},nUbatch=${profile.microBatchSize}," +
                            "flashAttention=${profile.flashAttention},gdnPolicy=${profile.config.gdnPolicy},model=${model.name}"
                    )
                    restoreRequired = true
                    var profileGateStatus: CorrectnessGatePlan.Status? = null
                    var profileGateRunId = ""
                    try {
                        val loadStarted = System.nanoTime()
                        val loaded = engine.loadModel(source, profile.config).getOrElse { throw it }
                        val modelLoadMs = (System.nanoTime() - loadStarted) / 1_000_000L
                        val actualBackend = backendFamily(loaded.activeBackend)
                        if (actualBackend != profile.backend.name) {
                            throw IllegalStateException(
                                "Angefordert wurde ${profile.backend.name}, geladen wurde aber ${loaded.activeBackend}"
                            )
                        }
                        if (loaded.sizeBytes != model.sizeBytes ||
                            (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() &&
                                model.sha256 != loaded.sha256)
                        ) {
                            throw IllegalStateException("Nach dem Neuladen wurde nicht dieselbe GGUF-Datei bestätigt.")
                        }
                        val gateSample = collectBackendBenchmarkSample(
                            backend = profile.backend,
                            maxTokens = BackendBenchmarkPlan.CORRECTNESS_GATE_STEPS,
                            warmupTokens = 0,
                            systemPromptSnapshot = "",
                            benchmarkPrompt = BackendBenchmarkPlan.correctnessGatePrompt()
                        )
                        val gateNativeRunId = gateSample.result.nativeRunId
                            .ifBlank { gateSample.result.diagnosticRunId }
                        val gateTraceLines = EngineDiagnostics.tokenSelectionTraceLines(
                            getApplication(), gateNativeRunId
                        )
                        val gateRun = CorrectnessGatePlan.parse(gateSample.result.text, gateTraceLines)
                        val gateKey = Triple(profile.microBatchSize, profile.flashAttention, profile.config.gdnPolicy)
                        val cpuReference = cpuGateReferences[gateKey]
                        val gateEvaluation = CorrectnessGatePlan.evaluate(
                            candidate = gateRun,
                            cpuReference = if (profile.backend == BackendPreference.CPU) null else cpuReference,
                            expectedBackend = actualBackend
                        )
                        profileGateStatus = gateEvaluation.status
                        profileGateRunId = gateNativeRunId
                        if (profile.backend == BackendPreference.CPU) {
                            cpuGateReferences[gateKey] = gateRun
                        }
                        EngineDiagnostics.info(
                            "benchmark",
                            "CORRECTNESS_GATE_RESULT",
                            "model=${model.name},profile=${profile.id},backend=$actualBackend," +
                                "gateRunId=$gateNativeRunId,nativeRunId=$gateNativeRunId," +
                                "resourceRunId=${gateSample.result.diagnosticRunId}," +
                                "cpuReferenceRunId=${cpuReference?.runId.orEmpty()},status=${gateEvaluation.status}," +
                                "reason=${gateEvaluation.reason},steps=${gateEvaluation.comparedSteps}," +
                                "minimumTop5Overlap=${gateEvaluation.minimumTop5Overlap}/5," +
                                "maxConsecutiveRepeat=${gateEvaluation.maxConsecutiveRepeat}"
                        )
                        if (gateEvaluation.passed) {
                        val measured = collectBackendBenchmarkSample(
                            backend = profile.backend,
                            maxTokens = BackendBenchmarkPlan.TOTAL_TOKENS,
                            warmupTokens = BackendBenchmarkPlan.WARMUP_TOKENS,
                            systemPromptSnapshot = systemPromptSnapshot,
                            benchmarkPrompt = benchmarkPrompt
                        )
                        if (measured.measuredTokens < 8) {
                            throw IllegalStateException(
                                "Zu wenige Mess-Tokens im Profil ${profile.id}: ${measured.measuredTokens}"
                            )
                        }
                        if (measured.result.graphSplits < 0) {
                            throw IllegalStateException(
                                "Graph-Split-Metrik fehlt im Profil ${profile.id}; diese Messung ist nicht vollständig."
                            )
                        }
                        if (measured.result.diagnosticRunId.isBlank()) {
                            throw IllegalStateException(
                                "IPC-Diagnose-Run-ID fehlt im Profil ${profile.id}; RAM-Messwerte können nicht zugeordnet werden."
                            )
                        }
                        val reportLine = String.format(
                            java.util.Locale.US,
                            "%s | gate=PASS | load=%dms | prompt=%d tokens/%s ms | TTFT=%s ms | graphSplits=%d | runId=%s | " +
                                "warmup=%d | measured=%d in %s ms | decode=%.3f tok/s | native=%.3f tok/s | " +
                                "n_batch=%d n_ubatch=%d threads=%d | %s",
                            profile.id,
                            modelLoadMs,
                            measured.promptTokens,
                            measured.promptEvaluationMs?.toString() ?: "n/a",
                            measured.firstTokenAfterPrefillMs?.toString() ?: "n/a",
                            measured.result.graphSplits,
                            measured.result.diagnosticRunId,
                            measured.warmupTokens,
                            measured.measuredTokens,
                            measured.measuredWallMs?.toString() ?: "n/a",
                            measured.measuredTokensPerSecond,
                            measured.result.tokensPerSecond,
                            profile.config.batchSize,
                            profile.config.effectiveMicroBatchSize,
                            profile.config.threads,
                            loaded.activeBackend
                        )
                        rows += reportLine
                        successfulRuns++
                        EngineDiagnostics.info(
                            "benchmark",
                            "BACKEND_ACCEPTANCE_RESULT",
                            "profile=${profile.id},model=${model.name},backend=${actualBackend},gateStatus=PASS," +
                                "decodeTokensPerSecond=${measured.measuredTokensPerSecond},nativeTokensPerSecond=${measured.result.tokensPerSecond}," +
                                "kernelProfile=${profile.kernelProfile.name},nUbatch=${profile.microBatchSize}," +
                                "flashAttention=${profile.flashAttention.name},gdnPolicy=${profile.config.gdnPolicy.name}," +
                                "graphSplits=${measured.result.graphSplits},diagnosticRunId=${measured.result.diagnosticRunId}," +
                                "gateRunId=$gateNativeRunId,gateResourceRunId=${gateSample.result.diagnosticRunId}," +
                                "nativeRunId=${measured.result.nativeRunId.ifBlank { measured.result.diagnosticRunId }}," +
                                "minimumTop5Overlap=${gateEvaluation.minimumTop5Overlap}," +
                                "activeBackend=${loaded.activeBackend},modelLoadMs=${modelLoadMs}," +
                                "promptTokens=${measured.promptTokens},prefillMs=${measured.promptEvaluationMs ?: -1L}," +
                                "firstTokenAfterPrefillMs=${measured.firstTokenAfterPrefillMs ?: -1L}," +
                                "warmupTokens=${measured.warmupTokens},measuredTokens=${measured.measuredTokens}," +
                                "measuredWallMs=${measured.measuredWallMs ?: -1L},generationMs=${measured.result.durationMs}," +
                                "finishReason=${measured.result.finishReason.name},batch=${profile.config.batchSize}," +
                                "threads=${profile.config.threads},batchThreads=${profile.config.effectiveBatchThreads}"
                        )
                        } else {
                            val reason = gateEvaluation.reason.replace(',', ';').replace('\n', ' ')
                            rows += "${profile.id} | gate=FAIL ($reason) | INVALID | tok/s=n/a"
                            EngineDiagnostics.info(
                                "benchmark",
                                "BACKEND_ACCEPTANCE_ROW_INVALID",
                                "profile=${profile.id},model=${model.name},backend=$actualBackend," +
                                    "gateStatus=FAIL,reason=$reason,tok/s=n/a,gateRunId=${gateSample.result.diagnosticRunId}"
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        val message = (error.message ?: error.javaClass.simpleName)
                            .replace(',', ';').replace('\n', ' ')
                        if (profileGateStatus == CorrectnessGatePlan.Status.PASS) {
                            rows += "${profile.id} | gate=PASS | INVALID (MESSUNG_FEHLER: $message) | tok/s=n/a"
                            EngineDiagnostics.info(
                                "benchmark",
                                "BACKEND_ACCEPTANCE_ROW_INVALID",
                                "profile=${profile.id},model=${model.name},backend=${profile.backend.name}," +
                                    "gateStatus=PASS,reason=MEASUREMENT_FAILED,gateRunId=$profileGateRunId,detail=$message"
                            )
                        } else {
                            val gateFailure = if (profileGateStatus == CorrectnessGatePlan.Status.FAIL) {
                                "GATE_FAILED"
                            } else "GATE_NOT_COMPLETED"
                            rows += "${profile.id} | gate=FAIL ($gateFailure: $message) | INVALID | tok/s=n/a"
                            EngineDiagnostics.info(
                                "benchmark",
                                "BACKEND_ACCEPTANCE_ROW_INVALID",
                                "profile=${profile.id},model=${model.name},backend=${profile.backend.name}," +
                                    "gateStatus=FAIL,reason=$gateFailure,tok/s=n/a,gateRunId=$profileGateRunId,detail=$message"
                            )
                            EngineDiagnostics.info(
                                "benchmark",
                                "CORRECTNESS_GATE_RESULT",
                                "model=${model.name},profile=${profile.id},backend=${profile.backend.name}," +
                                    "gateRunId=$profileGateRunId,status=FAIL,reason=$gateFailure,detail=$message"
                            )
                        }
                        EngineDiagnostics.error("benchmark", "BACKEND_ACCEPTANCE_PROFILE_FAILED", error)
                    }
                    if (index < profiles.lastIndex) delay(BackendBenchmarkPlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                stoppedReason = "Vom Nutzer oder durch den ViewModel-Lifecycle abgebrochen."
                rows += "ABGEBROCHEN: Vollständige Matrix wurde unterbrochen."
                EngineDiagnostics.info("benchmark", "BACKEND_ACCEPTANCE_CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                stoppedReason = error.message ?: error.javaClass.simpleName
                rows += "FEHLER: ${stoppedReason}"
                EngineDiagnostics.error("benchmark", "BACKEND_ACCEPTANCE_FAILED", error)
            } finally {
                if (restoreRequired) {
                    _benchmarkReport.value = "Stelle die ursprüngliche Modellkonfiguration wieder her…"
                    val restore = withContext(NonCancellable) {
                        runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                    }
                    restore.onSuccess { restored ->
                        EngineDiagnostics.info(
                            "benchmark",
                            "BACKEND_ACCEPTANCE_RESTORED",
                            "backendPreference=${originalConfig.backendPreference},activeBackend=${restored.activeBackend}"
                        )
                    }.onFailure { error ->
                        rows += "KRITISCH: Restore fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
                        EngineDiagnostics.error("benchmark", "BACKEND_ACCEPTANCE_RESTORE_FAILED", error)
                    }
                }
                _benchmarkReport.value = buildString {
                    appendLine("Kompakter CPU/Vulkan-Abnahmetest – ${successfulRuns}/${profiles.size} Profile mit bestandenem Gate und gültiger Messung")
                    appendLine("Modell: ${model.name} (${model.sizeMb.toInt()} MB); SHA-256: ${model.sha256 ?: "nicht verfügbar"}")
                    appendLine("Umfang: genau zwei Referenzläufe mit identischem Modell und identischen Parametern – zuerst CPU, danach Vulkan.")
                    appendLine("Vor jeder Messung: 16-Token-Greedy-Gate; CPU-Referenz zuerst, Vulkan nur bei Top-1-Identität und mindestens 4/5 Top-5-Überschneidung.")
                    appendLine("Profile ohne Gate PASS sind ungültig; für sie werden keine tok/s ausgewiesen.")
                    appendLine("Je gültigem Profil: Modell-Load, Prefill, TTFT, 4 Warm-up- und 20 Mess-Tokens, Decode tok/s.")
                    appendLine("Graph-Splits stehen direkt in jeder Profilzeile. diagnosticRunId verbindet jede Zeile mit dem exakten INFERENCE_<runId>-Peak-PSS/RAM-/Thermal-Summary.")
                    rows.forEach { appendLine(it) }
                    if (stoppedReason != null) appendLine("Hinweis: ${stoppedReason}")
                    append("Die ursprüngliche Modellkonfiguration wurde wiederhergestellt.")
                }
                _isBenchmarking.value = false
            }
        }
    }

    /**
     * Short, reproducible comparison. Each backend gets one model load and one inference
     * request. The first four generated tokens warm the execution path; the following
     * twenty are measured. This avoids repeating the expensive prompt prefill twice.
     */
    fun runDefaultBackendBenchmark() {
        if (_isBenchmarking.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Benchmark erst starten, wenn keine Inferenz oder Modell-Operation läuft."
            return
        }

        val model = currentModel.value
        if (model == null) {
            _errorMessage.value = "Bitte zuerst ein GGUF-Modell laden."
            return
        }

        val originalConfig = _engineConfig.value
        val systemPromptSnapshot = _systemPrompt.value
        val source = if (model.path.startsWith("content://", ignoreCase = true)) {
            ModelSource.ContentUri(model.path, model.name)
        } else {
            ModelSource.FilePath(model.path)
        }
        val benchmarkPrompt =
            "Gib die Zahlen 1 bis 40 fortlaufend aus, ausschließlich durch Leerzeichen getrennt. Keine Erklärung."
        val benchmarkConfig = BackendBenchmarkPlan.configuration(originalConfig)
        val backends = BackendBenchmarkPlan.BACKENDS

        _isBenchmarking.value = true
        _benchmarkReport.value = "Benchmark wird vorbereitet…"
        _errorMessage.value = null

        viewModelScope.launch {
            val lines = mutableListOf<String>()
            EngineDiagnostics.info(
                "benchmark",
                "BACKEND_BENCHMARK_STARTED",
                "model=${model.name},modelBytes=${model.sizeBytes},prompt=fixed_counting_sequence," +
                    "backendOrder=VULKAN,CPU,warmupTokens=${BackendBenchmarkPlan.WARMUP_TOKENS}," +
                    "measuredTokens=${BackendBenchmarkPlan.MEASURED_TOKENS},totalTokens=${BackendBenchmarkPlan.TOTAL_TOKENS}," +
                    "context=${benchmarkConfig.contextSize},batch=${benchmarkConfig.batchSize},threads=${benchmarkConfig.threads}," +
                    "batchThreads=${benchmarkConfig.effectiveBatchThreads},microBatchRequested=${benchmarkConfig.microBatchSize}," +
                    "gpuLayers=${benchmarkConfig.gpuLayers},temperature=0.0,topP=1.0"
            )

            try {
                for ((index, backend) in backends.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    _benchmarkReport.value = "Lade ${backend.name} für den kurzen Vergleich…"
                    try {
                        val backendConfig = benchmarkConfig.copy(backendPreference = backend)
                        val loadStarted = System.nanoTime()
                        val loaded = engine.loadModel(source, backendConfig).getOrElse { throw it }
                        val modelLoadMs = (System.nanoTime() - loadStarted) / 1_000_000L

                        val actualBackend = backendFamily(loaded.activeBackend)
                        if (actualBackend != backend.name) {
                            throw IllegalStateException(
                                "Angefordert wurde ${backend.name}, geladen wurde aber ${loaded.activeBackend}"
                            )
                        }
                        if (loaded.sizeBytes != model.sizeBytes ||
                            (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() && model.sha256 != loaded.sha256)
                        ) {
                            throw IllegalStateException("Nach dem Neuladen wurde nicht dieselbe Modelldatei bestätigt.")
                        }

                        val measured = collectBackendBenchmarkSample(
                            backend = backend,
                            maxTokens = BackendBenchmarkPlan.TOTAL_TOKENS,
                            warmupTokens = BackendBenchmarkPlan.WARMUP_TOKENS,
                            systemPromptSnapshot = systemPromptSnapshot,
                            benchmarkPrompt = benchmarkPrompt
                        )
                        if (measured.measuredTokens < 8) {
                            throw IllegalStateException(
                                "Zu wenige Mess-Tokens nach dem Warm-up: ${measured.measuredTokens}"
                            )
                        }

                        val reportLine = String.format(
                            java.util.Locale.US,
                            "%s: modelLoadMs=%d, promptTokens=%d, prefillMs=%s, firstTokenAfterPrefillMs=%s, " +
                                "warmupTokens=%d, warmupWallMs=%s, measuredTokens=%d, measuredWallMs=%s, " +
                                "measuredTokensPerSecond=%.3f, nativeGenerationMs=%d, nativeTokensPerSecond=%.3f, " +
                                "streamWallMs=%d, finishReason=%s, activeBackend=%s",
                            backend.name,
                            modelLoadMs,
                            measured.promptTokens,
                            measured.promptEvaluationMs?.toString() ?: "n/a",
                            measured.firstTokenAfterPrefillMs?.toString() ?: "n/a",
                            measured.warmupTokens,
                            measured.warmupWallMs?.toString() ?: "n/a",
                            measured.measuredTokens,
                            measured.measuredWallMs?.toString() ?: "n/a",
                            measured.measuredTokensPerSecond,
                            measured.result.durationMs,
                            measured.result.tokensPerSecond,
                            measured.wallMs,
                            measured.result.finishReason.name,
                            loaded.activeBackend
                        )
                        lines.add(reportLine)
                        EngineDiagnostics.info(
                            "benchmark",
                            "BACKEND_BENCHMARK_RESULT",
                            reportLine + ",context=${backendConfig.contextSize},batch=${backendConfig.batchSize}," +
                                "threads=${backendConfig.threads},batchThreads=${backendConfig.effectiveBatchThreads}," +
                                "microBatchRequested=${backendConfig.microBatchSize},gpuLayers=${backendConfig.gpuLayers}," +
                                "temperature=0.0,topP=1.0,maxPredictTokens=${BackendBenchmarkPlan.TOTAL_TOKENS}"
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        val failure = "${backend.name}: FEHLER - ${error.message ?: error.javaClass.simpleName}"
                        lines.add(failure)
                        EngineDiagnostics.error("benchmark", "BACKEND_BENCHMARK_FAILED", error)
                    }

                    if (index < backends.lastIndex) delay(BackendBenchmarkPlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                lines.add("Benchmark wurde abgebrochen.")
                throw cancelled
            } finally {
                _benchmarkReport.value = "Stelle ursprüngliche Modellkonfiguration wieder her…"
                val restore = withContext(NonCancellable) {
                    runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                }
                restore.onSuccess { restored ->
                    EngineDiagnostics.info(
                        "benchmark",
                        "BACKEND_BENCHMARK_RESTORED",
                        "backendPreference=${originalConfig.backendPreference},activeBackend=${restored.activeBackend}"
                    )
                }.onFailure { error ->
                    lines.add("Wiederherstellung der ursprünglichen Konfiguration fehlgeschlagen: ${error.message}")
                    EngineDiagnostics.error("benchmark", "BACKEND_BENCHMARK_RESTORE_FAILED", error)
                }

                _benchmarkReport.value = buildString {
                    appendLine("CPU- / Vulkan-Kurzbenchmark")
                    appendLine("Test: Zahlenfolge 1–40")
                    appendLine("Modell: ${model.name} (${model.sizeMb.toInt()} MB)")
                    appendLine(
                        "Pro Backend: eine Inferenz, 4 Warm-up-Tokens + 20 Mess-Tokens; Temperatur 0; Top-P 1; " +
                            "Kontext ${benchmarkConfig.contextSize}; Batch ${benchmarkConfig.batchSize}; " +
                            "${benchmarkConfig.threads} Threads; GPU-Layer ${benchmarkConfig.gpuLayers}."
                    )
                    lines.forEach { appendLine(it) }
                    append("Fortschritt und native Zeitmesswerte stehen zusätzlich im Diagnoseexport.")
                }
                _isBenchmarking.value = false
            }
        }
    }

    protected data class BackendBenchmarkSample(
        val result: InferenceResult,
        val firstTokenAfterPrefillMs: Long?,
        val promptEvaluationMs: Long?,
        val promptTokens: Int,
        val warmupTokens: Int,
        val warmupWallMs: Long?,
        val measuredTokens: Int,
        val measuredWallMs: Long?,
        val measuredTokensPerSecond: Double,
        val wallMs: Long
    )

    protected suspend fun collectBackendBenchmarkSample(
        backend: BackendPreference,
        maxTokens: Int,
        warmupTokens: Int,
        systemPromptSnapshot: String,
        benchmarkPrompt: String
    ): BackendBenchmarkSample {
        val messages = buildList {
            if (systemPromptSnapshot.isNotBlank()) add(ChatMessage.system(systemPromptSnapshot))
            add(ChatMessage.user(benchmarkPrompt))
        }
        val request = InferenceRequest(
            messages = messages,
            maxPredictTokens = maxTokens,
            temperature = 0f,
            topP = 1f
        )

        var firstTokenAfterPrefillMs: Long? = null
        var promptEvaluationMs: Long? = null
        var promptTokens = 0
        var visibleTokenCount = 0
        var warmupWallMs: Long? = null
        var completed: InferenceResult? = null
        val streamedOutput = StringBuilder()
        var cancelledPartialText: String? = null
        var failedPartialText: String? = null
        val started = System.nanoTime()

        try {
            engine.streamChat(request).collect { event ->
            when (event) {
                is InferenceEvent.Progress -> {
                    if (event.phase == InferenceEvent.ProgressPhase.PROMPT_EVALUATION) {
                        promptTokens = event.promptTokensTotal
                        _benchmarkReport.value =
                            "${backend.name}: Prompt ${event.promptTokensProcessed}/${event.promptTokensTotal} Tokens…"
                    } else if (event.phase == InferenceEvent.ProgressPhase.GENERATING) {
                        _benchmarkReport.value =
                            "${backend.name}: ${event.tokensGenerated}/$maxTokens Tokens werden erzeugt…"
                    }
                }
                is InferenceEvent.Started -> {
                    promptEvaluationMs = (System.nanoTime() - started) / 1_000_000L
                    _benchmarkReport.value = "${backend.name}: Prompt abgeschlossen – Generierung läuft…"
                }
                is InferenceEvent.Token -> {
                    visibleTokenCount++
                    streamedOutput.append(event.token)
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    if (firstTokenAfterPrefillMs == null) {
                        firstTokenAfterPrefillMs =
                            (elapsedMs - (promptEvaluationMs ?: elapsedMs)).coerceAtLeast(0L)
                    }
                    if (visibleTokenCount == warmupTokens) {
                        warmupWallMs = elapsedMs
                    }
                    val phase = if (visibleTokenCount <= warmupTokens) "Warm-up" else "Messung"
                    val measuredSoFar = (visibleTokenCount - warmupTokens).coerceAtLeast(0)
                    _benchmarkReport.value =
                        "${backend.name}: $phase – ${visibleTokenCount}/$maxTokens Tokens " +
                            "(${measuredSoFar}/${BackendBenchmarkPlan.MEASURED_TOKENS} gemessen)…"
                }
                is InferenceEvent.Completed -> completed = event.result
                is InferenceEvent.Error -> {
                    failedPartialText = event.partialText
                    throw event.throwable
                }
                is InferenceEvent.Cancelled -> {
                    cancelledPartialText = event.partialText.ifBlank { streamedOutput.toString() }
                    EngineDiagnostics.info(
                        "benchmark",
                        "BACKEND_BENCHMARK_INFERENCE_CANCELLED",
                        "backend=" + backend.name + ",tokens=" + event.tokensGenerated +
                            ",partialChars=" + cancelledPartialText!!.length
                    )
                    // A native inference cancellation is a failed sample, not cancellation
                    // of the parent benchmark coroutine. Only a real Job cancellation
                    // should stop the complete matrix/sweep.
                    throw IllegalStateException(
                        "Die Inferenz für " + backend.name + " wurde vor Abschluss abgebrochen."
                    )
                }
            }
            }
        } catch (cancelled: CancellationException) {
            EngineDiagnostics.testText(
                "benchmark",
                "BACKEND_BENCHMARK_TEST_OUTPUT",
                "backend=" + backend.name + ",finishReason=CANCELLED,tokens=" +
                    visibleTokenCount + ",text=" +
                    (cancelledPartialText ?: streamedOutput.toString())
            )
            throw cancelled
        } catch (error: Throwable) {
            val finishReason = if (cancelledPartialText != null) "CANCELLED" else "ERROR"
            EngineDiagnostics.testText(
                "benchmark",
                "BACKEND_BENCHMARK_TEST_OUTPUT",
                "backend=" + backend.name + ",finishReason=" + finishReason +
                    ",tokens=" + visibleTokenCount + ",error=" +
                    (error.message ?: error.javaClass.simpleName) +
                    ",text=" + (cancelledPartialText ?: failedPartialText ?: streamedOutput.toString())
            )
            throw error
        }

        val result = completed ?: throw IllegalStateException("Keine abgeschlossene Antwort für " + backend.name + " erhalten.")
        EngineDiagnostics.testText(
            "benchmark",
            "BACKEND_BENCHMARK_TEST_OUTPUT",
            "backend=" + backend.name + ",finishReason=" + result.finishReason.name +
                ",tokens=" + result.tokensGenerated + ",text=" + result.text
        )
        val visibleCount = visibleTokenCount.coerceAtMost(result.tokensGenerated)
        val measuredTokens = (result.tokensGenerated - warmupTokens).coerceAtLeast(0)
        val wallMs = (System.nanoTime() - started) / 1_000_000L
        val measuredWallMs = if (measuredTokens > 0 && warmupWallMs != null) {
            (wallMs - warmupWallMs!!).coerceAtLeast(1L)
        } else null
        val measuredSpeed = if (measuredWallMs != null) {
            measuredTokens * 1000.0 / measuredWallMs
        } else 0.0

        return BackendBenchmarkSample(
            result = result,
            firstTokenAfterPrefillMs = firstTokenAfterPrefillMs,
            promptEvaluationMs = promptEvaluationMs,
            promptTokens = promptTokens,
            warmupTokens = minOf(result.tokensGenerated, warmupTokens),
            warmupWallMs = warmupWallMs,
            measuredTokens = measuredTokens,
            measuredWallMs = measuredWallMs,
            measuredTokensPerSecond = measuredSpeed,
            wallMs = wallMs
        )
    }
}
