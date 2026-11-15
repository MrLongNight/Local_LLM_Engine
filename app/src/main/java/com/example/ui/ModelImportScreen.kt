package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.modelimport.ActiveModelSection
import com.example.ui.modelimport.FilePickerSection
import com.example.ui.modelimport.ImportErrorBanner
import com.example.ui.modelimport.importedModelsSection
import com.mydocanizer.llm.engine.ModelInfo

@Composable
fun ModelImportScreen(
    viewModel: EngineViewModel,
    modifier: Modifier = Modifier
) {
    val currentModel: ModelInfo? by viewModel.currentModel.collectAsState()
    val modelEntries: List<ImportedModelEntry> by viewModel.importedModelEntries.collectAsState()
    val isImporting: Boolean by viewModel.isImporting.collectAsState()
    val modelLoadProgress: Int? by viewModel.modelLoadProgress.collectAsState()
    val errorMessage: String? by viewModel.errorMessage.collectAsState()
    val memoryOverrideAvailable: Boolean by viewModel.memoryOverrideAvailable.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModelFromSaf(it) }
    }

    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.importModelsFromSaf(uris)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { viewModel.importModelsFromTree(it) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("model_import_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (errorMessage != null) {
            item {
                ImportErrorBanner(
                    errorMessage = errorMessage,
                    memoryOverrideAvailable = memoryOverrideAvailable,
                    isImporting = isImporting,
                    onLoadDespiteMemoryWarning = { viewModel.loadModelDespiteMemoryWarning() },
                    onDismiss = { viewModel.dismissError() }
                )
            }
        }

        item {
            ActiveModelSection(
                currentModel = currentModel,
                onUnload = { viewModel.unloadModel() }
            )
        }

        item {
            FilePickerSection(
                isImporting = isImporting,
                modelLoadProgress = modelLoadProgress,
                onPickFile = {
                    filePickerLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "application/x-binary"))
                },
                onPickMultipleFiles = {
                    multiFilePickerLauncher.launch(arrayOf("application/octet-stream", "application/x-gguf", "application/x-binary"))
                },
                onPickFolder = { folderPickerLauncher.launch(null) }
            )
        }

        importedModelsSection(
            modelEntries = modelEntries,
            currentModel = currentModel,
            isImporting = isImporting,
            onRefresh = { viewModel.refreshModelFiles() },
            onUnload = { viewModel.unloadModel() },
            onLoadModel = { entry -> viewModel.loadModelFile(entry.file) }
        )
    }
}
