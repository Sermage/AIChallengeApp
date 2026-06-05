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

/**
 * Доступные модели GigaChat.
 * costPer1kTokens — стоимость в рублях за 1000 токенов (вход и выход одинаковы).
 * Тарифы для физлиц: developers.sber.ru/docs/ru/gigachat/tariffs/individual-tariffs
 */
enum class GigaChatModel(val id: String, val title: String, val costPer1kTokens: Double) {
    Base("GigaChat", "GigaChat (слабая)", 0.065),
    Pro("GigaChat-Pro", "GigaChat-Pro (средняя)", 0.5),
    Max("GigaChat-Max", "GigaChat-Max (сильная)", 0.65),
    Base2("GigaChat-2", "GigaChat-2 (слабая, v2)", 0.065),
    Pro2("GigaChat-2-Pro", "GigaChat-2-Pro (средняя, v2)", 0.5),
    Max2("GigaChat-2-Max", "GigaChat-2-Max (сильная, v2)", 0.65),
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
        model: GigaChatModel,
    ) {
        if (prompt.isBlank()) return

        _uiState.value = ChatUiState.Loading

        viewModelScope.launch {
            val startMs = System.currentTimeMillis()
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
    var selectedModel by remember { mutableStateOf(GigaChatModel.Base) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        ModelDropdown(
            selectedModel = selectedModel,
            onModelSelected = { selectedModel = it },
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
                        model = selectedModel,
                    )
                },
                enabled = uiState !is ChatUiState.Loading,
            ) {
                Text("Отправить")
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            when (val state = uiState) {
                is ChatUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ChatUiState.Success -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Ответ:",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = state.text)
                        if (state.elapsedMs > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "⏱ ${state.elapsedMs} мс  |  " +
                                        "токены: ${state.promptTokens}→${state.completionTokens} " +
                                        "(всего ${state.totalTokens})  |  " +
                                        "~${String.format("%.4f", state.costRub)} ₽",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
private fun ModelDropdown(
    selectedModel: GigaChatModel,
    onModelSelected: (GigaChatModel) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { isExpanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Модель: ${selectedModel.title}")
        }

        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
        ) {
            GigaChatModel.entries.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.title) },
                    onClick = {
                        onModelSelected(model)
                        isExpanded = false
                    },
                )
            }
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
