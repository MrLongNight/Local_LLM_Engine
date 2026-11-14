package com.example.ui.settings.sections

import androidx.compose.material3.Divider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.EngineViewModel
import com.mydocanizer.llm.engine.EngineConfig
import kotlin.math.roundToInt

@Composable
internal fun SamplingParametersSection(
    config: EngineConfig,
    viewModel: EngineViewModel
) {
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
