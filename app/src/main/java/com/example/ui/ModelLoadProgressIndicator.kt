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
 * Numeric bars are shown only where tensor/byte progress is measured by the underlying operation.
 */
@Composable
fun ModelLoadProgressIndicator(
    progress: Int?,
    modifier: Modifier = Modifier
) {
    val value = progress?.coerceIn(0, 100)
    val label: String
    val measuredProgress: Float?

    when {
        value == null -> {
            label = "Engine wird initialisiert – noch kein messbarer Ladefortschritt"
            measuredProgress = null
        }
        value <= 70 -> {
            val phasePercent = (value / 70f * 100f).roundToInt()
            label = "Schritt 1/5 · GGUF-Gewichte geladen: $phasePercent %"
            measuredProgress = value / 70f
        }
        value < 80 -> {
            label = "Schritt 2/5 · Modellkontext wird angelegt (keine interne Teilmessung verfügbar)"
            measuredProgress = null
        }
        value == 80 -> {
            label = "Schritt 2/5 abgeschlossen · Modellkontext ist bereit"
            measuredProgress = null
        }
        value < 86 -> {
            label = "Schritt 3/5 · Chat-Template und Sampler werden eingerichtet (keine Teilmessung verfügbar)"
            measuredProgress = null
        }
        value == 86 -> {
            label = "Schritt 3/5 abgeschlossen · Native Engine ist initialisiert"
            measuredProgress = null
        }
        value == 87 -> {
            label = "Schritt 4/5 · Metadaten gelesen – SHA-256-Dateiprüfung startet"
            measuredProgress = 0f
        }
        value < 100 -> {
            val hashPercent = ((value - 87) / 12f * 100f).roundToInt().coerceIn(0, 100)
            label = "Schritt 5/5 · SHA-256-Dateiprüfung: $hashPercent %"
            measuredProgress = (value - 87) / 12f
        }
        else -> {
            label = "Modell vollständig geladen – Kontext, Sampler und Prüfsumme bereit"
            measuredProgress = 1f
        }
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
        if (measuredProgress != null) {
            LinearProgressIndicator(
                progress = measuredProgress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
