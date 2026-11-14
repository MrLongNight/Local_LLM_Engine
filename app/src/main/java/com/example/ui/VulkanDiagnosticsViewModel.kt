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
 * Vulkan layer-offload and Q4_K decode-path diagnostics.
 */
abstract class VulkanDiagnosticsViewModel(
    application: Application,
    engine: LocalLlmEngine
) : BackendBenchmarkViewModel(application, engine) {

    /**
     * Measures Vulkan with several layer-offload levels on the same model and prompt.
     * The first load requests the maximum (99) and reads the model's actual layer count
     * from the native runtime string. Each following run changes only gpuLayers.
     * A final full-offload run checks for obvious thermal/frequency drift.
     */
    fun runVulkanLayerSweep() {
        if (_isBenchmarking.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Vulkan-Layer-Test erst starten, wenn keine Inferenz oder Modell-Operation läuft."
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
        val prompt =
            "Gib die Zahlen 1 bis 40 fortlaufend aus, ausschließlich durch Leerzeichen getrennt. Keine Erklärung."

        _isBenchmarking.value = true
        _benchmarkReport.value = "Vulkan-GPU-Layer-Test wird vorbereitet…"
        _errorMessage.value = null

        viewModelScope.launch {
            val rows = mutableListOf<String>()
            var restoreRequired = false
            var confirmedTotalLayers: Int? = null
            var stoppedReason: String? = null
            EngineDiagnostics.info(
                "benchmark",
                "GPU_LAYER_SWEEP_STARTED",
                "model=${model.name},modelBytes=${model.sizeBytes},requestedMaxGpuLayers=${VulkanLayerSweepPlan.MAX_REQUESTED_GPU_LAYERS}," +
                    "fractions=100,75,50,25,100_repeat,warmupTokens=${BackendBenchmarkPlan.WARMUP_TOKENS}," +
                    "measuredTokens=${BackendBenchmarkPlan.MEASURED_TOKENS},context=${originalConfig.contextSize}," +
                    "batch=${originalConfig.batchSize},threads=${originalConfig.threads},temperature=0.0,topP=1.0"
            )

            try {
                val initialSafetyStop = configurationTestSafetyBlock()
                if (initialSafetyStop != null) {
                    throw IllegalStateException("Sicherheitsabbruch vor dem ersten GPU-Layer-Lauf: " + initialSafetyStop)
                }

                restoreRequired = true
                _benchmarkReport.value = "Vulkan: Lade Modell mit maximalem Layer-Offload…"
                val firstConfig = VulkanLayerSweepPlan.configuration(
                    originalConfig,
                    VulkanLayerSweepPlan.MAX_REQUESTED_GPU_LAYERS
                )
                val firstLoadStarted = System.nanoTime()
                val firstLoaded = engine.loadModel(source, firstConfig).getOrElse { throw it }
                val firstLoadMs = (System.nanoTime() - firstLoadStarted) / 1_000_000L

                val firstBackend = backendFamily(firstLoaded.activeBackend)
                if (firstBackend != "VULKAN") {
                    throw IllegalStateException(
                        "Vulkan wurde angefordert, geladen wurde aber: " + firstLoaded.activeBackend
                    )
                }
                if (firstLoaded.sizeBytes != model.sizeBytes ||
                    (!model.sha256.isNullOrBlank() && !firstLoaded.sha256.isNullOrBlank() &&
                        model.sha256 != firstLoaded.sha256)
                ) {
                    throw IllegalStateException("Nach dem maximalen Vulkan-Load wurde nicht dieselbe Modelldatei bestätigt.")
                }

                val totalLayers = VulkanLayerSweepPlan.totalLayersFromBackend(firstLoaded.activeBackend)
                    ?: throw IllegalStateException(
                        "Die native Backend-Meldung enthält keine bestätigte Gesamtzahl der GPU-Layer: " +
                            firstLoaded.activeBackend
                    )
                confirmedTotalLayers = totalLayers
                val candidateLayers = VulkanLayerSweepPlan.layerCounts(totalLayers)
                val runLayers = candidateLayers + candidateLayers.first()
                EngineDiagnostics.info(
                    "benchmark",
                    "GPU_LAYER_SWEEP_PLAN",
                    "totalLayers=$totalLayers,candidateLayers=" + candidateLayers.joinToString(",") +
                        ",repeatFullOffload=true,activeBackend=${firstLoaded.activeBackend}"
                )

                for ((index, gpuLayers) in runLayers.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val safetyStop = if (index == 0) null else configurationTestSafetyBlock()
                    if (safetyStop != null) {
                        stoppedReason = safetyStop
                        rows += "ABBRUCH vor gpuLayers=$gpuLayers: $safetyStop"
                        EngineDiagnostics.info("benchmark", "GPU_LAYER_SWEEP_SAFETY_STOP", safetyStop)
                        break
                    }

                    val profile = when {
                        index == 0 -> "100% (Start)"
                        index == runLayers.lastIndex -> "100% (Wiederholung)"
                        else -> "${(gpuLayers * 100.0 / totalLayers).toInt()}%"
                    }
                    _benchmarkReport.value =
                        "Vulkan-Layer-Test ${index + 1}/${runLayers.size}: $gpuLayers/$totalLayers Layer ($profile)…"

                    val loadMs: Long
                    val loaded: ModelInfo
                    val configuredGpuLayers: Int
                    if (index == 0) {
                        loadMs = firstLoadMs
                        loaded = firstLoaded
                        configuredGpuLayers = VulkanLayerSweepPlan.MAX_REQUESTED_GPU_LAYERS
                    } else {
                        // Repeat the exact same maximum request as the opening run. The
                        // native loader clamps both to the model's real total layer count.
                        val requestLayers = if (index == runLayers.lastIndex) {
                            VulkanLayerSweepPlan.MAX_REQUESTED_GPU_LAYERS
                        } else {
                            gpuLayers
                        }
                        val profileConfig = VulkanLayerSweepPlan.configuration(originalConfig, requestLayers)
                        configuredGpuLayers = profileConfig.gpuLayers
                        val loadStarted = System.nanoTime()
                        loaded = engine.loadModel(source, profileConfig).getOrElse { throw it }
                        loadMs = (System.nanoTime() - loadStarted) / 1_000_000L
                    }

                    val actualBackend = backendFamily(loaded.activeBackend)
                    if (actualBackend != "VULKAN") {
                        throw IllegalStateException(
                            "Vulkan-Profil gpuLayers=$gpuLayers wechselte unerwartet auf: " + loaded.activeBackend
                        )
                    }
                    if (loaded.sizeBytes != model.sizeBytes ||
                        (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() &&
                            model.sha256 != loaded.sha256)
                    ) {
                        throw IllegalStateException("Für gpuLayers=$gpuLayers wurde nicht dieselbe Modelldatei bestätigt.")
                    }

                    val measured = collectBackendBenchmarkSample(
                        backend = BackendPreference.VULKAN,
                        maxTokens = BackendBenchmarkPlan.TOTAL_TOKENS,
                        warmupTokens = BackendBenchmarkPlan.WARMUP_TOKENS,
                        systemPromptSnapshot = systemPromptSnapshot,
                        benchmarkPrompt = prompt
                    )
                    if (measured.measuredTokens < 8) {
                        throw IllegalStateException(
                            "Zu wenige Mess-Tokens nach dem Warm-up bei gpuLayers=$gpuLayers: " +
                                measured.measuredTokens
                        )
                    }

                    val percent = gpuLayers * 100.0 / totalLayers
                    val reportLine = String.format(
                        java.util.Locale.US,
                        "gpuLayers=%d/%d (%.0f%%): load=%dms, promptTokens=%d, prefillMs=%s, " +
                            "TTFT=%s ms, measuredTokens=%d, measuredWallMs=%s, measured=%.3f tok/s, " +
                            "native=%.3f tok/s, generationMs=%d, finish=%s, activeBackend=%s",
                        gpuLayers,
                        totalLayers,
                        percent,
                        loadMs,
                        measured.promptTokens,
                        measured.promptEvaluationMs?.toString() ?: "n/a",
                        measured.firstTokenAfterPrefillMs?.toString() ?: "n/a",
                        measured.measuredTokens,
                        measured.measuredWallMs?.toString() ?: "n/a",
                        measured.measuredTokensPerSecond,
                        measured.result.tokensPerSecond,
                        measured.result.durationMs,
                        measured.result.finishReason.name,
                        loaded.activeBackend
                    )
                    rows += reportLine
                    EngineDiagnostics.info(
                        "benchmark",
                        "GPU_LAYER_SWEEP_RESULT",
                        "runIndex=${index + 1},profile=$profile,configuredGpuLayers=$configuredGpuLayers," +
                            "layerTarget=$gpuLayers,totalLayers=$totalLayers," +
                            "modelLoadMs=$loadMs,promptTokens=${measured.promptTokens}," +
                            "prefillMs=${measured.promptEvaluationMs ?: -1L}," +
                            "firstTokenAfterPrefillMs=${measured.firstTokenAfterPrefillMs ?: -1L}," +
                            "warmupTokens=${measured.warmupTokens},measuredTokens=${measured.measuredTokens}," +
                            "measuredWallMs=${measured.measuredWallMs ?: -1L}," +
                            "measuredTokensPerSecond=${measured.measuredTokensPerSecond}," +
                            "nativeTokensPerSecond=${measured.result.tokensPerSecond}," +
                            "generationMs=${measured.result.durationMs},finishReason=${measured.result.finishReason.name}," +
                            "context=${originalConfig.contextSize},batch=${originalConfig.batchSize}," +
                            "threads=${originalConfig.threads},batchThreads=${originalConfig.effectiveBatchThreads}," +
                            "microBatchRequested=${originalConfig.microBatchSize},backend=${loaded.activeBackend}"
                    )
                    if (index < runLayers.lastIndex) delay(BackendBenchmarkPlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                rows += "Vulkan-Layer-Test wurde abgebrochen."
                stoppedReason = "Vom Nutzer abgebrochen."
                EngineDiagnostics.info("benchmark", "GPU_LAYER_SWEEP_CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                stoppedReason = error.message ?: error.javaClass.simpleName
                rows += "FEHLER: $stoppedReason"
                EngineDiagnostics.error("benchmark", "GPU_LAYER_SWEEP_FAILED", error)
            } finally {
                if (restoreRequired) {
                    _benchmarkReport.value = "Stelle ursprüngliche Modellkonfiguration wieder her…"
                    val restore = withContext(NonCancellable) {
                        runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                    }
                    restore.onSuccess { restored ->
                        EngineDiagnostics.info(
                            "benchmark",
                            "GPU_LAYER_SWEEP_RESTORED",
                            "backendPreference=${originalConfig.backendPreference},gpuLayers=${originalConfig.gpuLayers}," +
                                "activeBackend=${restored.activeBackend}"
                        )
                    }.onFailure { error ->
                        rows += "KRITISCH: Restore fehlgeschlagen: " +
                            (error.message ?: error.javaClass.simpleName)
                        EngineDiagnostics.error("benchmark", "GPU_LAYER_SWEEP_RESTORE_FAILED", error)
                    }
                }

                _benchmarkReport.value = buildString {
                    appendLine("Vulkan GPU-Layer-Vergleich")
                    appendLine("Modell: ${model.name} (${model.sizeMb.toInt()} MB)")
                    appendLine("Native bestätigte Layer-Gesamtzahl: ${confirmedTotalLayers ?: "nicht bestätigt"}")
                    appendLine(
                        "Je Profil: eine Inferenz, 4 Warm-up-Tokens + 20 Mess-Tokens; Temperatur 0; Top-P 1; " +
                            "gleiche Prompt- und Kontext-/Batch-/Thread-Einstellungen."
                    )
                    rows.forEach { appendLine(it) }
                    if (stoppedReason != null) appendLine("Hinweis: $stoppedReason")
                    append("Die ursprüngliche Modellkonfiguration wurde anschließend wiederhergestellt; Details stehen im Diagnoseexport.")
                }
                _isBenchmarking.value = false
            }
        }
    }

    /**
     * Controlled Q4_K decode-path A/B comparison.
     * MMVQ_FORCE explicitly bypasses the Mali-specific AUTO base-DMMV policy;
     * native run-scoped path evidence must confirm every measured kernel selection.
     */
    fun runVulkanQ4kDecodePathSweep() {
        if (_isBenchmarking.value) return
        if (_isGenerating.value || _isImporting.value) {
            _errorMessage.value = "Q4_K-Kerneltest erst starten, wenn keine Inferenz oder Modell-Operation läuft."
            return
        }

        val model = currentModel.value
        if (model == null) {
            _errorMessage.value = "Bitte zuerst ein GGUF-Modell laden."
            return
        }
        if (!model.name.contains("Q4_K", ignoreCase = true)) {
            _errorMessage.value = "Der Q4_K-Decodepfad-Test benötigt ein Q4_K-GGUF-Modell."
            return
        }

        val originalConfig = _engineConfig.value
        val profiles = BackendBenchmarkPlan.vulkanQ4kDecodeConfigurations(originalConfig)
        val systemPromptSnapshot = _systemPrompt.value
        val source = if (model.path.startsWith("content://", ignoreCase = true)) {
            ModelSource.ContentUri(model.path, model.name)
        } else {
            ModelSource.FilePath(model.path)
        }
        val prompt =
            "Gib die Zahlen 1 bis 40 fortlaufend aus, ausschließlich durch Leerzeichen getrennt. Keine Erklärung."

        _isBenchmarking.value = true
        _benchmarkReport.value = "Q4_K-Integer-Dot gegen Basis-DMMV wird vorbereitet…"
        _errorMessage.value = null

        viewModelScope.launch {
            val rows = mutableListOf<String>()
            var restoreRequired = false
            var confirmedTotalLayers: Int? = null
            var stoppedReason: String? = null
            EngineDiagnostics.info(
                "benchmark",
                "VULKAN_Q4K_PATH_SWEEP_STARTED",
                "model=${model.name},modelBytes=${model.sizeBytes},profiles=AUTO,MMVQ_INTEGER_DOT,BASE_DMMV,BASE_DMMV_REPEAT,MMVQ_INTEGER_DOT_REPEAT,AUTO_REPEAT," +
                    "gpuLayersRequested=99,warmupTokens=${BackendBenchmarkPlan.WARMUP_TOKENS}," +
                    "measuredTokens=${BackendBenchmarkPlan.MEASURED_TOKENS},context=${originalConfig.contextSize}," +
                    "batch=${originalConfig.batchSize},threads=${originalConfig.threads}," +
                    "batchThreads=${originalConfig.effectiveBatchThreads}," +
                    "microBatchRequested=${originalConfig.microBatchSize},temperature=0.0,topP=1.0"
            )

            try {
                val initialSafetyStop = configurationTestSafetyBlock()
                if (initialSafetyStop != null) {
                    throw IllegalStateException("Sicherheitsabbruch vor dem ersten Q4_K-Kernel-Lauf: $initialSafetyStop")
                }

                restoreRequired = true
                _benchmarkReport.value = "Q4_K AUTO-Produktionspfad: Lade Modell mit vollem Vulkan-Offload…"
                val firstLoadStarted = System.nanoTime()
                val firstLoaded = engine.loadModel(source, profiles.first()).getOrElse { throw it }
                val firstLoadMs = (System.nanoTime() - firstLoadStarted) / 1_000_000L

                if (backendFamily(firstLoaded.activeBackend) != "VULKAN") {
                    throw IllegalStateException(
                        "Vulkan wurde angefordert, geladen wurde aber: ${firstLoaded.activeBackend}"
                    )
                }
                if (firstLoaded.sizeBytes != model.sizeBytes ||
                    (!model.sha256.isNullOrBlank() && !firstLoaded.sha256.isNullOrBlank() &&
                        model.sha256 != firstLoaded.sha256)
                ) {
                    throw IllegalStateException("Nach dem Q4_K-MMVQ-Load wurde nicht dieselbe Modelldatei bestätigt.")
                }

                val totalLayers = VulkanLayerSweepPlan.totalLayersFromBackend(firstLoaded.activeBackend)
                    ?: throw IllegalStateException(
                        "Die native Backend-Meldung enthält keine bestätigte Gesamtzahl der GPU-Layer: " +
                            firstLoaded.activeBackend
                    )
                confirmedTotalLayers = totalLayers
                EngineDiagnostics.info(
                    "benchmark",
                    "VULKAN_Q4K_PATH_SWEEP_PLAN",
                    "modelLayers=$totalLayers,requestedGpuLayers=99,activeBackend=${firstLoaded.activeBackend}," +
                        "pathOrder=AUTO,MMVQ_INTEGER_DOT,BASE_DMMV,BASE_DMMV,MMVQ_INTEGER_DOT,AUTO"
                )

                for ((index, profileConfig) in profiles.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val safetyStop = if (index == 0) null else configurationTestSafetyBlock()
                    if (safetyStop != null) {
                        stoppedReason = safetyStop
                        rows += "ABBRUCH vor ${profileConfig.vulkanMatvecWorkgroup.name}: $safetyStop"
                        EngineDiagnostics.info("benchmark", "VULKAN_Q4K_PATH_SWEEP_SAFETY_STOP", safetyStop)
                        break
                    }

                    val workgroup = profileConfig.vulkanMatvecWorkgroup
                    val baseDmmv = workgroup == com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q4K_BASE_DMMV
                    val autoPath = workgroup == com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.AUTO
                    val pathName = when {
                        baseDmmv -> "BASE_DMMV"
                        autoPath -> "AUTO"
                        else -> "MMVQ_INTEGER_DOT"
                    }
                    val displayName = when {
                        autoPath && index == 0 -> "AUTO (Produktionspfad)"
                        autoPath -> "AUTO (Wiederholung)"
                        workgroup == com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q4K_BASE_DMMV &&
                            index == 2 -> "Basis-DMMV"
                        workgroup == com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.Q4K_BASE_DMMV ->
                            "Basis-DMMV (Wiederholung)"
                        index == 1 -> "MMVQ Integer-Dot (erzwungen)"
                        else -> "MMVQ Integer-Dot (Wiederholung)"
                    }
                    _benchmarkReport.value = "Q4_K-Kerneltest ${index + 1}/${profiles.size}: $displayName…"

                    val loadMs: Long
                    val loaded: ModelInfo
                    if (index == 0) {
                        loadMs = firstLoadMs
                        loaded = firstLoaded
                    } else {
                        val loadStarted = System.nanoTime()
                        loaded = engine.loadModel(source, profileConfig).getOrElse { throw it }
                        loadMs = (System.nanoTime() - loadStarted) / 1_000_000L
                    }

                    if (backendFamily(loaded.activeBackend) != "VULKAN") {
                        throw IllegalStateException(
                            "Profil ${workgroup.name} wechselte unerwartet auf: ${loaded.activeBackend}"
                        )
                    }
                    if (loaded.sizeBytes != model.sizeBytes ||
                        (!model.sha256.isNullOrBlank() && !loaded.sha256.isNullOrBlank() &&
                            model.sha256 != loaded.sha256)
                    ) {
                        throw IllegalStateException("Für ${workgroup.name} wurde nicht dieselbe Modelldatei bestätigt.")
                    }
                    val modelLayers = VulkanLayerSweepPlan.totalLayersFromBackend(loaded.activeBackend)
                    if (modelLayers != totalLayers) {
                        throw IllegalStateException(
                            "Layer-Offload änderte sich für ${workgroup.name}: erwartet $totalLayers, erhalten $modelLayers."
                        )
                    }

                    val measured = collectBackendBenchmarkSample(
                        backend = BackendPreference.VULKAN,
                        maxTokens = BackendBenchmarkPlan.TOTAL_TOKENS,
                        warmupTokens = BackendBenchmarkPlan.WARMUP_TOKENS,
                        systemPromptSnapshot = systemPromptSnapshot,
                        benchmarkPrompt = prompt
                    )
                    val nativeRunId = measured.result.nativeRunId
                    if (nativeRunId.isBlank()) {
                        throw IllegalStateException(
                            "Native Inferenz-Run-ID fehlt; der Kernelpfad kann diesem A/B-Lauf nicht zugeordnet werden."
                        )
                    }
                    val pathEvidence = EngineDiagnostics.vulkanQ4kDecodePathLines(
                        getApplication(), nativeRunId
                    )
                    val observedKernelPaths = pathEvidence.mapNotNull { line ->
                        Regex("selected=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    }.toSet()
                    val observedReasons = pathEvidence.mapNotNull { line ->
                        Regex("reason=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    }.toSet()
                    val observedQuantizeY = pathEvidence.mapNotNull { line ->
                        Regex("quantizeY=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    }.toSet()
                    val observedIntDot = pathEvidence.mapNotNull { line ->
                        Regex("intDot=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    }.toSet()
                    val observedQ8Eligible = pathEvidence.mapNotNull { line ->
                        Regex("q8_1Eligible=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    }.toSet()
                    val observedQ8Eligibility = pathEvidence.mapNotNull { line ->
                        Regex("q8_1Eligibility=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    }.toSet()
                    val observedSourceTypes = pathEvidence.mapNotNull { line ->
                        Regex("src1Type=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    }.toSet()
                    val observedYKernelTypes = pathEvidence.mapNotNull { line ->
                        Regex("yKernelType=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                    }.toSet()
                    if (observedKernelPaths.size != 1) {
                        throw IllegalStateException(
                            "Q4_K-Kernelpfad nicht eindeutig: beobachtet=${observedKernelPaths.ifEmpty { setOf("keine native Pfadmeldung") }.joinToString()} " +
                                "(nativeRunId=$nativeRunId)."
                        )
                    }
                    val selectedKernelPath = observedKernelPaths.single()
                    val requestedMmvqFallback =
                        workgroup == com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.MMVQ_FORCE &&
                            observedReasons == setOf("q8_1_pipeline_unavailable_fallback")
                    val expectedKernelPath = when {
                        baseDmmv -> "BASE_TYPE_MMV"
                        autoPath || requestedMmvqFallback ->
                            selectedKernelPath // AUTO and an explicitly evidenced missing-pipeline fallback are evidence-driven.
                        else -> "MMVQ_INTEGER_DOT"
                    }
                    if (selectedKernelPath != expectedKernelPath) {
                        throw IllegalStateException(
                            "Q4_K-Kernelpfad nicht bestätigt: erwartet=$expectedKernelPath, beobachtet=$selectedKernelPath " +
                                "(nativeRunId=$nativeRunId). Der Lauf wird nicht als gültiger A/B-Wert gewertet."
                        )
                    }
                    if (observedReasons.size != 1 || observedQuantizeY.size != 1 ||
                        observedIntDot.size != 1 || observedQ8Eligible.size != 1 ||
                        observedQ8Eligibility.size != 1 ||
                        observedSourceTypes.size != 1 || observedYKernelTypes.size != 1
                    ) {
                        throw IllegalStateException(
                            "Q4_K-Dispatch-Metadaten fehlen oder sind mehrdeutig: reason=$observedReasons," +
                                "intDot=$observedIntDot,q8_1Eligible=$observedQ8Eligible," +
                                "q8_1Eligibility=$observedQ8Eligibility,quantizeY=$observedQuantizeY," +
                                "src1Type=$observedSourceTypes,yKernelType=$observedYKernelTypes"
                        )
                    }
                    val selectedReason = observedReasons.single()
                    // A requested MMVQ path can legitimately fall back when the Q8_1
                    // pipeline is unavailable. In that case native evidence must say so;
                    // never mislabel the executed BASE path as MMVQ.
                    val q8PipelineFallback =
                        selectedKernelPath == "BASE_TYPE_MMV" &&
                            selectedReason == "q8_1_pipeline_unavailable_fallback"
                    val expectedReason = when {
                        workgroup == com.mydocanizer.llm.engine.VulkanMatvecWorkgroup.MMVQ_FORCE &&
                            selectedKernelPath == "MMVQ_INTEGER_DOT" -> "explicit_mmvq_override"
                        baseDmmv -> "explicit_base_override"
                        selectedKernelPath == "MMVQ_INTEGER_DOT" -> "intDot_q4k_batch1"
                        else -> null
                    }
                    if (expectedReason != null && selectedReason != expectedReason) {
                        throw IllegalStateException(
                            "Q4_K-Dispatchgrund weicht ab: erwartet=$expectedReason, beobachtet=$selectedReason"
                        )
                    }
                    val intDotAvailable = observedIntDot.single() == "1"
                    val q8Eligible = observedQ8Eligible.single() == "1"
                    val q8EligibilityReason = observedQ8Eligibility.single()
                    if (observedIntDot.single() !in setOf("0", "1") ||
                        observedQ8Eligible.single() !in setOf("0", "1")
                    ) {
                        throw IllegalStateException(
                            "Q4_K-Hardware-/Q8_1-Flags ungültig: intDot=$observedIntDot,q8_1Eligible=$observedQ8Eligible"
                        )
                    }
                    if (q8Eligible != (q8EligibilityReason == "eligible")) {
                        throw IllegalStateException(
                            "Q8_1-Eignungsflag und Grund widersprechen sich: eligible=$q8Eligible,reason=$q8EligibilityReason"
                        )
                    }
                    if (selectedKernelPath == "MMVQ_INTEGER_DOT" &&
                        (!intDotAvailable || !q8Eligible || selectedReason !in setOf(
                            "intDot_q4k_batch1", "explicit_mmvq_override"
                        ))
                    ) {
                        throw IllegalStateException(
                            "MMVQ wurde ohne bestätigte Integer-Dot/Q8_1-Voraussetzungen ausgewählt: " +
                                "intDot=$intDotAvailable,q8_1Eligible=$q8Eligible,reason=$selectedReason"
                        )
                    }
                    if (q8PipelineFallback &&
                        (!intDotAvailable || !q8Eligible || q8EligibilityReason != "eligible" ||
                            observedQuantizeY.single() != "0" ||
                            observedYKernelTypes.single() == "q8_1")
                    ) {
                        throw IllegalStateException(
                            "Q8_1-Pipeline-Fallback ist nicht durch einen tatsächlich geeigneten, " +
                                "aber nicht verfügbaren Q8_1-Pipeline-Pfad belegt: " +
                                "intDot=$intDotAvailable,q8_1Eligible=$q8Eligible," +
                                "q8_1Eligibility=$q8EligibilityReason,quantizeY=$observedQuantizeY," +
                                "yKernelType=$observedYKernelTypes"
                        )
                    }
                    if (selectedReason == "q8_1_pipeline_unavailable_fallback" && !q8PipelineFallback) {
                        throw IllegalStateException(
                            "Q8_1-Pipeline-Fallback meldet einen widersprüchlichen tatsächlich gewählten Kernelpfad."
                        )
                    }
                    if (autoPath && loaded.activeBackend.contains("Mali", ignoreCase = true) &&
                        selectedKernelPath == "BASE_TYPE_MMV" && !q8PipelineFallback
                    ) {
                        val expectedMaliAutoReason = if (!intDotAvailable) {
                            "mali_auto_base_without_intDot"
                        } else {
                            "mali_auto_base_q8_1_ineligible"
                        }
                        if (selectedReason != expectedMaliAutoReason ||
                            (intDotAvailable && q8Eligible) ||
                            (!intDotAvailable && q8EligibilityReason.isBlank())
                        ) {
                            throw IllegalStateException(
                                "Mali AUTO wählte BASE ohne zutreffenden Hardware-/Q8_1-Grund: " +
                                    "intDot=$intDotAvailable,q8_1Eligible=$q8Eligible," +
                                    "q8_1Eligibility=$q8EligibilityReason,reason=$selectedReason"
                            )
                        }
                    }
                    if (selectedReason == "mali_auto_base_q8_1_ineligible" &&
                        (!intDotAvailable || q8Eligible)
                    ) {
                        throw IllegalStateException(
                            "Mali AUTO meldet Q8_1-Ineligibility, obwohl Integer-Dot fehlt oder Q8_1 geeignet ist."
                        )
                    }
                    val expectedQuantizeY = if (selectedKernelPath == "MMVQ_INTEGER_DOT") "1" else "0"
                    if (observedQuantizeY.single() != expectedQuantizeY) {
                        throw IllegalStateException(
                            "Q4_K quantizeY passt nicht zum Kernelpfad $selectedKernelPath: $observedQuantizeY"
                        )
                    }
                    if (selectedKernelPath == "MMVQ_INTEGER_DOT" &&
                        (observedSourceTypes.single() != "f32" || observedYKernelTypes.single() != "q8_1")
                    ) {
                        throw IllegalStateException(
                            "MMVQ-Integer-Dot lief nicht über F32→Q8_1: src1Type=$observedSourceTypes,yKernelType=$observedYKernelTypes"
                        )
                    }
                    if (measured.measuredTokens < 8) {
                        throw IllegalStateException(
                            "Zu wenige Mess-Tokens für ${workgroup.name}: ${measured.measuredTokens}"
                        )
                    }

                    val measuredSpeed = measured.measuredTokensPerSecond
                    val reportLine = String.format(
                        java.util.Locale.US,
                        "%s: selected=%s,reason=%s,quantizeY=%s,src1=%s,yKernel=%s, gpuLayersRequested=%d, modelLayers=%d, load=%dms, promptTokens=%d, prefillMs=%s, " +
                            "TTFT=%s ms, measuredTokens=%d, measuredWallMs=%s, decode=%.3f tok/s, " +
                            "native=%.3f tok/s, generationMs=%d, finish=%s",
                        displayName,
                        selectedKernelPath,
                        selectedReason,
                        observedQuantizeY.single(),
                        observedSourceTypes.single(),
                        observedYKernelTypes.single(),
                        profileConfig.gpuLayers,
                        totalLayers,
                        loadMs,
                        measured.promptTokens,
                        measured.promptEvaluationMs?.toString() ?: "n/a",
                        measured.firstTokenAfterPrefillMs?.toString() ?: "n/a",
                        measured.measuredTokens,
                        measured.measuredWallMs?.toString() ?: "n/a",
                        measuredSpeed,
                        measured.result.tokensPerSecond,
                        measured.result.durationMs,
                        measured.result.finishReason.name
                    )
                    rows += reportLine
                    EngineDiagnostics.info(
                        "benchmark",
                        "VULKAN_Q4K_PATH_SWEEP_RESULT",
                        "runIndex=${index + 1},path=$pathName,pathNativeId=${workgroup.nativeId}," +
                            "gpuLayersRequested=${profileConfig.gpuLayers},modelLayers=$modelLayers," +
                            "loadMs=$loadMs,promptTokens=${measured.promptTokens}," +
                            "prefillMs=${measured.promptEvaluationMs ?: -1L}," +
                            "firstTokenAfterPrefillMs=${measured.firstTokenAfterPrefillMs ?: -1L}," +
                            "warmupTokens=${measured.warmupTokens},measuredTokens=${measured.measuredTokens}," +
                            "measuredWallMs=${measured.measuredWallMs ?: -1L}," +
                            "measuredTokensPerSecond=$measuredSpeed," +
                            "nativeTokensPerSecond=${measured.result.tokensPerSecond}," +
                            "generationMs=${measured.result.durationMs}," +
                            "finishReason=${measured.result.finishReason.name}," +
                            "nativeRunId=$nativeRunId,nativePathVerified=true," +
                            "observedKernelPaths=${observedKernelPaths.joinToString("|")}," +
                            "observedReasons=${observedReasons.joinToString("|")},intDot=${observedIntDot.single()}," +
                            "q8_1Eligible=${observedQ8Eligible.single()},q8_1Eligibility=${q8EligibilityReason}," +
                            "quantizeY=${observedQuantizeY.single()}," +
                            "src1Type=${observedSourceTypes.single()},yKernelType=${observedYKernelTypes.single()}," +
                            "context=${profileConfig.contextSize},batch=${profileConfig.batchSize}," +
                            "threads=${profileConfig.threads},batchThreads=${profileConfig.effectiveBatchThreads}," +
                            "microBatch=${profileConfig.effectiveMicroBatchSize},backend=${loaded.activeBackend}"
                    )
                    if (index < profiles.lastIndex) delay(BackendBenchmarkPlan.COOLDOWN_MS)
                }
            } catch (cancelled: CancellationException) {
                rows += "Q4_K-Kerneltest wurde abgebrochen."
                stoppedReason = "Vom Nutzer abgebrochen."
                EngineDiagnostics.info("benchmark", "VULKAN_Q4K_PATH_SWEEP_CANCELLED")
                throw cancelled
            } catch (error: Throwable) {
                stoppedReason = error.message ?: error.javaClass.simpleName
                rows += "FEHLER: $stoppedReason"
                EngineDiagnostics.error("benchmark", "VULKAN_Q4K_PATH_SWEEP_FAILED", error)
            } finally {
                if (restoreRequired) {
                    _benchmarkReport.value = "Stelle ursprüngliche Modellkonfiguration wieder her…"
                    val restore = withContext(NonCancellable) {
                        runCatching { engine.loadModel(source, originalConfig).getOrThrow() }
                    }
                    restore.onSuccess { restored ->
                        EngineDiagnostics.info(
                            "benchmark",
                            "VULKAN_Q4K_PATH_SWEEP_RESTORED",
                            "backendPreference=${originalConfig.backendPreference}," +
                                "gpuLayers=${originalConfig.gpuLayers}," +
                                "vulkanMatvecWorkgroup=${originalConfig.vulkanMatvecWorkgroup}," +
                                "activeBackend=${restored.activeBackend}"
                        )
                    }.onFailure { error ->
                        rows += "KRITISCH: Restore fehlgeschlagen: " +
                            (error.message ?: error.javaClass.simpleName)
                        EngineDiagnostics.error("benchmark", "VULKAN_DMMV_SWEEP_RESTORE_FAILED", error)
                    }
                }

                _benchmarkReport.value = buildString {
                    appendLine("Vulkan Q4_K-Decodepfad-Vergleich")
                    appendLine("Modell: ${model.name} (${model.sizeMb.toInt()} MB)")
                    appendLine("Native bestätigte Modell-Layerzahl: ${confirmedTotalLayers ?: "nicht bestätigt"}; je Lauf wurden 99 GPU-Layer angefordert.")
                    appendLine(
                        "Vergleicht Q8_1 Integer-Dot-MMVQ mit Basis-DMMV nur für Q4_K-Decode. " +
                            "Ablauf: MMVQ, Basis-DMMV, Basis-DMMV-Wiederholung, MMVQ-Wiederholung. " +
                            "Je Profil 4 Warm-up- und 20 Mess-Tokens; Temperatur 0; Top-P 1; voller Vulkan-Offload."
                    )
                    rows.forEach { appendLine(it) }
                    if (stoppedReason != null) appendLine("Hinweis: $stoppedReason")
                    append("Die nativen Q4_K_DECODE_PATH-Meldungen bestätigen den tatsächlich aktiven Shaderpfad; " +
                        "die ursprüngliche Modellkonfiguration wurde anschließend wiederhergestellt.")
                }
                _isBenchmarking.value = false
            }
        }
    }
}
