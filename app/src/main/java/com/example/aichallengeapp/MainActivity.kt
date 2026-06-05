package com.example.aichallengeapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichallengeapp.data.GigaChatClient
import com.example.aichallengeapp.data.MessageObj
import com.example.aichallengeapp.ui.FourApproachesScreen
import com.example.aichallengeapp.ui.theme.AIChallengeAppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

/** Состояние UI экрана чата. */
sealed interface ChatUiState {
    data object Idle : ChatUiState
    data object Loading : ChatUiState
    data class Success(val text: String) : ChatUiState
    data class Error(val message: String) : ChatUiState
}

/** Режим формирования ответа модели. */
enum class ResponseMode(
    val title: String,
) {
    Default("Обычный ответ"),
    Restricted("Ограниченный ответ"),
}

/** Ограничения, которые применяются к запросу только в ограниченном режиме. */
data class ResponseRestrictions(
    val format: String,
    val maxTokens: Int?,
    val stopSequence: String,
)

/** ViewModel отвечает за отправку запроса в GigaChat и хранение результата. */
class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState

    /** Отправляет [prompt] в GigaChat и обновляет [uiState]. */
    fun sendMessage(
        prompt: String,
        responseMode: ResponseMode,
        restrictions: ResponseRestrictions,
        temperature: Double,
    ) {
        if (prompt.isBlank()) return

        _uiState.value = ChatUiState.Loading

        viewModelScope.launch {
            runCatching {
                val messages = buildMessages(
                    prompt = prompt,
                    responseMode = responseMode,
                    restrictions = restrictions,
                )

                GigaChatClient.chat(
                    messages = messages,
                    maxTokens = restrictions.maxTokens.takeIf { responseMode == ResponseMode.Restricted },
                    stopSequences = restrictions.stopSequence.takeIf {
                        responseMode == ResponseMode.Restricted && it.isNotBlank()
                    }
                        ?.let(::listOf),
                    temperature = temperature,
                )
            }.onSuccess { response ->
                val answer = response.choices.firstOrNull()?.message?.content.orEmpty()
                Log.d("GigaChat", "Ответ: $answer")
                _uiState.value = ChatUiState.Success(answer)
            }.onFailure { error ->
                Log.e("GigaChat", "Ошибка запроса", error)
                _uiState.value = ChatUiState.Error(error.message ?: "Неизвестная ошибка")
            }
        }
    }

    /** Собирает сообщения с системными правилами для ограниченного ответа. */
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

    /** Сбрасывает ответ и возвращает экран в начальное состояние. */
    fun resetState() {
        _uiState.value = ChatUiState.Idle
    }
}

// ──────────────────────────────────────────────
// Activity
// ──────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIChallengeAppTheme {
                var showFourApproaches by remember { mutableStateOf(false) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showFourApproaches) {
                        FourApproachesScreen(
                            onBack = { showFourApproaches = false },
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else {
                        ChatScreen(
                            onNavigateToFourApproaches = { showFourApproaches = true },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// UI
// ──────────────────────────────────────────────

@Composable
fun ChatScreen(
    onNavigateToFourApproaches: () -> Unit = {},
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel = viewModel(),
) {
    val uiState by chatViewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var responseMode by remember { mutableStateOf(ResponseMode.Default) }
    var responseFormat by remember { mutableStateOf("Краткий список с маркированными пунктами") }
    var maxTokensText by remember { mutableStateOf("200") }
    var stopSequence by remember { mutableStateOf("###") }
    var temperature by remember { mutableStateOf(0.7) }

    // Внешняя колонка: верхний блок фиксирован, нижний скроллируется
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "GigaChat Mini",
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedButton(onClick = onNavigateToFourApproaches) {
                Text("4 подхода")
            }
        }

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Введите вопрос") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        ResponseModeDropdown(
            selectedMode = responseMode,
            onModeSelected = { responseMode = it },
        )

        TemperatureSelector(
            selected = temperature,
            onSelected = { temperature = it },
        )

        if (responseMode == ResponseMode.Restricted) {
            ResponseRestrictionsFields(
                responseFormat = responseFormat,
                onResponseFormatChange = { responseFormat = it },
                maxTokensText = maxTokensText,
                onMaxTokensChange = { maxTokensText = it.filter(Char::isDigit) },
                stopSequence = stopSequence,
                onStopSequenceChange = { stopSequence = it },
            )
        }

        // Ряд кнопок: «Отправить» справа, «Сбросить» слева
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = {
                    inputText = ""
                    chatViewModel.resetState()
                },
                enabled = uiState !is ChatUiState.Idle,
            ) {
                Text("Сбросить")
            }

            Button(
                onClick = {
                    chatViewModel.sendMessage(
                        prompt = inputText,
                        responseMode = responseMode,
                        restrictions = ResponseRestrictions(
                            format = responseFormat,
                            maxTokens = maxTokensText.toIntOrNull(),
                            stopSequence = stopSequence,
                        ),
                        temperature = temperature,
                    )
                },
                enabled = uiState !is ChatUiState.Loading,
            ) {
                Text("Отправить")
            }
        }

        // Область ответа — занимает оставшееся место и скроллируется независимо
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (val state = uiState) {
                is ChatUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ChatUiState.Success -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Ответ:",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = state.text)
                    }
                }
                is ChatUiState.Error -> {
                    Text(
                        text = "Ошибка: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun TemperatureSelector(
    selected: Double,
    onSelected: (Double) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Температура:",
            style = MaterialTheme.typography.labelMedium,
        )
        OutlinedButton(
            onClick = {
                val newVal = (Math.round((selected - 0.1) * 10) / 10.0).coerceAtLeast(0.1)
                onSelected(newVal)
            },
            enabled = Math.round(selected * 10) > 1,
        ) {
            Text("−")
        }
        Text(
            text = String.format("%.1f", selected),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                val newVal = (Math.round((selected + 0.1) * 10) / 10.0).coerceAtMost(2.0)
                onSelected(newVal)
            },
            enabled = Math.round(selected * 10) < 20,
        ) {
            Text("+")
        }
    }
}

@Composable
private fun ResponseModeDropdown(
    selectedMode: ResponseMode,
    onModeSelected: (ResponseMode) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { isExpanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Режим ответа: ${selectedMode.title}")
        }

        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
        ) {
            ResponseMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.title) },
                    onClick = {
                        onModeSelected(mode)
                        isExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ResponseRestrictionsFields(
    responseFormat: String,
    onResponseFormatChange: (String) -> Unit,
    maxTokensText: String,
    onMaxTokensChange: (String) -> Unit,
    stopSequence: String,
    onStopSequenceChange: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = responseFormat,
            onValueChange = onResponseFormatChange,
            label = { Text("Формат ответа") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedTextField(
            value = maxTokensText,
            onValueChange = onMaxTokensChange,
            label = { Text("Максимальная длина, токены") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            value = stopSequence,
            onValueChange = onStopSequenceChange,
            label = { Text("Stop sequence") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}
