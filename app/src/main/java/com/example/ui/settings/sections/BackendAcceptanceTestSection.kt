package com.example.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.EngineViewModel
import com.example.ui.diagnostics.ConfigurationSmokeProgress
import com.example.ui.diagnostics.ConfigurationSmokeRunStatus
import com.mydocanizer.llm.engine.ModelInfo

internal fun backendAcceptanceBlockReason(
    hasCurrentModel: Boolean,
    isBenchmarking: Boolean,
    isConfigSmokeTesting: Boolean,
    isGenerating: Boolean,
    isImporting: Boolean
): String? = when {
    isConfigSmokeTesting -> "Der D0-Test läuft bereits."
    isBenchmarking -> "Ein anderer Benchmark läuft noch. D0 startet danach."
    isGenerating -> "Eine Inferenz läuft noch. D0 startet nach deren Abschluss."
    isImporting -> "Ein Modellimport läuft noch. D0 startet nach dessen Abschluss."
    !hasCurrentModel -> "Zuerst ein GGUF-Modell laden."
    else -> null
}

@Composable
internal fun BackendAcceptanceTestSection(
    currentModel: ModelInfo?,
    isBenchmarking: Boolean,
    isConfigSmokeTesting: Boolean,
    configurationSmokeProgress: ConfigurationSmokeProgress?,
    isGenerating: Boolean,
    isImporting: Boolean,
    viewModel: EngineViewModel
) {
    val blockReason = backendAcceptanceBlockReason(
        hasCurrentModel = currentModel != null,
        isBenchmarking = isBenchmarking,
        isConfigSmokeTesting = isConfigSmokeTesting,
        isGenerating = isGenerating,
        isImporting = isImporting
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "D0-CPU-Referenz und Backendvergleich",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "D0 führt drei CPU-only-Läufe mit dem aktuell geladenen GGUF aus (GPU-Layer=0, Vulkan wird nicht gestartet). Name, Architektur, Parameterzahl und Quantisierung des Modells sind keine Startbedingung. Eine gültige SHA-256 sichert nur dieselben Modellbytes über alle drei Reloads; das Modell vorher in die private Modellbibliothek importieren. Der zweite Test ist ein separater CPU/Vulkan-Kandidatenvergleich.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { viewModel.runConfigurationSmokeTest() },
                enabled = blockReason == null,
                modifier = Modifier.fillMaxWidth().testTag("backend_acceptance_button")
            ) {
                Text(
                    when {
                        isConfigSmokeTesting -> "D0-Test läuft…"
                        blockReason != null -> "D0 derzeit nicht startbar"
                        else -> "D0: drei CPU-only-Referenzläufe starten"
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.runGeneralConfigurationSmokeTest() },
                enabled = blockReason == null,
                modifier = Modifier.fillMaxWidth().testTag("backend_general_smoke_button")
            ) {
                Text(
                    when {
                        isConfigSmokeTesting -> "D0-Test läuft…"
                        blockReason != null -> "Vergleich derzeit nicht startbar"
                        else -> "Separaten CPU/Vulkan-Kandidatenvergleich starten"
                    }
                )
            }
            if (blockReason != null && !isConfigSmokeTesting) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    blockReason,
                    modifier = Modifier.testTag("backend_acceptance_block_reason"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            configurationSmokeProgress?.let { progress ->
                Spacer(modifier = Modifier.height(14.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = when (progress.status) {
                                    ConfigurationSmokeRunStatus.RUNNING -> "TEST LÄUFT"
                                    ConfigurationSmokeRunStatus.PASSED -> "TEST BESTANDEN"
                                    ConfigurationSmokeRunStatus.FAILED -> "TEST FEHLGESCHLAGEN"
                                    ConfigurationSmokeRunStatus.CANCELLED -> "TEST ABGEBROCHEN"
                                    ConfigurationSmokeRunStatus.REJECTED -> "TEST NICHT GESTARTET"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (progress.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(progress.message, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Schritt ${progress.currentStep}/${progress.totalSteps}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = progress.stepProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        progress.profileLabel?.let { profile ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Backend: $profile", style = MaterialTheme.typography.bodySmall)
                        }
                        progress.promptProgress?.let {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Prompt: ${progress.promptTokensProcessed}/${progress.promptTokensTotal} Tokens",
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = it,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        progress.generatedTokens?.let { generated ->
                            val limit = progress.tokenLimit
                            if (limit != null && limit > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Ausgabe: $generated/$limit Tokens" +
                                        (progress.tokensPerSecond?.let {
                                            " · " + String.format(java.util.Locale.GERMANY, "%.2f", it) + " Token/s"
                                        } ?: ""),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                progress.tokenProgress?.let { fraction ->
                                    LinearProgressIndicator(
                                        progress = fraction,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
