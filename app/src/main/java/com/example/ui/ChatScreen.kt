package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineState
import com.mydocanizer.llm.engine.ModelInfo
import java.io.File

@Composable
fun ChatScreen(
    viewModel: DocAnizerViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.chatMessages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingText by viewModel.currentStreamingText.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val engineConfig by viewModel.engineConfig.collectAsState()
    val modelEntries by viewModel.importedModelEntries.collectAsState()
    val selectedModelFile by viewModel.selectedModelFile.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()

    var showModelDropdown by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importModelFromSaf(it, copyToPrivate = true)
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streamingText.length) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            listState.animateScrollToItem(messages.size.coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("chat_screen")
    ) {
        // ==========================================
        // ENGINE-STATUS & MODELL-STEUERUNG
        // ==========================================
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("engine_status_card"),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header-Zeile: Status-Badge, Backend, Kontext & Reset
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
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
                                currentModel != null -> "Idle / Bereit"
                                engineState is EngineState.LoadingModel -> "Lädt Modell..."
                                else -> "Kein Modell geladen"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isGenerating -> MaterialTheme.colorScheme.tertiary
                                currentModel != null -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Backend Badge
                        Text(
                            text = currentModel?.activeBackend ?: engineConfig.backendPreference.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Kontextgröße
                        Text(
                            text = "n_ctx: ${engineConfig.contextSize}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        // Gespräch neu starten Button
                        IconButton(
                            onClick = { viewModel.restartConversation() },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("restart_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Gespräch neu starten",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Modellname & Details
                Text(
                    text = currentModel?.name ?: "Kein Modell geladen",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )

                if (currentModel != null) {
                    val m = currentModel!!
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "${String.format("%.1f MB", m.sizeMb)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (m.parameterCount > 0) {
                            Text(
                                text = "• ${m.formattedParamCount} Params",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (m.contextLengthTrain > 0) {
                            Text(
                                text = "• max ${m.contextLengthTrain} ctx",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val sha = m.sha256
                    if (!sha.isNullOrBlank()) {
                        Text(
                            text = "SHA-256: ${sha.take(16)}...",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 6.dp))

                // Modell-Aktionen: Auswählen, Laden/Entladen, Importieren (SAF)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Modell-Auswahl Dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { showModelDropdown = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .testTag("select_model_dropdown_button")
                        ) {
                            Text(
                                text = selectedModelFile?.name ?: if (modelEntries.isEmpty()) "Kein Modell verfügbar" else "Modell auswählen",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showModelDropdown,
                            onDismissRequest = { showModelDropdown = false }
                        ) {
                            if (modelEntries.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Keine Modelle importiert") },
                                    onClick = { showModelDropdown = false }
                                )
                            } else {
                                modelEntries.forEach { entry ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(entry.name, fontWeight = FontWeight.Medium)
                                                Text(
                                                    "${String.format("%.1f MB", entry.sizeMb)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.selectModelFile(entry.file)
                                            showModelDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Laden / Entladen Button
                    if (currentModel != null) {
                        OutlinedButton(
                            onClick = { viewModel.unloadModel() },
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("unload_button")
                        ) {
                            Text("Entladen", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.loadSelectedModel() },
                            enabled = selectedModelFile != null && engineState !is EngineState.LoadingModel,
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("load_button")
                        ) {
                            if (engineState is EngineState.LoadingModel) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Laden", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Importieren (SAF) Button
                    IconButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !isImporting,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("saf_import_icon_button")
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Modell importieren (SAF)",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
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
            } else if (isGenerating) {
                item {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Modell verarbeitet Eingabe und generiert Tokens...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
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
    val isSystem = message.role == ChatRole.SYSTEM

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
