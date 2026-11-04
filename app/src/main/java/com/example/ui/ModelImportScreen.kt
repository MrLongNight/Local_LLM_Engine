package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydocanizer.llm.engine.BackendInfo
import com.mydocanizer.llm.engine.ModelInfo
import java.io.File

@Composable
fun ModelImportScreen(
    viewModel: DocAnizerViewModel,
    modifier: Modifier = Modifier
) {
    val currentModel: ModelInfo? by viewModel.currentModel.collectAsState()
    val modelEntries: List<ImportedModelEntry> by viewModel.importedModelEntries.collectAsState()
    val selectedModelFile: File? by viewModel.selectedModelFile.collectAsState()
    val backendInfo: BackendInfo by viewModel.backendInfo.collectAsState()
    val isImporting: Boolean by viewModel.isImporting.collectAsState()
    val modelLoadProgress: Int? by viewModel.modelLoadProgress.collectAsState()
    val errorMessage: String? by viewModel.errorMessage.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModelFromSaf(it) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("model_import_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Error Banner
        if (errorMessage != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Fehler",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fehler",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }

        // Active Loaded Model Card
        item {
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
                                onClick = { viewModel.unloadModel() },
                                modifier = Modifier.testTag("unload_model_button")
                            ) {
                                Text("Entladen")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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
            }
        }

        // Storage Access Framework (SAF) Import Section
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GGUF-Modell importieren (SAF)",
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
                            text = "Sichere private Modellablage",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Die Engine kopiert das Modell beim Import in den privaten App-Speicher, prüft die GGUF-Signatur und vergibt einen eindeutigen SHA-256-Dateinamen. Die Originaldatei bleibt unverändert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isImporting) {
                        ModelLoadProgressIndicator(
                            progress = modelLoadProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
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
                            Text("Importiere & berechne SHA-256...")
                        } else {
                            Icon(imageVector = Icons.Default.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("GGUF-Datei über SAF auswählen")
                        }
                    }
                }
            }
        }

        // Imported Models List Header
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Lokale App-Modelle (${modelEntries.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { viewModel.refreshModelFiles() }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Aktualisieren")
                }
            }
        }

        if (modelEntries.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Noch keine Modelldateien vorhanden",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Wähle über die SAF-Schaltfläche oben eine .gguf Datei aus.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            items(modelEntries) { entry ->
                val isCurrentlyLoaded = currentModel?.path == entry.file.absolutePath
                val isSelected = selectedModelFile?.absolutePath == entry.file.absolutePath

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrentlyLoaded) MaterialTheme.colorScheme.secondaryContainer
                        else if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (isCurrentlyLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${String.format("%.2f MB", entry.sizeMb)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row {
                                if (isCurrentlyLoaded) {
                                    OutlinedButton(
                                        onClick = { viewModel.unloadModel() },
                                        modifier = Modifier.padding(start = 4.dp)
                                    ) {
                                        Text("Entladen")
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            viewModel.selectModelFile(entry.file)
                                            viewModel.loadSelectedModel()
                                        },
                                        enabled = !isImporting,
                                        modifier = Modifier.padding(start = 4.dp)
                                    ) {
                                        Text("Laden")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
