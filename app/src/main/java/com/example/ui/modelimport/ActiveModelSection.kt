package com.example.ui.modelimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mydocanizer.llm.engine.ModelInfo

@Composable
internal fun ActiveModelSection(
    currentModel: ModelInfo?,
    onUnload: () -> Unit
) {

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_model_card"),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (currentModel != null) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (currentModel != null) Icons.Default.CheckCircle else Icons.Default.Memory,
                                contentDescription = null,
                                tint = if (currentModel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Aktuell geladenes Modell",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (currentModel != null) {
                            OutlinedButton(
                                onClick = { onUnload() },
                                modifier = Modifier.testTag("unload_model_button")
                            ) {
                                Text("Entladen")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    ActiveModelDetails(currentModel = currentModel)

                }
            }
        
}
