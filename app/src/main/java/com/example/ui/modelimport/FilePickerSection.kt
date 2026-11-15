package com.example.ui.modelimport

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun FilePickerSection(
    isImporting: Boolean,
    modelLoadProgress: Int?,
    onPickFile: () -> Unit,
    onPickMultipleFiles: () -> Unit,
    onPickFolder: () -> Unit
) {

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GGUF-Datei direkt öffnen (SAF)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Wähle eine lokale .gguf Modelldatei über das sichere Android Storage Access Framework (SAF). Keine Internetverbindung und keine Webkataloge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Column {
                        Text(
                            text = "Direkter Zugriff auf Originaldatei",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Die Engine öffnet die ausgewählte Originaldatei direkt über einen Android-Dateideskriptor im isolierten Inferenzprozess. Es wird keine zweite GGUF-Datei angelegt oder umbenannt; die Originaldatei bleibt am gewählten Speicherort.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ImportProgressSection(isImporting = isImporting, progress = modelLoadProgress)

                    FilePickerActions(
                        isImporting = isImporting,
                        onPickFile = onPickFile,
                        onPickMultipleFiles = onPickMultipleFiles,
                        onPickFolder = onPickFolder
                    )

                }
            }
        
}
