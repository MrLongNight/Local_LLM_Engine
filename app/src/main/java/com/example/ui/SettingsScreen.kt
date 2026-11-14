package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.mydocanizer.llm.engine.ReloadAfterAbort
import kotlin.math.roundToInt
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
            InferenceSettingsSection(
                config = config,
                viewModel = viewModel
            )
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
