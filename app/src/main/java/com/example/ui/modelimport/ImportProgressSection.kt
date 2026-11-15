package com.example.ui.modelimport

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.ModelLoadProgressIndicator

@Composable
internal fun ImportProgressSection(
    isImporting: Boolean,
    progress: Int?
) {
                    if (isImporting) {
                        ModelLoadProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
}
