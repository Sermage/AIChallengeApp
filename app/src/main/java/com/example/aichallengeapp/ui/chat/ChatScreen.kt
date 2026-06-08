package com.example.aichallengeapp.ui.chat

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
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ChatScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel = hiltViewModel(),
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
            OutlinedButton(onClick = onBack) { Text("←") }
            Text(
                text = "GigaChat",
                style = MaterialTheme.typography.headlineSmall,
            )
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
