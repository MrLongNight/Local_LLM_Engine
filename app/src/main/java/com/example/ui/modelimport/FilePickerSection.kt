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
                        text = "GGUF-Modelle importieren (SAF)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Wähle eine einzelne GGUF-Datei, mehrere Dateien oder einen ganzen Ordner. Alle Auswahlarten verwenden denselben Import in die private Modellbibliothek.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Column {
                        Text(
                            text = "Einheitlicher Modellimport",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Einzel- und Mehrfachauswahl werden identisch verarbeitet: GGUF-Prüfung, SHA-256-Ermittlung, inhaltsbasierte Ablage und Anzeige in der Modellliste. Das Original bleibt unverändert am Quellort.",
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
