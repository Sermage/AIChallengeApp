package com.example.aichallengeapp.ui.four_approaches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichallengeapp.data.ChatResponseObj
import com.example.aichallengeapp.data.GigaChatRepository
import com.example.aichallengeapp.data.MessageObj
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DEFAULT_TASK =
    """Задача: три человека — Алексей, Борис и Виктор — работают программистом, дизайнером и менеджером (не обязательно в этом порядке). Известно:
1. Алексей не является программистом.
2. Борис не является менеджером.
3. Программист живёт в Москве.
4. Алексей живёт в Санкт-Петербурге.
Кто кем работает?"""

sealed interface ResultUiState {
    data object Idle : ResultUiState
    data object Loading : ResultUiState
    data class Success(val text: String) : ResultUiState
    data class Error(val message: String) : ResultUiState
}

@HiltViewModel
class FourApproachesViewModel @Inject constructor(
    private val repository: GigaChatRepository,
) : ViewModel() {

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

    private suspend fun solveApproach1(task: String): ResultUiState = runCatching {
        repository.chat(
            messages = listOf(MessageObj(role = "user", content = task)),
        )
    }.toResultUiState()

    private suspend fun solveApproach2(task: String): ResultUiState = runCatching {
        repository.chat(
            messages = listOf(
                MessageObj(
                    role = "user",
                    content = "$task\n\nРешай пошагово, объясняя каждый шаг рассуждения.",
                ),
            ),
        )
    }.toResultUiState()

    private suspend fun solveApproach3(task: String): ResultUiState = runCatching {
        val metaRequest = """
            Составь оптимальный промпт для решения следующей логической задачи.
            Промпт должен быть чётким, структурированным и направлять модель к правильному ответу.
            Верни ТОЛЬКО текст промпта без пояснений.

            Задача:
            $task
        """.trimIndent()

        val metaResponse = repository.chat(
            messages = listOf(MessageObj(role = "user", content = metaRequest)),
        )
        val generatedPrompt = metaResponse.choices.firstOrNull()?.message?.content.orEmpty()

        repository.chat(
            messages = listOf(
                MessageObj(role = "user", content = generatedPrompt),
                MessageObj(role = "assistant", content = "Понял, готов следовать этому промпту."),
                MessageObj(role = "user", content = task),
            ),
        )
    }.toResultUiState()

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

        repository.chat(
            messages = listOf(MessageObj(role = "user", content = expertPrompt)),
        )
    }.toResultUiState()

    private fun Result<ChatResponseObj>.toResultUiState(): ResultUiState =
        fold(
            onSuccess = { response ->
                val text = response.choices.firstOrNull()?.message?.content.orEmpty()
                if (text.isNotBlank()) ResultUiState.Success(text)
                else ResultUiState.Error("Пустой ответ от модели")
            },
            onFailure = { ResultUiState.Error(it.message ?: "Неизвестная ошибка") },
        )
}
