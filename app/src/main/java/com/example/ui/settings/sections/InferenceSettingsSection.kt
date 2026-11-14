package com.example.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.EngineViewModel
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.ReloadAfterAbort
import kotlin.math.roundToInt

@Composable
internal fun InferenceSettingsSection(
    config: EngineConfig,
    viewModel: EngineViewModel
) {
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
            SamplingParametersSection(
                config = config,
                viewModel = viewModel
            )
        }
    }
}
