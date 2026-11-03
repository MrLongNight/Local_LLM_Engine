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
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.ModelInfo
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: DocAnizerViewModel,
    modifier: Modifier = Modifier
) {
    val config: EngineConfig by viewModel.engineConfig.collectAsState()
    val systemPrompt: String by viewModel.systemPrompt.collectAsState()
    val currentModel: ModelInfo? by viewModel.currentModel.collectAsState()

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
                        text = "AUTO nutzt ein tatsächlich eingebautes GPU-Backend, sofern llama.cpp eines registriert hat, sonst CPU. Dieser aktuelle Build enthält kein Vulkan-Backend; VULKAN meldet deshalb einen klaren Fehler statt GPU-Nutzung vorzutäuschen.",
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
                            text = "GPU-Layer-Offload: ${config.gpuLayers} Layer (nur bei eingebautem GPU-Backend)",
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
    }
}
