package com.example.aichallengeapp.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichallengeapp.data.GigaChatRepository
import com.example.aichallengeapp.data.MessageObj
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChatUiState {
    data object Idle : ChatUiState
    data object Loading : ChatUiState
    data class Success(
        val text: String,
        val elapsedMs: Long = 0,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
        val costRub: Double = 0.0,
    ) : ChatUiState

    data class Error(val message: String) : ChatUiState
}

enum class GigaChatModel(val id: String, val title: String, val costPer1kTokens: Double) {
    Base("GigaChat", "GigaChat (слабая)", 0.065),
    Pro("GigaChat-Pro", "GigaChat-Pro (средняя)", 0.5),
    Max("GigaChat-Max", "GigaChat-Max (сильная)", 0.65),
    Base2("GigaChat-2", "GigaChat-2 (слабая, v2)", 0.065),
    Pro2("GigaChat-2-Pro", "GigaChat-2-Pro (средняя, v2)", 0.5),
    Max2("GigaChat-2-Max", "GigaChat-2-Max (сильная, v2)", 0.65),
}

enum class ResponseMode(val title: String) {
    Default("Обычный ответ"),
    Restricted("Ограниченный ответ"),
}

data class ResponseRestrictions(
    val format: String,
    val maxTokens: Int?,
    val stopSequence: String,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: GigaChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState

    fun sendMessage(
        prompt: String,
        responseMode: ResponseMode,
        restrictions: ResponseRestrictions,
        temperature: Double,
        model: GigaChatModel,
    ) {
        if (prompt.isBlank()) return
        _uiState.value = ChatUiState.Loading

        viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            runCatching {
                repository.chat(
                    messages = buildMessages(prompt, responseMode, restrictions),
                    maxTokens = restrictions.maxTokens.takeIf { responseMode == ResponseMode.Restricted },
                    stopSequences = restrictions.stopSequence.takeIf {
                        responseMode == ResponseMode.Restricted && it.isNotBlank()
                    }?.let(::listOf),
                    temperature = temperature,
                    model = model.id,
                )
            }.onSuccess { response ->
                val elapsed = System.currentTimeMillis() - startMs
                val answer = response.choices.firstOrNull()?.message?.content.orEmpty()
                val usage = response.usage
                val totalTokens = usage?.totalTokens ?: 0
                val costRub = totalTokens / 1000.0 * model.costPer1kTokens
                Log.d("GigaChat", "Ответ за ${elapsed}мс, токены: $usage, стоимость: $costRub ₽")
                _uiState.value = ChatUiState.Success(
                    text = answer,
                    elapsedMs = elapsed,
                    promptTokens = usage?.promptTokens ?: 0,
                    completionTokens = usage?.completionTokens ?: 0,
                    totalTokens = totalTokens,
                    costRub = costRub,
                )
            }.onFailure { error ->
                Log.e("GigaChat", "Ошибка запроса", error)
                _uiState.value = ChatUiState.Error(error.message ?: "Неизвестная ошибка")
            }
        }
    }

    private fun buildMessages(
        prompt: String,
        responseMode: ResponseMode,
        restrictions: ResponseRestrictions,
    ): List<MessageObj> {
        if (responseMode == ResponseMode.Default) {
            return listOf(MessageObj(role = "user", content = prompt))
        }

        val systemPrompt = buildString {
            appendLine("Соблюдай ограничения ответа.")
            appendLine("Формат ответа: ${restrictions.format}.")
            restrictions.maxTokens?.let { appendLine("Максимальная длина ответа: $it токенов.") }
            if (restrictions.stopSequence.isNotBlank()) {
                appendLine("Заверши ответ перед последовательностью: ${restrictions.stopSequence}.")
            }
        }

        return listOf(
            MessageObj(role = "system", content = systemPrompt),
            MessageObj(role = "user", content = prompt),
        )
    }

    fun resetState() {
        _uiState.value = ChatUiState.Idle
    }
}
