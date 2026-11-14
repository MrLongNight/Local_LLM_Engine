package com.example.ui.modelimport

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun FilePickerActions(
    isImporting: Boolean,
    onPickFile: () -> Unit,
    onPickMultipleFiles: () -> Unit,
    onPickFolder: () -> Unit
) {
                    Button(
                        onClick = {
                            onPickFile()
                        },
                        enabled = !isImporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_file_button")
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Öffne Originaldatei...")
                        } else {
                            Icon(imageVector = Icons.Default.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("GGUF-Datei direkt öffnen")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            onPickMultipleFiles()
                        },
                        enabled = !isImporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_multiple_files_button")
                    ) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Mehrere GGUF-Modelle gesammelt importieren")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { onPickFolder() },
                        enabled = !isImporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_gguf_folder_button")
                    ) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Kompletten Modellordner importieren")
                    }
}
