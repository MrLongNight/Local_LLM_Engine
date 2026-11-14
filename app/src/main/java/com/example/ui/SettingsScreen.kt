package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.ModelInfo
import com.example.ui.settings.sections.BackendAcceptanceTestSection
import com.example.ui.settings.sections.BackendSettingsSection
import com.example.ui.settings.sections.BenchmarkResultSection
import com.example.ui.settings.sections.InferenceSettingsSection
import com.example.ui.settings.sections.SystemPromptSection
import com.example.ui.settings.sections.VulkanQ4kDiagnosticsSection

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
            BenchmarkResultSection(
                benchmarkReport = benchmarkReport
            )
        }
    }
}
