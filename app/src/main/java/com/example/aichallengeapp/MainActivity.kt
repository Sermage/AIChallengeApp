package com.example.aichallengeapp

import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichallengeapp.data.GigaChatClient
import com.example.aichallengeapp.data.MessageObj
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

/** ViewModel отвечает за отправку запроса в GigaChat и хранение результата. */
class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState

    /** Отправляет [prompt] в GigaChat и обновляет [uiState]. */
    fun sendMessage(prompt: String) {
        if (prompt.isBlank()) return

        _uiState.value = ChatUiState.Loading

        viewModelScope.launch {
            runCatching {
                GigaChatClient.chat(
                    messages = listOf(MessageObj(role = "user", content = prompt)),
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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ChatScreen(modifier = Modifier.padding(innerPadding))
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
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by chatViewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }

    // Показываем Toast при получении ответа
    if (uiState is ChatUiState.Success) {
        val answer = (uiState as ChatUiState.Success).text
        Toast.makeText(context, answer.take(200), Toast.LENGTH_LONG).show()
    }

    // Внешняя колонка: верхний блок фиксирован, нижний скроллируется
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "GigaChat Mini",
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Введите вопрос") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

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
                onClick = { chatViewModel.sendMessage(inputText) },
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
