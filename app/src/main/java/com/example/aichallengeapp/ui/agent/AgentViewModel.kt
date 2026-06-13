package com.example.aichallengeapp.ui.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichallengeapp.data.AgentConfig
import com.example.aichallengeapp.data.AgentStep
import com.example.aichallengeapp.data.CompressionConfig
import com.example.aichallengeapp.data.GigaChatClient
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

/** Файл, готовый к отправке: уже загружен в Files API, сохранён ID. */
data class PendingAttachment(
    val fileId: String,
    val displayName: String,
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val isUser: Boolean,
    val text: String,
    val steps: List<AgentStep> = emptyList(),
    val totalTokens: Int = 0,
    val elapsedMs: Long = 0,
    /** Токены промпта текущего запроса (prompt_tokens). */
    val requestTokens: Int = 0,
    /** Токены ответа модели (completion_tokens). */
    val completionTokens: Int = 0,
    /** Накопленные токены всей истории диалога включая это сообщение. */
    val historyTokens: Int = 0,
    /** Ответ агента на тот же запрос, но без истории диалога (устаревшее, не заполняется). */
    val noContextAnswer: String? = null,
    /** Токены ответа без контекста (устаревшее, не заполняется). */
    val noContextTokens: Int = 0,
    /** Имена файлов, приложенных к сообщению (только для отображения). */
    val attachmentNames: List<String> = emptyList(),
    /** true, если ответ был обрублен по лимиту max_tokens. */
    val finishedByLength: Boolean = false,
    /** true, если запрос был заблокирован из-за превышения демо-лимита контекста. */
    val isContextOverflow: Boolean = false,
    /** Ответ агента со сжатой историей (устаревшее, не заполняется). */
    val compressedAnswer: String? = null,
    /** Суммарные токены запроса со сжатой историей (устаревшее). */
    val compressedTokens: Int = 0,
    /** prompt_tokens у сжатого варианта (устаревшее). */
    val compressedRequestTokens: Int = 0,
    /** completion_tokens у сжатого варианта (устаревшее). */
    val compressedCompletionTokens: Int = 0,
    /** Доп. токены, потраченные на обновление summary в этом запросе. */
    val compressedSummaryTokens: Int = 0,
    /** Снимок summary после этого запроса (если был). */
    val summarySnapshot: String? = null,
    /** Использовалось ли сжатие при формировании этого запроса. */
    val compressionUsed: Boolean = false,
    /** prompt_tokens THINK-шага — сравнимая с оценкой без сжатия величина. */
    val thinkPromptTokens: Int = 0,
    /** Грубая оценка prompt_tokens THINK-шага, если бы вся история шла без сжатия. */
    val estimatedFullPromptTokens: Int = 0,
    /** true, если к моменту запроса часть истории уже свёрнута в summary. */
    val historyFolded: Boolean = false,
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

    /** Зеркальный набор агентов с включённой компрессией — для A/B-сравнения. */
    private val compressedAgents = PRESET_AGENTS.map { LlmAgent(it) }

    private val _selectedAgentIndex = MutableStateFlow(0)
    val selectedAgentIndex: StateFlow<Int> = _selectedAgentIndex

    /** Ограничение max_tokens: null — без лимита. */
    private val _maxTokens = MutableStateFlow<Int?>(null)
    val maxTokens: StateFlow<Int?> = _maxTokens

    /** Размер скользящего окна истории (кол-во сообщений, передаваемых агенту). */
    private val _contextHistorySize = MutableStateFlow(4)
    val contextHistorySize: StateFlow<Int> = _contextHistorySize

    /** Максимальный суммарный расход токенов за сессию (демо-лимит контекста). */
    private val _maxContextTokens = MutableStateFlow(32768)
    val maxContextTokens: StateFlow<Int> = _maxContextTokens

    /** Включена ли компрессия истории (запускает параллельный «сжатый» агент). */
    private val _compressionEnabled = MutableStateFlow(false)
    val compressionEnabled: StateFlow<Boolean> = _compressionEnabled

    /** Каждые сколько сообщений пересчитывать summary. */
    private val _summarizeEvery = MutableStateFlow(10)
    val summarizeEvery: StateFlow<Int> = _summarizeEvery

    /** Актуальный summary (последняя сводка, сгенерированная сжатым агентом). */
    private val _currentSummary = MutableStateFlow<String?>(null)
    val currentSummary: StateFlow<String?> = _currentSummary

    /** Сколько суммарно токенов потрачено на генерацию summary за сессию. */
    private val _summaryTokensTotal = MutableStateFlow(0)
    val summaryTokensTotal: StateFlow<Int> = _summaryTokensTotal

    /** Файлы, ожидающие отправки вместе со следующим сообщением. */
    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachment>> = _pendingAttachments

    /** Идёт загрузка файла в Files API. */
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    val agentNames: List<String> get() = PRESET_AGENTS.map { it.name }

    private val _savedSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val savedSessions: StateFlow<List<ChatSession>> = _savedSessions

    private var currentSessionId = System.currentTimeMillis()

    init {
        _savedSessions.value = SessionStorage.loadAll(context)
        applyCompressionConfig()
    }

    fun selectAgent(index: Int) {
        if (index == _selectedAgentIndex.value) return
        saveCurrentSession()
        _selectedAgentIndex.value = index
        _messages.value = emptyList()
        agents[index].reset()
        compressedAgents[index].reset()
        _currentSummary.value = null
        _summaryTokensTotal.value = 0
        currentSessionId = System.currentTimeMillis()
    }

    fun setMaxTokens(value: Int?) {
        _maxTokens.value = value
    }

    fun setContextHistorySize(value: Int) {
        _contextHistorySize.value = value
        agents.forEach { it.contextHistorySize = value }
        compressedAgents.forEach { it.contextHistorySize = value }
        applyCompressionConfig()
    }

    fun setMaxContextTokens(value: Int) {
        _maxContextTokens.value = value
    }

    fun setCompressionEnabled(value: Boolean) {
        _compressionEnabled.value = value
        applyCompressionConfig()
    }

    fun setSummarizeEvery(value: Int) {
        _summarizeEvery.value = value
        applyCompressionConfig()
    }

    private fun applyCompressionConfig() {
        val cfg = CompressionConfig(
            keepLastN = _contextHistorySize.value.coerceAtLeast(2),
            summarizeEvery = _summarizeEvery.value,
            enabled = true, // у compressedAgents всегда включено — сравнение vs. agents без сжатия
        )
        compressedAgents.forEach { it.compressionConfig = cfg }
    }

    /** Загружает файл по URI в Files API и добавляет в список ожидающих вложений. */
    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            _isUploading.value = true
            runCatching {
                val cr = context.contentResolver
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Не удалось прочитать файл")
                val mimeType = cr.getType(uri) ?: "application/octet-stream"
                val displayName = resolveDisplayName(uri)
                val fileId = GigaChatClient.uploadFile(bytes, displayName, mimeType)
                _pendingAttachments.value = _pendingAttachments.value +
                        PendingAttachment(fileId, displayName)
            }
            _isUploading.value = false
        }
    }

    fun removeAttachment(fileId: String) {
        _pendingAttachments.value = _pendingAttachments.value.filter { it.fileId != fileId }
    }

    fun send(query: String) {
        if (query.isBlank() || _isLoading.value) return
        val agent = agents[_selectedAgentIndex.value]
        val compressedAgent = compressedAgents[_selectedAgentIndex.value]
        val maxTok = _maxTokens.value
        val attachments = _pendingAttachments.value
        _pendingAttachments.value = emptyList()
        val withCompression = _compressionEnabled.value

        val spentTokens = _messages.value.filterNot { it.isUser }.sumOf { it.totalTokens }
        val limit = _maxContextTokens.value
        if (spentTokens > 0 && spentTokens >= limit) {
            _messages.value = _messages.value + ChatMessage(
                isUser = false,
                text = "Контекст переполнен: потрачено $spentTokens / $limit токенов.\n" +
                        "Очистите историю чтобы продолжить.",
                isContextOverflow = true,
            )
            return
        }

        val fileIds = attachments.map { it.fileId }
        val attachmentNames = attachments.map { it.displayName }

        _messages.value = _messages.value + ChatMessage(
            isUser = true,
            text = query,
            attachmentNames = attachmentNames,
        )
        _isLoading.value = true

        viewModelScope.launch {
            runCatching {
                // Один запрос на ход: либо обычный агент, либо сжатый — в зависимости от флага.
                if (withCompression) {
                    compressedAgent.run(query, maxTok, fileIds)
                } else {
                    agent.run(query, maxTok, fileIds)
                }
            }
                .onSuccess { result ->
                    val historyTokens = _messages.value.filterNot { it.isUser }
                        .sumOf { it.totalTokens } + result.totalTokens

                    if (result.summarySnapshot != null) {
                        _currentSummary.value = result.summarySnapshot
                    }
                    if (result.summaryTokens > 0) {
                        _summaryTokensTotal.value =
                            _summaryTokensTotal.value + result.summaryTokens
                    }

                    _messages.value = _messages.value + ChatMessage(
                        isUser = false,
                        text = result.answer,
                        steps = result.steps,
                        totalTokens = result.totalTokens,
                        elapsedMs = result.elapsedMs,
                        requestTokens = result.requestTokens,
                        completionTokens = result.completionTokens,
                        historyTokens = historyTokens,
                        finishedByLength = result.finishedByLength,
                        compressedSummaryTokens = result.summaryTokens,
                        summarySnapshot = result.summarySnapshot,
                        compressionUsed = withCompression,
                        thinkPromptTokens = result.thinkPromptTokens,
                        estimatedFullPromptTokens = result.estimatedFullPromptTokens,
                        historyFolded = result.historyFolded,
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
        _pendingAttachments.value = emptyList()
        agents[_selectedAgentIndex.value].reset()
        compressedAgents[_selectedAgentIndex.value].reset()
        _currentSummary.value = null
        _summaryTokensTotal.value = 0
        currentSessionId = System.currentTimeMillis()
    }

    fun loadSession(session: ChatSession) {
        val agentIndex = PRESET_AGENTS.indexOfFirst { it.name == session.agentName }
        if (agentIndex >= 0) {
            agents[_selectedAgentIndex.value].reset()
            compressedAgents[_selectedAgentIndex.value].reset()
            _selectedAgentIndex.value = agentIndex
            val users = session.messages.filter { it.isUser }.map { it.text }
            val assistants = session.messages.filter { !it.isUser }.map { it.text }
            agents[agentIndex].restoreHistory(users.zip(assistants))
            compressedAgents[agentIndex].restoreHistory(users.zip(assistants))
        }
        _messages.value = session.messages
        _currentSummary.value =
            session.messages.lastOrNull { it.summarySnapshot != null }?.summarySnapshot
        _summaryTokensTotal.value = session.messages.sumOf { it.compressedSummaryTokens }
        currentSessionId = session.id
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && col >= 0) return cursor.getString(col)
        }
        return uri.lastPathSegment ?: "file"
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


