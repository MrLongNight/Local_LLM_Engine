package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The same progress display is used from Chat and Model Import.
 * 100% is only reported after the engine has completed its full load lifecycle.
 */
@Composable
fun ModelLoadProgressIndicator(
    progress: Int?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        val value = progress?.coerceIn(0, 100)
        val label = when {
            value == null -> "Modell wird vorbereitet / Engine wird initialisiert…"
            value >= 99 -> "Modellgewichte geladen – Kontext, Sampler und Prüfsumme werden finalisiert…"
            else -> "GGUF-Modellgewichte werden geladen: $value %"
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        if (value == null || value >= 99) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = value / 100f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
