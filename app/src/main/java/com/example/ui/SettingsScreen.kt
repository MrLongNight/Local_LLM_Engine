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
            BackendAcceptanceTestSection(
                currentModel = currentModel,
                isBenchmarking = isBenchmarking,
                isGenerating = isGenerating,
                isImporting = isImporting,
                viewModel = viewModel
            )
        }
        item {
            VulkanQ4kDiagnosticsSection(
                currentModel = currentModel,
                isBenchmarking = isBenchmarking,
                isGenerating = isGenerating,
                isImporting = isImporting,
                viewModel = viewModel
            )
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
