package com.example.ui.modelimport

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydocanizer.llm.engine.ModelInfo

@Composable
internal fun ActiveModelDetails(currentModel: ModelInfo?) {
                    val activeModel = currentModel
                    if (activeModel != null) {
                        Text(
                            text = activeModel.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Dateigröße: ${String.format("%.2f MB", activeModel.sizeMb)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (activeModel.parameterCount > 0) {
                            Text(
                                text = "Parameter: ${activeModel.formattedParamCount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (activeModel.contextLengthTrain > 0) {
                            Text(
                                text = "Trainierter Kontext: ${activeModel.contextLengthTrain} Tokens",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = "Architektur: ${activeModel.architecture}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Tatsächlich aktives Backend: ${activeModel.activeBackend}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val sha = activeModel.sha256
                        if (!sha.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "SHA-256 Prüfsumme:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = sha,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "Kein Modell im Speicher. Wähle eine GGUF-Datei aus dem Speicher oder importiere eine neue Datei über SAF.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
}
