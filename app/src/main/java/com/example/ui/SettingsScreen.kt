package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.GdnPolicy
import com.mydocanizer.llm.engine.ModelInfo
import com.mydocanizer.llm.engine.ReloadAfterAbort
import com.mydocanizer.llm.engine.VulkanMatvecWorkgroup
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: EngineViewModel,
    modifier: Modifier = Modifier
) {
    val config: EngineConfig by viewModel.engineConfig.collectAsState()
    val systemPrompt: String by viewModel.systemPrompt.collectAsState()
    val currentModel: ModelInfo? by viewModel.currentModel.collectAsState()
    val isBenchmarking: Boolean by viewModel.isBenchmarking.collectAsState()
    val benchmarkReport: String? by viewModel.benchmarkReport.collectAsState()
    val isConfigSmokeTesting: Boolean by viewModel.isConfigSmokeTesting.collectAsState()
    val configSmokeReport: String? by viewModel.configSmokeReport.collectAsState()
    val isGenerating: Boolean by viewModel.isGenerating.collectAsState()
    val isImporting: Boolean by viewModel.isImporting.collectAsState()
    LaunchedEffect(Unit) {
        EngineDiagnostics.info("ui", "SETTINGS_SCREEN_VISIBLE")
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // System Prompt Section
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "System-Prompt",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Wird vor jede Konversation gesetzt. Bleibt bei Kontextkürzungen stets vollständig erhalten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { viewModel.updateSystemPrompt(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("system_prompt_input"),
                        minLines = 3,
                        maxLines = 6
                    )
                }
            }
        }

        // Hardware Acceleration / Backend Selection Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Hardware-Beschleunigung & GPU",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Das Standardprofil erzwingt Vulkan-GPU-Offload. CPU wird nicht stillschweigend als Ersatz verwendet: Wenn Vulkan das Modell nicht laden kann, erscheint ein klarer Fehler statt eines möglicherweise speicherintensiven CPU-Ladeversuchs. AUTO und CPU können bei Bedarf manuell gewählt werden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Backend-, GPU-Layer-, Kontext-, Thread- und Sampler-Änderungen werden beim nächsten Modellladen übernommen. Temperatur, Top-P und Ausgabelimit gelten ab der nächsten Anfrage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    BackendPreference.entries.forEach { pref ->
                        FilterChip(
                            selected = config.backendPreference == pref,
                            onClick = { viewModel.updateConfig(config.copy(backendPreference = pref)) },
                            label = { Text(pref.label) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .testTag("backend_chip_${pref.name}")
                        )
                    }

                    if (config.backendPreference != BackendPreference.CPU) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "GPU-Layer-Offload angefordert: ${config.gpuLayers} (99 = maximal)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = config.gpuLayers.toFloat(),
                            onValueChange = { viewModel.updateConfig(config.copy(gpuLayers = it.roundToInt())) },
                            valueRange = 1f..99f,
                            steps = 98,
                            modifier = Modifier.testTag("gpu_layers_slider")
                        )
                    }

                    if (config.backendPreference != BackendPreference.CPU) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Vulkan-DMMV-Workgroup (Diagnose)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "AUTO nutzt für Q4_K auf Mali den gemessenen Basis-MMV-Pfad; SUBGROUP und LARGE erzwingen nur die MatVec-Workgroup-Größe. Die Q4_K/Q5_K/Q6_K- und Prefill-Overrides bleiben interne A/B-Profile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        VulkanMatvecWorkgroup.entries.filter { it.nativeId in 0..2 }.forEach { workgroup ->
                            FilterChip(
                                selected = config.vulkanMatvecWorkgroup == workgroup,
                                onClick = {
                                    viewModel.updateConfig(config.copy(vulkanMatvecWorkgroup = workgroup))
                                },
                                label = {
                                    Text(
                                        when (workgroup) {
                                            VulkanMatvecWorkgroup.AUTO -> "AUTO – Upstream"
                                            VulkanMatvecWorkgroup.SUBGROUP -> "SUBGROUP – Geräte-Subgroup (Mali-G57: 16)"
                                            VulkanMatvecWorkgroup.LARGE -> "LARGE – 4× Subgroup"
                                            VulkanMatvecWorkgroup.Q4K_BASE_DMMV -> "Q4_K Basis-DMMV (A/B, intern)"
                                            VulkanMatvecWorkgroup.Q5K_BASE_DMMV -> "Q5_K Basis-DMMV (A/B, intern)"
                                            VulkanMatvecWorkgroup.Q6K_BASE_DMMV -> "Q6_K Basis-DMMV (A/B, intern)"
                                            VulkanMatvecWorkgroup.Q4K_PREFILL_F16 -> "Q4_K Prefill F16/F32 (A/B, intern)"
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .testTag("vulkan_matvec_workgroup_${workgroup.name}")
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = "Gated Delta Net (GDN)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "ARCH_DEFAULT verwendet für Qwen3.5 die unfused Referenzpfade und lässt andere Architekturen bei den Upstream-Defaults. Die übrigen Modi sind für die CPU/Vulkan-Korrektheitsmatrix und werden beim nächsten Modellladen aktiv.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GdnPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = config.gdnPolicy == policy,
                            onClick = { viewModel.updateConfig(config.copy(gdnPolicy = policy)) },
                            label = { Text(policy.label) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .testTag("gdn_policy_${policy.name.lowercase()}")
                        )
                    }

                    val activeModel = currentModel
                    if (activeModel != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Aktives Backend: ${activeModel.activeBackend}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Inferenz- & Kontextparameter
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Dynamische Kontextgröße & Inferenz",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Context Size
                    Text(
                        text = "Kontextfenster (n_ctx): ${config.contextSize} Tokens",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        listOf(2048, 4096, 8192, 16384).forEach { size ->
                            FilterChip(
                                selected = config.contextSize == size,
                                onClick = { viewModel.updateConfig(config.copy(contextSize = size)) },
                                label = { Text("$size") }
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = "Modell nach Prozessabbruch wiederherstellen",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        ReloadAfterAbort.entries.forEach { mode ->
                            FilterChip(
                                selected = config.reloadAfterAbort == mode,
                                onClick = { viewModel.updateConfig(config.copy(reloadAfterAbort = mode)) },
                                label = { Text(if (mode == ReloadAfterAbort.LAZY) "LAZY – beim nächsten Chat" else "EAGER – sofort neu laden") },
                                modifier = Modifier.testTag("reload_after_abort_${mode.name.lowercase()}")
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    // CPU Worker Threads
                    val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                    Text(
                        text = "CPU Worker-Threads: ${config.threads} / $maxThreads Cores",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = config.threads.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(threads = it.roundToInt())) },
                        valueRange = 1f..maxThreads.toFloat(),
                        steps = (maxThreads - 2).coerceAtLeast(0),
                        modifier = Modifier.testTag("threads_slider")
                    )

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    // Temperature
                    Text(
                        text = "Temperatur (Sampling): ${String.format("%.2f", config.temperature)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = config.temperature,
                        onValueChange = { viewModel.updateConfig(config.copy(temperature = it)) },
                        valueRange = 0.0f..1.5f,
                        modifier = Modifier.testTag("temperature_slider")
                    )

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    // Top-P (Nucleus Sampling)
                    Text(
                        text = "Top-P (Nucleus Sampling): ${String.format("%.2f", config.topP)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = config.topP,
                        onValueChange = { viewModel.updateConfig(config.copy(topP = it)) },
                        valueRange = 0.05f..1.0f,
                        modifier = Modifier.testTag("top_p_slider")
                    )

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    // Top-K
                    Text(
                        text = "Top-K (Kandidaten): ${config.topK}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = config.topK.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(topK = it.roundToInt().coerceAtLeast(1))) },
                        valueRange = 1f..64f,
                        steps = 62,
                        modifier = Modifier.testTag("top_k_slider")
                    )

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    // Min-P
                    Text(
                        text = "Min-P (relative Wahrscheinlichkeit): ${String.format("%.2f", config.minP)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = config.minP,
                        onValueChange = { viewModel.updateConfig(config.copy(minP = it)) },
                        valueRange = 0f..0.25f,
                        modifier = Modifier.testTag("min_p_slider")
                    )

                    // Max Predict Tokens
                    Text(
                        text = "Maximale Ausgabelänge: ${config.maxPredictTokens} Tokens",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = config.maxPredictTokens.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(maxPredictTokens = it.roundToInt())) },
                        valueRange = 128f..4096f,
                        steps = 30,
                        modifier = Modifier.testTag("max_tokens_slider")
                    )
                }
            }
        }

        // Reproducible device benchmark: same model, question, system prompt and
        // inference settings; only the selected compute backend changes.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "CPU- / Vulkan-Benchmark",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Vergleicht dasselbe Modell auf Vulkan und CPU mit einer festen Zahlenfolge. " +
                            "Pro Backend läuft nur eine Inferenz: 4 Warm-up-Tokens und anschließend 20 Mess-Tokens. " +
                            "Damit wird das teure Prompt-Prefill nicht doppelt ausgeführt. Der Fortschritt wird live angezeigt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.runDefaultBackendBenchmark() },
                        enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                        modifier = Modifier.fillMaxWidth().testTag("backend_benchmark_button")
                    ) {
                        Text(if (isBenchmarking) "Benchmark läuft…" else "CPU vs. Vulkan testen")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Modellabhängige Messmatrix: Vulkan/CPU, AUTO/SUBGROUP/LARGE plus passender Q4_K-/Q5_K-/Q6_K-A/B-Pfad, n_ubatch 32/64/128 und Flash Attention AUS/AN. Jeder Lauf lädt dasselbe Modell neu; bei kritisch wenig RAM oder hoher Temperatur wird abgebrochen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { viewModel.runFullBenchmarkMatrix() },
                        enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                        modifier = Modifier.fillMaxWidth().testTag("full_benchmark_matrix_button")
                    ) {
                        Text(if (isBenchmarking) "Vollständige Matrix läuft…" else "Vollständige Modellmatrix starten")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.runVulkanLayerSweep() },
                        enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                        modifier = Modifier.fillMaxWidth().testTag("vulkan_layer_sweep_button")
                    ) {
                        Text(if (isBenchmarking) "Vulkan-Layer-Test läuft…" else "Vulkan-GPU-Layer vergleichen")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.runVulkanQ4kDecodePathSweep() },
                        enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                        modifier = Modifier.fillMaxWidth().testTag("vulkan_matvec_workgroup_sweep_button")
                    ) {
                        Text(if (isBenchmarking) "Q4_K-Kerneltest läuft…" else "Q4_K Integer-Dot gegen Basis-DMMV testen")
                    }
                    Text(
                        text = "Vier kontrollierte Läufe im ABBA-Ablauf: Q4_K Integer-Dot-MMVQ, Basis-DMMV ohne Q8_1-Integer-Dot, Basis-DMMV-Wiederholung und Integer-Dot-Wiederholung. Nur Q4_K-Decode wird umgeschaltet; alle anderen Tensor-Typen, Modell-Layer, Threads und Promptparameter bleiben gleich.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Der Workgroup-Test zeigte auf dem Mali-G57 keinen messbaren Vorteil für SUBGROUP gegenüber AUTO; LARGE war langsamer. Dieser Test untersucht deshalb den eigentlichen Q4_K-Rechenpfad. Die ursprünglichen Einstellungen werden danach wiederhergestellt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    benchmarkReport?.let { report ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = report,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "RAM-Verlauf und native Vulkan-Meldungen werden automatisch im Diagnoseprotokoll erfasst. " +
                            "Die Ausgabe des Modells selbst wird nicht protokolliert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // Controlled in-app screening for independent llama.cpp context parameters.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Automatischer Konfigurationstest",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Prüft n_threads (Token-Generierung), n_threads_batch (Prompt-Verarbeitung) " +
                            "und n_batch/n_ubatch getrennt. Das Modell lädt je Profil neu; ein Warmup " +
                            "und eine Messung verwenden denselben langen Testprompt. Temperatur 0 und " +
                            "Top-P 1 reduzieren Sampling-Schwankungen. Deine gespeicherten Einstellungen " +
                            "bleiben unverändert; am Ende wird die ursprüngliche Modellkonfiguration wiederhergestellt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Vor jedem Profil werden verfügbarer RAM und thermischer Status geprüft. " +
                            "Bei zu wenig freiem RAM, hoher Temperatur, Backend-Wechsel oder Messfehler wird abgebrochen. " +
                            "Prompt-Zeit, Time-to-first-token, Decode-Zeit, Tokens/s und geladene Parameter werden protokolliert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.runConfigurationSmokeTest() },
                        enabled = currentModel != null && !isBenchmarking && !isConfigSmokeTesting && !isGenerating && !isImporting,
                        modifier = Modifier.fillMaxWidth().testTag("configuration_smoke_test_button")
                    ) {
                        Text(if (isConfigSmokeTesting) "Konfigurationstest läuft…" else "Konfiguration automatisch testen")
                    }
                    if (isConfigSmokeTesting) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.cancelConfigurationSmokeTest() },
                            modifier = Modifier.fillMaxWidth().testTag("configuration_smoke_test_cancel_button")
                        ) {
                            Text("Test abbrechen")
                        }
                    }
                    configSmokeReport?.let { report ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = report, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Die Testausgabe und Modellantwort werden nicht protokolliert. " +
                            "Die Messwerte erscheinen im Diagnoseexport unter CONFIG_SMOKE_RESULT.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
