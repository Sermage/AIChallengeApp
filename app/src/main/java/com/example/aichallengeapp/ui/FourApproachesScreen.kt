package com.example.aichallengeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichallengeapp.data.GigaChatClient
import com.example.aichallengeapp.data.MessageObj
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────
// Задача по умолчанию
// ──────────────────────────────────────────────

private const val DEFAULT_TASK =
    """Задача: три человека — Алексей, Борис и Виктор — работают программистом, дизайнером и менеджером (не обязательно в этом порядке). Известно:
1. Алексей не является программистом.
2. Борис не является менеджером.
3. Программист живёт в Москве.
4. Алексей живёт в Санкт-Петербурге.
Кто кем работает?"""

// ──────────────────────────────────────────────
// Варианты подходов
// ──────────────────────────────────────────────

data class ApproachOption(
    val title: String,
    val description: String,
)

private val APPROACH_OPTIONS = listOf(
    ApproachOption(
        title = "1. Прямой ответ",
        description = "Задача отправляется без дополнительных инструкций",
    ),
    ApproachOption(
        title = "2. Пошаговое решение",
        description = "К задаче добавлена инструкция «решай пошагово»",
    ),
    ApproachOption(
        title = "3. Мета-промпт",
        description = "Сначала модель составляет оптимальный промпт, затем решает по нему",
    ),
    ApproachOption(
        title = "4. Группа экспертов",
        description = "Аналитик, Логик и Критик дают своё решение",
    ),
)

// ──────────────────────────────────────────────
// Состояние результата
// ──────────────────────────────────────────────

sealed interface ResultUiState {
    data object Idle : ResultUiState
    data object Loading : ResultUiState
    data class Success(val text: String) : ResultUiState
    data class Error(val message: String) : ResultUiState
}

// ──────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────

class FourApproachesViewModel : ViewModel() {

    private val _task = MutableStateFlow(DEFAULT_TASK.trimIndent())
    val task: StateFlow<String> = _task

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex

    private val _result = MutableStateFlow<ResultUiState>(ResultUiState.Idle)
    val result: StateFlow<ResultUiState> = _result

    fun updateTask(text: String) {
        _task.value = text
    }

    fun selectApproach(index: Int) {
        _selectedIndex.value = index
        _result.value = ResultUiState.Idle
    }

    fun solve() {
        val task = _task.value.trim()
        if (task.isBlank()) return
        _result.value = ResultUiState.Loading

        viewModelScope.launch {
            _result.value = when (_selectedIndex.value) {
                0 -> solveApproach1(task)
                1 -> solveApproach2(task)
                2 -> solveApproach3(task)
                3 -> solveApproach4(task)
                else -> ResultUiState.Error("Неизвестный подход")
            }
        }
    }

    fun reset() {
        _result.value = ResultUiState.Idle
    }

    // Подход 1: прямой вопрос без инструкций
    private suspend fun solveApproach1(task: String): ResultUiState = runCatching {
        GigaChatClient.chat(
            messages = listOf(MessageObj(role = "user", content = task)),
        )
    }.toResultUiState()

    // Подход 2: «решай пошагово»
    private suspend fun solveApproach2(task: String): ResultUiState = runCatching {
        GigaChatClient.chat(
            messages = listOf(
                MessageObj(
                    role = "user",
                    content = "$task\n\nРешай пошагово, объясняя каждый шаг рассуждения."
                ),
            ),
        )
    }.toResultUiState()

    // Подход 3: мета-промпт — сначала генерируем промпт, потом используем
    private suspend fun solveApproach3(task: String): ResultUiState = runCatching {
        val metaRequest = """
            Составь оптимальный промпт для решения следующей логической задачи.
            Промпт должен быть чётким, структурированным и направлять модель к правильному ответу.
            Верни ТОЛЬКО текст промпта без пояснений.

            Задача:
            $task
        """.trimIndent()

        val metaResponse = GigaChatClient.chat(
            messages = listOf(MessageObj(role = "user", content = metaRequest)),
        )
        val generatedPrompt = metaResponse.choices.firstOrNull()?.message?.content.orEmpty()

        GigaChatClient.chat(
            messages = listOf(
                MessageObj(role = "user", content = generatedPrompt),
                MessageObj(role = "assistant", content = "Понял, готов следовать этому промпту."),
                MessageObj(role = "user", content = task),
            ),
        )
    }.toResultUiState()

    // Подход 4: группа экспертов — аналитик, логик, критик
    private suspend fun solveApproach4(task: String): ResultUiState = runCatching {
        val expertPrompt = """
            Решите следующую логическую задачу с точки зрения трёх экспертов.

            Задача:
            $task

            Дайте ответ от каждого эксперта:

            [АНАЛИТИК] — систематически анализирует все условия и строит таблицу возможностей:

            [ЛОГИК] — применяет формальные правила дедукции и метод исключения:

            [КРИТИК] — проверяет ответ на противоречия и подтверждает или опровергает решение:

            [ИТОГ] — финальный согласованный ответ группы экспертов:
        """.trimIndent()

        GigaChatClient.chat(
            messages = listOf(MessageObj(role = "user", content = expertPrompt)),
        )
    }.toResultUiState()

    private fun Result<com.example.aichallengeapp.data.ChatResponseObj>.toResultUiState(): ResultUiState =
        fold(
            onSuccess = { response ->
                val text = response.choices.firstOrNull()?.message?.content.orEmpty()
                if (text.isNotBlank()) ResultUiState.Success(text)
                else ResultUiState.Error("Пустой ответ от модели")
            },
            onFailure = { ResultUiState.Error(it.message ?: "Неизвестная ошибка") },
        )
}

// ──────────────────────────────────────────────
// UI
// ──────────────────────────────────────────────

@Composable
fun FourApproachesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FourApproachesViewModel = viewModel(),
) {
    val task by viewModel.task.collectAsState()
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val result by viewModel.result.collectAsState()
    val isSolving = result is ResultUiState.Loading

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Четыре подхода к задаче",
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = task,
            onValueChange = { viewModel.updateTask(it) },
            label = { Text("Задача") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            enabled = !isSolving,
        )

        ApproachDropdown(
            options = APPROACH_OPTIONS,
            selectedIndex = selectedIndex,
            onSelected = { viewModel.selectApproach(it) },
            enabled = !isSolving,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Назад")
            }
            OutlinedButton(
                onClick = { viewModel.reset() },
                enabled = !isSolving && result !is ResultUiState.Idle,
                modifier = Modifier.weight(1f),
            ) {
                Text("Сбросить")
            }
            Button(
                onClick = { viewModel.solve() },
                enabled = !isSolving && task.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isSolving) "Решаю..." else "Решить")
            }
        }

        ResultArea(result = result)
    }
}

@Composable
private fun ApproachDropdown(
    options: List<ApproachOption>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options[selectedIndex]

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Text(selected.title)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = option.title,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultArea(result: ResultUiState) {
    when (result) {
        is ResultUiState.Idle -> Unit
        is ResultUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is ResultUiState.Success -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Ответ:",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = result.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is ResultUiState.Error -> {
            Text(
                text = "Ошибка: ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
