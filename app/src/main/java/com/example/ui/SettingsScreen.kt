package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.ModelInfo
import com.example.ui.settings.sections.BackendSettingsSection
import com.example.ui.settings.sections.SystemPromptSection

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
            SystemPromptSection(
                systemPrompt = systemPrompt,
                viewModel = viewModel
            )
        }

        // Hardware Acceleration / Backend Selection Card
        item {
            BackendSettingsSection(
                config = config,
                currentModel = currentModel,
                viewModel = viewModel
            )
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

        // Two-stage acceptance flow: one combined CPU/Vulkan gate, then an optional targeted Q4_K diagnosis.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "1. Engine-Abnahmetest: Korrektheit + Leistung",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Ein zusammengefasster Lauf mit genau zwei Profilen: CPU-Referenz zuerst, danach Vulkan. " +
                            "Beide verwenden dasselbe Modell und dieselben Inferenzparameter. Vor der Geschwindigkeitsmessung " +
                            "muss Vulkan die 16-Token-Korrektheitsprüfung bestehen: identische Top-1-Tokens und mindestens " +
                            "4/5 Top-5-Überschneidung. Nur bestandene Profile erhalten einen Geschwindigkeitswert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.runBackendAcceptanceTest() },
                        enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                        modifier = Modifier.fillMaxWidth().testTag("backend_acceptance_button")
                    ) {
                        Text(if (isBenchmarking) "Abnahmetest läuft…" else "CPU + Vulkan gemeinsam prüfen")
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "2. Gezielte Vulkan-Diagnose (nur bei Bedarf)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Nur starten, wenn der erste Abnahmetest Vulkan deutlich langsamer als CPU zeigt " +
                            "und das Modell Q4_K verwendet. Vier ABBA-Läufe vergleichen ausschließlich den Q4_K-Decodepfad " +
                            "(Integer-Dot-MMVQ gegen Basis-DMMV) und prüfen die tatsächlich gemeldete GPU-Layerzahl. " +
                            "Keine allgemeine Parameter- oder Layer-Matrix.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.runVulkanQ4kDecodePathSweep() },
                        enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                        modifier = Modifier.fillMaxWidth().testTag("vulkan_q4k_diagnostic_button")
                    ) {
                        Text(if (isBenchmarking) "Vulkan-Diagnose läuft…" else "Q4_K-Kernel gezielt vergleichen")
                    }
                }
            }
        }
        item {
            benchmarkReport?.let { report ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Testergebnis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = report, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                text = "RAM-Verlauf, tatsächlicher Backend-/Layer-Status und native Vulkan-Meldungen werden " +
                    "automatisch im Diagnoseprotokoll erfasst. Die Modellantwort selbst wird nicht protokolliert.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
