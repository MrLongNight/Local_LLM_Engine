package com.example.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.EngineViewModel
import com.mydocanizer.llm.engine.ModelInfo
@Composable
internal fun BackendAcceptanceTestSection(
    currentModel: ModelInfo?,
    isBenchmarking: Boolean,
    isGenerating: Boolean,
    isImporting: Boolean,
    viewModel: EngineViewModel
) {
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
