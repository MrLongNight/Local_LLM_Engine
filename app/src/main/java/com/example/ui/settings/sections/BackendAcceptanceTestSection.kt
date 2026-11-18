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
                text = "Kurzer CPU/Vulkan-Funktionstest",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Für die wiederholbare Geräte-Baseline Qwen3-4B-Instruct-2507 Q4_K_M laden. Der Test führt denselben festen Rechen-/Sequenzprompt einmal auf CPU und einmal auf Vulkan aus; nur Backend und GPU-Layer-Offload unterscheiden sich. " +
                    "Er prüft exakte Ausgabe und Finish-Grund und protokolliert je Backend Modellidentität, Konfiguration, echte Graph-Verteilung, Lade-/Prefill-/TTFT-/Decode-Zeiten und RAM. Keine Parameter-Sweeps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { viewModel.runConfigurationSmokeTest() },
                enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                modifier = Modifier.fillMaxWidth().testTag("backend_acceptance_button")
            ) {
                Text(if (isBenchmarking) "CPU/Vulkan-Test läuft…" else "CPU + Vulkan prüfen")
            }
        }
    }
}
