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
                text = "CPU/Vulkan-Funktionstest",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Der D0-Referenzlauf akzeptiert ausschließlich Qwen3-4B-Instruct-2507 Q4_K_M mit passender Architektur und Parameterzahl. Ein fehlender SHA-256 erlaubt einen Funktionstest, aber keine vergleichbare D0-Baseline. Andere GGUF-Kandidaten können separat im allgemeinen Lauf geprüft werden; sie werden niemals als 4B-Referenz gezählt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { viewModel.runConfigurationSmokeTest() },
                enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                modifier = Modifier.fillMaxWidth().testTag("backend_acceptance_button")
            ) {
                Text(if (isBenchmarking) "CPU/Vulkan-Test läuft…" else "D0: Qwen3-4B-Referenz prüfen")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.runGeneralConfigurationSmokeTest() },
                enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                modifier = Modifier.fillMaxWidth().testTag("backend_general_smoke_button")
            ) {
                Text(if (isBenchmarking) "CPU/Vulkan-Test läuft…" else "Allgemeinen Modellkandidaten prüfen")
            }
        }
    }
}
