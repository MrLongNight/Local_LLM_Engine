package com.example.ui.modelimport

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import com.example.ui.ImportedModelEntry
import com.mydocanizer.llm.engine.ModelInfo

internal fun LazyListScope.importedModelsSection(
    modelEntries: List<ImportedModelEntry>,
    currentModel: ModelInfo?,
    isImporting: Boolean,
    onRefresh: () -> Unit,
    onUnload: () -> Unit,
    onLoadModel: (ImportedModelEntry) -> Unit
) {
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
                IconButton(onClick = { onRefresh() }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Aktualisieren")
                }
            }
        }

        if (modelEntries.isEmpty()) {
            item {
                ImportedModelEmptyState()
            }
        } else {
            items(modelEntries) { entry ->
                val isCurrentlyLoaded = currentModel?.path == entry.file.absolutePath
                ImportedModelCard(
                    entry = entry,
                    isCurrentlyLoaded = isCurrentlyLoaded,
                    isImporting = isImporting,
                    onUnload = onUnload,
                    onLoad = { onLoadModel(entry) }
                )
            }
        }
}
