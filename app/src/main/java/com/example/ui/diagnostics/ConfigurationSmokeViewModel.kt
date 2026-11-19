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
import com.mydocanizer.llm.engine.EngineState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            Runtime.getRuntime().availableProcessors()
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
            val profileStatuses = mutableMapOf<String, String>()
            var stopRemainingProfilesReason: String? = null
            var pendingRecoveryFromProfile: String? = null
            var pendingRecoveryObservedState: String? = null
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
                    val previousStopReason = stopRemainingProfilesReason
                    if (previousStopReason != null) {
                        profileStatuses[profile.id] = "NOT_RUN"
                        recordConfigurationSmokeProfileResult(
                            profile.id,
                            "NOT_RUN",
                            previousStopReason
                        )
                        rows += profile.id + ": NOT_RUN (" + previousStopReason + ")"
                        continue
                    }

                    EngineDiagnostics.info(
                        "config_test",
                        "CONFIG_SMOKE_PROFILE_STARTED",
                        "profile=" + profile.id + ",index=" + (index + 1) +
                            ",total=" + profiles.size + ",backend=" + profile.expectedBackend
                    )
                    profileStatuses[profile.id] = "RUNNING"
                    var profileLoadMs: Long? = null

                    try {
                        val stop = configurationTestSafetyBlock()
                        if (stop != null) {
                            stoppedReason = stop
                            stopRemainingProfilesReason = "SAFETY_STOP"
                            profileStatuses[profile.id] = "NOT_RUN"
                            recordConfigurationSmokeProfileResult(
                                profile.id,
                                "NOT_RUN",
                                "SAFETY_STOP: " + stop
                            )
                            rows += "ABBRUCH vor " + profile.id + ": " + stop
                            EngineDiagnostics.info("config_test", "CONFIG_SMOKE_SAFETY_STOP", stop)
                            break
                        }

                        _configSmokeReport.value =
                            "Profil " + (index + 1) + "/" + profiles.size + ": " + profile.label + " – Modell wird neu geladen…"
                        restoreRequired = true
                        val loadStart = System.nanoTime()
                        val loadResult = engine.loadModel(source, profile.config)
                        profileLoadMs = (System.nanoTime() - loadStart) / 1_000_000L
                        if (loadResult.isFailure) {
                            val loadError = loadResult.exceptionOrNull()
                                ?: IllegalStateException("Unbekannter Modellladefehler")
                            if (profile.expectedBackend == "VULKAN" && isVulkanBackendUnavailable(loadError)) {
                                val reason = "VULKAN_DEVICE_UNAVAILABLE_OR_INITIALIZATION_FAILED"
                                rows += "Vulkan nicht verfügbar (nicht als Pass gewertet): " +
                                    (loadError.message ?: loadError.javaClass.simpleName)
                                stoppedReason = "Vulkan-Backend nicht verfügbar; CPU-Referenz bleibt separat dokumentiert."
                                profileStatuses[profile.id] = "UNAVAILABLE"
                                recordConfigurationSmokeProfileResult(
                                    profile.id,
                                    "UNAVAILABLE",
                                    reason,
                                    profileLoadMs
                                )
                                EngineDiagnostics.info(
                                    "config_test",
                                    "CONFIG_SMOKE_RESULT",
                                    "profile=" + profile.id + ",requestedBackend=VULKAN,backend=UNAVAILABLE," +
                                        "status=UNAVAILABLE,reason=" + reason + ",loadMs=" + profileLoadMs
                                )
                                stopRemainingProfilesReason = "VULKAN_UNAVAILABLE"
                                break
                            }
                            throw loadError
                        }

                        val loaded = loadResult.getOrThrow()
                        if (loaded.sizeBytes != model.sizeBytes ||
                            (!model.sha256.isNullOrBlank() && model.sha256 != loaded.sha256)
                        ) {
                            throw ConfigurationSmokeModelIdentityException(
                                "Nach Neuladen wurde nicht dieselbe Modelldatei bestätigt (Größe/SHA-256)."
                            )
                        }
                        pendingRecoveryFromProfile?.let { failedProfile ->
                            EngineDiagnostics.info(
                                "config_test",
                                "CONFIG_SMOKE_PROFILE_RECOVERED",
                                "failedProfile=" + failedProfile + ",nextProfile=" + profile.id +
                                    ",stableState=" + (pendingRecoveryObservedState ?: "unknown") +
                                    ",modelLoadConfirmed=true,activeBackend=" + loaded.activeBackend
                            )
                            pendingRecoveryFromProfile = null
                            pendingRecoveryObservedState = null
                        }
                        val backend = backendFamily(loaded.activeBackend)
                        if (backend == "UNKNOWN") {
                            throw IllegalStateException("Aktives Backend nicht eindeutig: " + loaded.activeBackend)
                        }
                        if (backend != profile.expectedBackend) {
                            throw IllegalStateException(
                                "Angefordert wurde " + profile.expectedBackend +
                                    ", tatsächlich aktiv ist " + loaded.activeBackend
                            )
                        }
                        confirmedBackends += profile.id + "=" + backend
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
                        val normalizedOutput = InferenceConfigSmokePlan.normalizeOutput(sample.result.text)
                        val expectedOutput = InferenceConfigSmokePlan.EXPECTED_OUTPUT
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
                        val expectsQ4kDecodeTrace = Regex("Q4[_-]?K", RegexOption.IGNORE_CASE).containsMatchIn(
                            model.name + " " + model.description + " " + model.path
                        )
                        val q4kPathGatePassed = !expectsQ4kDecodeTrace ||
                            q4kFallbackValidation.status == Q4kPipelineFallbackPolicy.Status.VALID
                        val outputGatePassed = outputMatches && completedNormally &&
                            sample.result.tokensGenerated >= 4 && q4kPathGatePassed
                        profileSamples[profile.id] = sample.copy(outputGatePassed = outputGatePassed)
                        val report = String.format(
                            java.util.Locale.US,
                            "%s | requested=%s | backend=%s | load=%dms | n_threads=%d | n_threads_batch=%d | n_batch=%d | n_ubatch=%d | prompt=%d/%s ms | TTFT=%s ms | decode=%dms | output=%d | %.3f tok/s | finish=%s | exactOutput=%s | gate=%s | graphSplits=%d | run=%s",
                            profile.label, profile.expectedBackend, backend, profileLoadMs ?: -1L, cfg.threads, cfg.effectiveBatchThreads,
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
                                ",loadMs=" + (profileLoadMs ?: -1L) + ",nThreads=" + cfg.threads +
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
                        val profileStatus = if (outputGatePassed) "PASS" else "FAIL"
                        profileStatuses[profile.id] = profileStatus
                        recordConfigurationSmokeProfileResult(
                            profile.id,
                            profileStatus,
                            if (outputGatePassed) "OUTPUT_AND_FINISH_GATE_PASSED" else "OUTPUT_OR_FINISH_GATE_FAILED",
                            profileLoadMs,
                            sample.result.nativeRunId
                        )
                        if (!outputGatePassed) {
                            val rejection = "profile=" + profile.id +
                                ",outputTokens=" + sample.result.tokensGenerated +
                                ",exactOutput=" + outputMatches +
                                ",finishReason=" + sample.result.finishReason.name
                            rows += "Korrektheits-Gate fehlgeschlagen: " + rejection
                            EngineDiagnostics.info("config_test", "CONFIG_SMOKE_OUTPUT_REJECTED", rejection)
                        }
                        if (index < profiles.lastIndex && InferenceConfigSmokePlan.COOLDOWN_MS > 0L) {
                            delay(InferenceConfigSmokePlan.COOLDOWN_MS)
                        }
                    } catch (cancelled: CancellationException) {
                        profileStatuses[profile.id] = "CANCELLED"
                        recordConfigurationSmokeProfileResult(
                            profile.id,
                            "CANCELLED",
                            "USER_CANCELLED",
                            profileLoadMs
                        )
                        stopRemainingProfilesReason = "USER_CANCELLED"
                        throw cancelled
                    } catch (identityMismatch: ConfigurationSmokeModelIdentityException) {
                        pendingRecoveryFromProfile?.let { failedProfile ->
                            EngineDiagnostics.error(
                                "config_test",
                                "CONFIG_SMOKE_PROFILE_RECOVERY_FAILED",
                                IllegalStateException(
                                    "Following model load for $profile failed identity verification after $failedProfile"
                                )
                            )
                            pendingRecoveryFromProfile = null
                            pendingRecoveryObservedState = null
                        }
                        profileStatuses[profile.id] = "FAIL"
                        recordConfigurationSmokeProfileResult(
                            profile.id,
                            "FAIL",
                            "MODEL_IDENTITY_MISMATCH: " + (identityMismatch.message ?: "unknown"),
                            profileLoadMs
                        )
                        rows += profile.id + ": FAIL (MODEL_IDENTITY_MISMATCH)"
                        stopRemainingProfilesReason = "MODEL_IDENTITY_MISMATCH"
                        stoppedReason = "Modellidentität änderte sich zwischen den Profilen; weitere Läufe wurden gestoppt."
                    } catch (error: Exception) {
                        pendingRecoveryFromProfile?.let { failedProfile ->
                            EngineDiagnostics.error(
                                "config_test",
                                "CONFIG_SMOKE_PROFILE_RECOVERY_FAILED",
                                IllegalStateException(
                                    "Following model load for $profile failed after profile $failedProfile",
                                    error
                                )
                            )
                            pendingRecoveryFromProfile = null
                            pendingRecoveryObservedState = null
                            stopRemainingProfilesReason = "SERVICE_RECOVERY_UNAVAILABLE"
                        }
                        val vulkanUnavailable =
                            profile.expectedBackend == "VULKAN" && isVulkanBackendUnavailable(error)
                        val status = if (vulkanUnavailable) "UNAVAILABLE" else "FAIL"
                        val reason = if (vulkanUnavailable) {
                            "VULKAN_DEVICE_UNAVAILABLE_OR_INITIALIZATION_FAILED"
                        } else {
                            error.message ?: error.javaClass.simpleName
                        }
                        profileStatuses[profile.id] = status
                        recordConfigurationSmokeProfileResult(profile.id, status, reason, profileLoadMs)
                        EngineDiagnostics.info(
                            "config_test",
                            "CONFIG_SMOKE_RESULT",
                            "profile=" + profile.id + ",requestedBackend=" + profile.expectedBackend +
                                ",status=" + status + ",reason=" + reason +
                                ",loadMs=" + (profileLoadMs ?: -1L)
                        )
                        rows += profile.id + ": " + status + " (" + reason + ")"
                        stoppedReason = "Profil " + profile.id + " fehlgeschlagen: " + reason

                        val nextProfile = profiles.getOrNull(index + 1)
                        if (nextProfile != null && !vulkanUnavailable) {
                            var followUpAttemptAllowed = false
                            try {
                                EngineDiagnostics.info(
                                    "config_test",
                                    "CONFIG_SMOKE_PROFILE_RECOVERY_STARTED",
                                    "failedProfile=" + profile.id + ",nextProfile=" + nextProfile.id +
                                        ",waitMs=" + (profile.config.timeoutConfig.reconnectMs +
                                        profile.config.timeoutConfig.serviceBindMs)
                                )
                                val stableState = awaitRecoverableConfigurationService(
                                    profile.id,
                                    profile.config.timeoutConfig
                                )
                                followUpAttemptAllowed = true
                                pendingRecoveryFromProfile = profile.id
                                pendingRecoveryObservedState = stableState
                                EngineDiagnostics.info(
                                    "config_test",
                                    "CONFIG_SMOKE_PROFILE_SERVICE_STATE",
                                    "failedProfile=" + profile.id + ",nextProfile=" + nextProfile.id +
                                        ",state=" + stableState + ",nextAction=load_same_model"
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (recoveryError: Exception) {
                                val recoveryReason = recoveryError.message ?: recoveryError.javaClass.simpleName
                                EngineDiagnostics.error(
                                    "config_test",
                                    "CONFIG_SMOKE_PROFILE_RECOVERY_FAILED",
                                    recoveryError
                                )
                                rows += "Folgeprofil " + nextProfile.id + " nicht gestartet: Service-Recovery nicht bestätigt (" +
                                    recoveryReason + ")"
                                stopRemainingProfilesReason = "SERVICE_RECOVERY_UNAVAILABLE: " + recoveryReason
                                stoppedReason = "Service-Recovery nach " + profile.id + " fehlgeschlagen: " + recoveryReason
                            }
                            if (!InferenceConfigSmokePlan.mayContinueAfterProfileFailure(
                                    recoveryConfirmed = followUpAttemptAllowed
                                )
                            ) {
                                stopRemainingProfilesReason = stopRemainingProfilesReason
                                    ?: "SERVICE_RECOVERY_UNAVAILABLE"
                            }
                        }
                    }
                }
                val cpuSample = profileSamples["cpu-reference"]
                val vulkanSample = profileSamples["vulkan-candidate"]
                var numericGate: EngineDiagnostics.BackendCorrectnessResult? = null
                val pairedGateSummary = InferenceConfigSmokePlan.evaluatePairedGate(
                    cpuSample?.outputGatePassed,
                    vulkanSample?.outputGatePassed
                ) {
                    EngineDiagnostics.evaluateCpuVulkanCorrectnessGate(
                        getApplication<Application>(),
                        requireNotNull(cpuSample).result.nativeRunId,
                        requireNotNull(vulkanSample).result.nativeRunId
                    ).also { numericGate = it }.status
                }
                val gate = numericGate ?: EngineDiagnostics.BackendCorrectnessResult(
                    "UNAVAILABLE",
                    "CPU_OR_VULKAN_RUN_MISSING"
                )
                val cpuOutputGateStatus = when (cpuSample?.outputGatePassed) {
                    true -> "PASS"
                    false -> "FAIL"
                    null -> "UNAVAILABLE"
                }
                val vulkanOutputGateStatus = when (vulkanSample?.outputGatePassed) {
                    true -> "PASS"
                    false -> "FAIL"
                    null -> "UNAVAILABLE"
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
                EngineDiagnostics.info(
                    "config_test",
                    "CONFIG_SMOKE_RESULT",
                    "profile=paired-gates,outputGateStatus=" + pairedGateSummary.outputGateStatus +
                        ",cpuOutputGateStatus=" + cpuOutputGateStatus +
                        ",vulkanOutputGateStatus=" + vulkanOutputGateStatus +
                        ",backendParityStatus=" + pairedGateSummary.backendParityStatus +
                        ",status=" + pairedGateSummary.combinedStatus +
                        ",cpuNativeRunId=" + (cpuSample?.result?.nativeRunId ?: "unavailable") +
                        ",vulkanNativeRunId=" + (vulkanSample?.result?.nativeRunId ?: "unavailable")
                )
                rows += "Ausgabe-/Finish-Gate: " + pairedGateSummary.outputGateStatus +
                    " (CPU=" + cpuOutputGateStatus + ", Vulkan=" + vulkanOutputGateStatus + ")"
                rows += "Numerisches CPU/Vulkan-Paritäts-Gate: " + gate.status +
                    " (" + gate.reason + ", Schritte=" + gate.comparedSteps +
                    ", min. Top-5-Überlappung=" + gate.minimumTop5Overlap + "/5)"
                rows += "Kombiniertes CPU/Vulkan-Gesamtgate: " + pairedGateSummary.combinedStatus
                if (pairedGateSummary.combinedStatus != "PASS") {
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
                profiles.forEach { profile ->
                    when (profileStatuses[profile.id]) {
                        null -> {
                            val reason = stopRemainingProfilesReason ?: stoppedReason ?: "PRIOR_PROFILE_NOT_COMPLETED"
                            profileStatuses[profile.id] = "NOT_RUN"
                            recordConfigurationSmokeProfileResult(profile.id, "NOT_RUN", reason)
                        }
                        "RUNNING" -> {
                            val reason = stoppedReason ?: "UNHANDLED_PROFILE_FAILURE"
                            profileStatuses[profile.id] = "FAIL"
                            recordConfigurationSmokeProfileResult(profile.id, "FAIL", reason)
                        }
                    }
                }
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

    private class ConfigurationSmokeModelIdentityException(message: String) : IllegalStateException(message)

    private fun recordConfigurationSmokeProfileResult(
        profileId: String,
        status: String,
        reason: String,
        loadMs: Long? = null,
        nativeRunId: String? = null
    ) {
        val safeReason = reason.replace(',', ';').replace('\\n', ' ').take(320)
        EngineDiagnostics.info(
            "config_test",
            "CONFIG_SMOKE_PROFILE_RESULT",
            "profile=" + profileId + ",status=" + status +
                ",reason=" + safeReason +
                ",loadMs=" + (loadMs ?: -1L) +
                ",nativeRunId=" + (nativeRunId ?: "unavailable")
        )
    }

    /**
     * Wait for a stable IPC state before attempting the next profile. A connected
     * Binder or an Error state is not itself proof of a recovered native model:
     * the next profile's own loadModel callback is the definitive rebuild check.
     */
    private suspend fun awaitRecoverableConfigurationService(
        failedProfileId: String,
        timeoutConfig: com.mydocanizer.llm.engine.TimeoutConfig
    ): String {
        val waitMs = timeoutConfig.reconnectMs + timeoutConfig.serviceBindMs
        val stableState = withTimeoutOrNull(waitMs) {
            engine.state.first { state ->
                state == EngineState.Ready ||
                    state is EngineState.ModelLoaded ||
                    state is EngineState.Error ||
                    state == EngineState.Closed
            }
        } ?: throw IllegalStateException(
            "Kein stabiler Inference-Service nach Profil " + failedProfileId +
                " innerhalb von " + waitMs + " ms"
        )

        return when (stableState) {
            EngineState.Ready -> "READY_REQUIRES_PROFILE_MODEL_LOAD"
            is EngineState.ModelLoaded -> "MODEL_LOADED"
            is EngineState.Error -> "ERROR_REQUIRES_PROFILE_MODEL_RELOAD"
            EngineState.Closed -> throw IllegalStateException("Inference-Service/Engine ist geschlossen.")
            else -> throw IllegalStateException(
                "Unerwarteter Zustand nach Reconnect: " + stableState.javaClass.simpleName
            )
        }
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
        val prompt = InferenceConfigSmokePlan.USER_PROMPT
        val expectedOutput = InferenceConfigSmokePlan.EXPECTED_OUTPUT
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
                ",exactOutput=" + (InferenceConfigSmokePlan.normalizeOutput(result.text) == expectedOutput) +
                ",text=" + result.text
        )
        if (!InferenceConfigSmokePlan.normalizeOutput(result.text).startsWith("ERGEBNIS=155\n")) {
            EngineDiagnostics.info("config_test", "CONFIG_SMOKE_OUTPUT_CHECK_FAILED", "expectedFirstLine=ERGEBNIS=155")
        }
        val outputGatePassed = result.finishReason == com.mydocanizer.llm.engine.FinishReason.STOP &&
            InferenceConfigSmokePlan.normalizeOutput(result.text) == expectedOutput &&
            result.tokensGenerated >= 4
        return ConfigurationSmokeSample(
            result, firstTokenMs, promptElapsedMs, promptTokens, outputGatePassed
        )
    }
}
