package com.example.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
internal fun VulkanQ4kDiagnosticsSection(
    currentModel: ModelInfo?,
    isBenchmarking: Boolean,
    isGenerating: Boolean,
    isImporting: Boolean,
    viewModel: EngineViewModel
) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "2. Gezielte Vulkan-Diagnose (nur bei Bedarf)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Nur starten, wenn der erste Abnahmetest Vulkan deutlich langsamer als CPU zeigt " +
                                "und das Modell Q4_K verwendet. Vier ABBA-Läufe vergleichen ausschließlich den Q4_K-Decodepfad " +
                                "(Integer-Dot-MMVQ gegen Basis-DMMV) und prüfen die tatsächlich gemeldete GPU-Layerzahl. " +
                                "Keine allgemeine Parameter- oder Layer-Matrix.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.runVulkanQ4kDecodePathSweep() },
                            enabled = currentModel != null && !isBenchmarking && !isGenerating && !isImporting,
                            modifier = Modifier.fillMaxWidth().testTag("vulkan_q4k_diagnostic_button")
                        ) {
                            Text(if (isBenchmarking) "Vulkan-Diagnose läuft…" else "Q4_K-Kernel gezielt vergleichen")
                        }
                    }
                }
}
