package com.example.aichallengeapp.ui.agent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichallengeapp.data.AgentConfig
import com.example.aichallengeapp.data.AgentStep
import com.example.aichallengeapp.data.LlmAgent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private val PRESET_AGENTS = listOf(
    AgentConfig(
        name = "Помощник",
        systemPrompt = "Ты полезный ассистент. Отвечай на русском языке точно и по делу.",
    ),
    AgentConfig(
        name = "Аналитик",
        systemPrompt = "Ты эксперт-аналитик. Разбирай вопросы структурированно, выделяй ключевые факторы и давай обоснованные выводы.",
        temperature = 0.3,
    ),
    AgentConfig(
        name = "Креативщик",
        systemPrompt = "Ты творческий помощник. Подходи к вопросам нестандартно, предлагай оригинальные идеи и интересные метафоры.",
        temperature = 1.2,
    ),
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val isUser: Boolean,
    val text: String,
    val steps: List<AgentStep> = emptyList(),
    val totalTokens: Int = 0,
    val elapsedMs: Long = 0,
)

@HiltViewModel
class AgentViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val agents = PRESET_AGENTS.map { LlmAgent(it) }

    private val _selectedAgentIndex = MutableStateFlow(0)
    val selectedAgentIndex: StateFlow<Int> = _selectedAgentIndex

    val agentNames: List<String> get() = PRESET_AGENTS.map { it.name }

    private val _savedSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val savedSessions: StateFlow<List<ChatSession>> = _savedSessions

    private var currentSessionId = System.currentTimeMillis()

    init {
        _savedSessions.value = SessionStorage.loadAll(context)
    }

    fun selectAgent(index: Int) {
        if (index == _selectedAgentIndex.value) return
        saveCurrentSession()
        _selectedAgentIndex.value = index
        _messages.value = emptyList()
        agents[index].reset()
        currentSessionId = System.currentTimeMillis()
    }

    fun send(query: String) {
        if (query.isBlank() || _isLoading.value) return
        val agent = agents[_selectedAgentIndex.value]

        _messages.value = _messages.value + ChatMessage(isUser = true, text = query)
        _isLoading.value = true

        viewModelScope.launch {
            runCatching { agent.run(query) }
                .onSuccess { result ->
                    _messages.value = _messages.value + ChatMessage(
                        isUser = false,
                        text = result.answer,
                        steps = result.steps,
                        totalTokens = result.totalTokens,
                        elapsedMs = result.elapsedMs,
                    )
                    saveCurrentSession()
                }
                .onFailure { error ->
                    _messages.value = _messages.value + ChatMessage(
                        isUser = false,
                        text = "Ошибка: ${error.message ?: "Неизвестная ошибка"}",
                    )
                }
            _isLoading.value = false
        }
    }

    fun clearHistory() {
        _messages.value = emptyList()
        agents[_selectedAgentIndex.value].reset()
        currentSessionId = System.currentTimeMillis()
    }

    fun loadSession(session: ChatSession) {
        val agentIndex = PRESET_AGENTS.indexOfFirst { it.name == session.agentName }
        if (agentIndex >= 0) {
            agents[_selectedAgentIndex.value].reset()
            _selectedAgentIndex.value = agentIndex
            val users = session.messages.filter { it.isUser }.map { it.text }
            val assistants = session.messages.filter { !it.isUser }.map { it.text }
            agents[agentIndex].restoreHistory(users.zip(assistants))
        }
        _messages.value = session.messages
        currentSessionId = session.id
    }

    private fun saveCurrentSession() {
        val msgs = _messages.value
        if (msgs.isEmpty()) return
        val session = ChatSession(
            id = currentSessionId,
            agentName = agentNames[_selectedAgentIndex.value],
            timestamp = currentSessionId,
            messages = msgs,
        )
        SessionStorage.save(context, session)
        _savedSessions.value = SessionStorage.loadAll(context)
    }
}
