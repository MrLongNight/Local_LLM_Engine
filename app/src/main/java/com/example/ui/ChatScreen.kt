package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.ChatRole
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.InferenceEvent

@Composable
fun ChatScreen(
    viewModel: EngineViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.chatMessages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingText by viewModel.currentStreamingText.collectAsState()
    val inferenceProgress by viewModel.inferenceProgress.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val engineConfig by viewModel.engineConfig.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val modelLoadProgress by viewModel.modelLoadProgress.collectAsState()

    val listState = rememberLazyListState()

    // Follow the active message without starting a new animation for every token.
    // The transient streaming/progress row exists only while generation is active.
    LaunchedEffect(messages.size, streamingText.length / 80, isGenerating) {
        if (messages.isNotEmpty()) {
            val transientItems = if (isGenerating) {
                if (streamingText.isNotEmpty()) 2 else 1
            } else 0
            val lastItemIndex = (messages.size + transientItems - 1).coerceAtLeast(0)
            listState.animateScrollToItem(lastItemIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("chat_screen")
    ) {
        // Model import, selection and load/unload actions live in one place:
        // the dedicated Modell-Import screen.
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("engine_status_card"),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(10.dp).clip(CircleShape).background(
                                when {
                                    isGenerating -> MaterialTheme.colorScheme.tertiary
                                    currentModel != null -> MaterialTheme.colorScheme.primary
                                    engineState is EngineState.LoadingModel -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isGenerating -> "Inferenz aktiv"
                                engineState is EngineState.LoadingModel -> "Modell wird geladen"
                                currentModel != null -> "Engine bereit"
                                else -> "Kein Modell geladen"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = currentModel?.activeBackend ?: engineConfig.backendPreference.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(currentModel?.name ?: "Kein Modell geladen",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                if (currentModel != null) {
                    Text(
                        text = "${String.format("%.1f MB", currentModel!!.sizeMb)} · n_ctx: ${engineConfig.contextSize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Importiere und lade ein GGUF-Modell im Bereich Modell-Import.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (engineState is EngineState.LoadingModel) {
                    ModelLoadProgressIndicator(
                        progress = modelLoadProgress,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.selectTab(AppTab.IMPORT) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("manage_models_button")
                ) { Text("Modelle verwalten") }
            }
        }

        // Verständliche Fehlermeldung
        if (errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("error_banner")
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Fehler",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.dismissError() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Schließen",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // ==========================================
        // CHAT-NACHRICHTENLISTE
        // ==========================================
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(message = msg)
            }

            if (isGenerating && streamingText.isNotEmpty()) {
                item {
                    ChatBubble(
                        message = ChatMessage.assistant(streamingText),
                        isStreaming = true
                    )
                }
            }

            if (isGenerating) {
                item {
                    InferenceProgressIndicator(
                        progress = inferenceProgress,
                        maxPredictTokens = engineConfig.maxPredictTokens,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // ==========================================
        // EINGABELEISTE & STEUERUNG
        // ==========================================
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { viewModel.updateInputText(it) },
                    placeholder = {
                        Text(
                            if (currentModel != null) "Nachricht eingeben..."
                            else "Zuerst Modell oben laden..."
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Abbrechen-Button während Generierung
                if (isGenerating) {
                    IconButton(
                        onClick = { viewModel.cancelGeneration() },
                        modifier = Modifier
                            .size(46.dp)
                            .testTag("cancel_generation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Generierung abbrechen",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else {
                    // Senden-Button
                    IconButton(
                        onClick = { viewModel.sendMessage() },
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier
                            .size(46.dp)
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Nachricht senden",
                            tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == ChatRole.USER
    val isSystem = message.role == ChatRole.SYSTEM || message.role == ChatRole.DEVELOPER

    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bgColor = when {
        isUser -> MaterialTheme.colorScheme.primary
        isSystem -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            if (!isUser) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = when (message.role) {
                    ChatRole.USER -> "Du"
                    ChatRole.ASSISTANT -> if (isStreaming) "Assistent (Streaming...)" else "Assistent"
                    ChatRole.SYSTEM -> "System-Instruktion"
                    ChatRole.DEVELOPER -> "Developer-Instruktion"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = bgColor,
            tonalElevation = 1.dp,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isStreaming) {
                    Text(
                        text = "●",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun InferenceProgressIndicator(
    progress: InferenceEvent.Progress?,
    maxPredictTokens: Int,
    modifier: Modifier = Modifier
) {
    val maxTokens = maxPredictTokens.coerceAtLeast(1)
    val phase = progress?.phase ?: InferenceEvent.ProgressPhase.PROMPT_PREPARATION
    val title: String
    val detail: String
    val fraction: Float?

    when (phase) {
        InferenceEvent.ProgressPhase.PROMPT_PREPARATION -> {
            val elapsed = progress?.elapsedMs ?: 0L
            title = "Prompt wird vorbereitet und tokenisiert"
            detail = if (elapsed > 0L) "Laufzeit: ${formatProgressDuration(elapsed)} · Tokenzahl wird ermittelt"
                else "Warte auf die ersten Messwerte aus der Engine"
            fraction = null
        }
        InferenceEvent.ProgressPhase.PROMPT_EVALUATION -> {
            val total = progress?.promptTokensTotal ?: 0
            val processed = progress?.promptTokensProcessed ?: 0
            val speed = progress?.tokensPerSecond ?: 0.0
            val eta = progress?.estimatedRemainingMs
            val percent = if (total > 0) (processed * 100 / total).coerceIn(0, 100) else 0
            title = if (total > 0) "Prompt-Auswertung: $processed / $total Tokens ($percent %)"
                else "Prompt-Auswertung läuft – Tokenfortschritt wird ermittelt"
            detail = buildString {
                if (speed > 0.0) append("Gemessen: ${String.format("%.2f", speed)} Prompt-Tokens/s")
                if (eta != null) {
                    if (isNotEmpty()) append(" · ")
                    append("Restzeit geschätzt: ${formatProgressDuration(eta)}")
                }
                if (isNotEmpty()) append(" · ")
                append("Zeit: ${formatProgressDuration(progress?.elapsedMs ?: 0L)}")
            }
            fraction = if (total > 0) processed.toFloat() / total.toFloat() else null
        }
        InferenceEvent.ProgressPhase.WAITING_FOR_FIRST_TOKEN -> {
            title = "Prompt vollständig ausgewertet – warte auf den ersten Antwort-Token"
            detail = "Wartezeit seit Prefill: ${formatProgressDuration(progress?.elapsedMs ?: 0L)} · Bisher 0 Antwort-Tokens"
            fraction = 1f
        }
        InferenceEvent.ProgressPhase.GENERATING -> {
            val generated = progress?.tokensGenerated ?: 0
            val speed = progress?.tokensPerSecond ?: 0.0
            val eta = progress?.estimatedRemainingMs
            title = "Antwort wird erzeugt: $generated Tokens · Ausgabelimit: $maxTokens"
            detail = buildString {
                if (speed > 0.0) append("Gemessen: ${String.format("%.2f", speed)} Tokens/s")
                if (isNotEmpty()) append(" · ")
                append("Laufzeit: ${formatProgressDuration(progress?.elapsedMs ?: 0L)}")
                if (eta != null) {
                    append(" · ca. ${formatProgressDuration(eta)} bis zum Maximal-Limit")
                }
            }
            fraction = (generated.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
        }
    }

    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        if (fraction != null) {
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth()
            )
        }
        val qualifier = if (
            phase == InferenceEvent.ProgressPhase.GENERATING && progress?.estimatedRemainingMs != null
        ) " (Prognose bis zur Obergrenze; die Antwort kann vorher enden.)" else ""
        Text(
            detail + qualifier,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatProgressDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return if (seconds < 60L) "${seconds}s" else "${seconds / 60L}m ${seconds % 60L}s"
}
