package com.example.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.EngineViewModel
import com.mydocanizer.llm.engine.BackendPreference
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.ModelInfo
import kotlin.math.roundToInt
@Composable
internal fun BackendSettingsSection(
    config: EngineConfig,
    currentModel: ModelInfo?,
    viewModel: EngineViewModel
) {
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
            VulkanTuningSection(
                config = config,
                currentModel = currentModel,
                viewModel = viewModel
            )
        }
    }
}
