package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Shared model-load status for Chat and Model Import.
 *
 * 0..70 = actual llama.cpp weight progress; 72..80 = context setup;
 * 81..86 = template/sampler setup; 87..99 = byte-counted SHA-256; 100 = ready.
 * Values between phases are stage markers, not fabricated wall-clock percentages.
 */
@Composable
fun ModelLoadProgressIndicator(
    progress: Int?,
    modifier: Modifier = Modifier
) {
    val value = progress?.coerceIn(0, 100)
    val (label, phaseProgress) = when {
        value == null -> "Engine wird vorbereitet – noch keine messbare Ladephase" to 0f
        value <= 70 -> {
            val phasePercent = (value / 70f * 100f).roundToInt()
            "Schritt 1/5 · GGUF-Gewichte: $phasePercent % der Gewichtsphase" to (value / 70f)
        }
        value <= 72 -> "Schritt 2/5 · Gewichte geladen – Modellkontext wird initialisiert" to 0f
        value <= 80 -> {
            val phasePercent = ((value - 72) / 8f * 100f).roundToInt()
            "Schritt 2/5 · Modellkontext wird initialisiert: $phasePercent %" to ((value - 72) / 8f)
        }
        value <= 81 -> "Schritt 3/5 · Kontext bereit – Chat-Template und Sampler werden vorbereitet" to 0f
        value <= 86 -> {
            val phasePercent = ((value - 81) / 5f * 100f).roundToInt()
            "Schritt 3/5 · Chat-Template und Sampler: $phasePercent %" to ((value - 81) / 5f)
        }
        value == 87 -> "Schritt 4/5 · Metadaten gelesen – SHA-256-Prüfung startet" to 0f
        value < 100 -> {
            val phasePercent = ((value - 87) / 12f * 100f).roundToInt().coerceIn(0, 100)
            "Schritt 5/5 · SHA-256-Dateiprüfung: $phasePercent %" to ((value - 87) / 12f)
        }
        else -> "Modell vollständig geladen – Kontext, Sampler und Prüfsumme bereit" to 1f
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        LinearProgressIndicator(
            progress = phaseProgress.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
