package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mydocanizer.llm.engine.ChatMessage
import com.mydocanizer.llm.engine.EngineConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import com.mydocanizer.llm.engine.InferenceEvent
import com.mydocanizer.llm.engine.InferenceRequest
import com.mydocanizer.llm.engine.LocalLlmEngine
import com.mydocanizer.llm.engine.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chat / Conversation state and actions shared by the existing EngineViewModel.
 *
 * Engine configuration, model selection, benchmark lock and app-wide status/error
 * flows remain owned by EngineViewModel and are exposed here as abstract dependencies.
 * This preserves the single ViewModel instance currently shared by all Compose screens.
 */
abstract class ChatViewModel(
    application: Application,
    val engine: LocalLlmEngine
) : AndroidViewModel(application) {

    abstract val currentModel: StateFlow<ModelInfo?>
    abstract val engineConfig: StateFlow<EngineConfig>
    abstract val systemPrompt: StateFlow<String>
    abstract val isBenchmarking: StateFlow<Boolean>

    protected abstract val _engineConfig: MutableStateFlow<EngineConfig>
    protected abstract val _systemPrompt: MutableStateFlow<String>
    protected abstract val _isBenchmarking: MutableStateFlow<Boolean>
    protected abstract val _statusMessage: MutableStateFlow<String?>
    protected abstract val _errorMessage: MutableStateFlow<String?>

    companion object {
        private const val TAG = "EngineViewModel"
    }

    // UI welcome copy is rendered by ChatScreen while this list is empty.
    // It must never be sent to the model as a synthetic assistant turn.
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    protected val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentStreamingText = MutableStateFlow("")
    val currentStreamingText: StateFlow<String> = _currentStreamingText.asStateFlow()

    private val _inferenceProgress = MutableStateFlow<InferenceEvent.Progress?>(null)
    val inferenceProgress: StateFlow<InferenceEvent.Progress?> = _inferenceProgress.asStateFlow()

    private var inferenceJob: Job? = null
    private var generationSequence: Long = 0L
    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun restartConversation() {
        // Invalidate queued events before cancelling the coroutine. A late native
        // completion from the old conversation must never repopulate the reset chat.
        generationSequence++
        engine.cancelInference()
        inferenceJob?.cancel()
        inferenceJob = null

        _chatMessages.value = emptyList()
        _currentStreamingText.value = ""
        _inferenceProgress.value = null
        _inputText.value = ""
        _isGenerating.value = false
        _statusMessage.value = "Gespräch wurde zurückgesetzt"
    }

    fun sendMessage() {
        if (_isBenchmarking.value) {
            _errorMessage.value = "Bitte den laufenden Benchmark erst abschließen."
            return
        }
        val prompt = _inputText.value.trim()
        if (prompt.isEmpty() || _isGenerating.value) return

        if (currentModel.value == null) {
            _errorMessage.value = "Kein Modell geladen! Bitte wähle oder importiere zuerst eine GGUF-Modelldatei."
            return
        }

        val userMsg = ChatMessage.user(prompt)
        val currentList = _chatMessages.value.toMutableList().apply { add(userMsg) }
        _chatMessages.value = currentList
        _inputText.value = ""
        _isGenerating.value = true
        _currentStreamingText.value = ""
        _inferenceProgress.value = null
        _errorMessage.value = null

        val messagesWithSystem = mutableListOf<ChatMessage>()
        if (_systemPrompt.value.isNotBlank()) {
            messagesWithSystem.add(ChatMessage.system(_systemPrompt.value))
        }
        messagesWithSystem.addAll(currentList)

        val request = InferenceRequest(
            messages = messagesWithSystem,
            maxPredictTokens = _engineConfig.value.maxPredictTokens,
            temperature = _engineConfig.value.temperature,
            topP = _engineConfig.value.topP
        )

        val thisGeneration = ++generationSequence
        inferenceJob = viewModelScope.launch {
            val streamBuilder = StringBuilder()
            try {
                engine.streamChat(request).collect { event ->
                    if (thisGeneration != generationSequence) return@collect
                    when (event) {
                    is InferenceEvent.Started -> {
                        Log.d(TAG, "Inference started with ${event.promptMessageCount} messages")
                    }
                    is InferenceEvent.Token -> {
                        streamBuilder.append(event.token)
                        _currentStreamingText.value = streamBuilder.toString()
                    }
                    is InferenceEvent.Progress -> {
                        _inferenceProgress.value = event
                    }
                    is InferenceEvent.Completed -> {
                        val assistantMsg = ChatMessage.assistant(event.result.text)
                        _chatMessages.value = _chatMessages.value + assistantMsg
                        _currentStreamingText.value = ""
                        _inferenceProgress.value = null
                        _isGenerating.value = false
                        Log.i(TAG, "Completed: ${event.result.tokensGenerated} tokens @ ${event.result.tokensPerSecond} t/s")
                    }
                    is InferenceEvent.Cancelled -> {
                        val partialText = event.partialText.ifBlank { streamBuilder.toString() }
                        val partial = if (partialText.isNotBlank()) {
                            partialText + " [Abgebrochen]"
                        } else {
                            "[Generierung abgebrochen]"
                        }
                        _chatMessages.value = _chatMessages.value + ChatMessage.assistant(partial)
                        _currentStreamingText.value = ""
                        _inferenceProgress.value = null
                        _isGenerating.value = false
                    }
                    is InferenceEvent.Error -> {
                        // Keep generated text in the conversation; the streaming bubble is transient.
                        val partialText = event.partialText.ifBlank { streamBuilder.toString() }
                        if (partialText.isNotBlank()) {
                            _chatMessages.value = _chatMessages.value + ChatMessage.assistant(
                                "$partialText\n\n[Teilantwort – Inferenzfehler]"
                            )
                        }
                        val reason = event.throwable.message ?: event.throwable.javaClass.simpleName
                        EngineDiagnostics.error("chat", "VIEWMODEL_RECEIVED_INFERENCE_ERROR", event.throwable)
                        _errorMessage.value = "Inferenz-Fehler: $reason"
                        _currentStreamingText.value = ""
                        _inferenceProgress.value = null
                        _isGenerating.value = false
                    }
                }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (thisGeneration == generationSequence) {
                    Log.e(TAG, "Chat inference failed", error)
                    _errorMessage.value = "Inferenz-Fehler: ${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                if (thisGeneration == generationSequence) {
                    _isGenerating.value = false
                    _currentStreamingText.value = ""
                    _inferenceProgress.value = null
                    inferenceJob = null
                }
            }
        }
    }

    fun cancelGeneration() {
        // Keep the UI in its generating state until the native engine confirms cancellation.
        engine.cancelInference()
        _statusMessage.value = "Abbruch angefordert..."
    }

}
