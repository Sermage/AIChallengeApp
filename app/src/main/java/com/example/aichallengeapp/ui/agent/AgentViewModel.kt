package com.example.aichallengeapp.ui.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichallengeapp.data.AgentConfig
import com.example.aichallengeapp.data.AgentResult
import com.example.aichallengeapp.data.AgentStateSnapshot
import com.example.aichallengeapp.data.AgentStep
import com.example.aichallengeapp.data.CompressionConfig
import com.example.aichallengeapp.data.ContextStrategy
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
    /** Накладные расходы на обновление словаря фактов в этом ходе. */
    val factsTokens: Int = 0,
    /** Снимок словаря фактов после этого хода (STICKY_FACTS). */
    val factsSnapshot: String? = null,
    /** Стратегия контекста, при которой был сделан этот ход. */
    val strategy: ContextStrategy = ContextStrategy.SLIDING_WINDOW,
    /** ID ветки, в которой был сделан этот ход (для BRANCHING). */
    val branchId: String? = null,
)

/** Чекпоинт + независимая ветка диалога для стратегии BRANCHING. */
data class DialogBranch(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val messages: List<ChatMessage>,
    val agentState: AgentStateSnapshot,
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

    /** Ограничение max_tokens: null — без лимита. */
    private val _maxTokens = MutableStateFlow<Int?>(null)
    val maxTokens: StateFlow<Int?> = _maxTokens

    /** Размер скользящего окна истории (кол-во сообщений, передаваемых агенту). */
    private val _contextHistorySize = MutableStateFlow(4)
    val contextHistorySize: StateFlow<Int> = _contextHistorySize

    /** Максимальный суммарный расход токенов за сессию (демо-лимит контекста). */
    private val _maxContextTokens = MutableStateFlow(32768)
    val maxContextTokens: StateFlow<Int> = _maxContextTokens

    /** Активная стратегия управления контекстом. */
    private val _strategy = MutableStateFlow(ContextStrategy.SLIDING_WINDOW)
    val strategy: StateFlow<ContextStrategy> = _strategy

    /** Каждые сколько сообщений пересчитывать summary (только для SUMMARY). */
    private val _summarizeEvery = MutableStateFlow(10)
    val summarizeEvery: StateFlow<Int> = _summarizeEvery

    /** Актуальный summary (последняя сводка, сгенерированная сжатым агентом). */
    private val _currentSummary = MutableStateFlow<String?>(null)
    val currentSummary: StateFlow<String?> = _currentSummary

    /** Сколько суммарно токенов потрачено на генерацию summary за сессию. */
    private val _summaryTokensTotal = MutableStateFlow(0)
    val summaryTokensTotal: StateFlow<Int> = _summaryTokensTotal

    /** Текущий словарь «липких фактов» для стратегии STICKY_FACTS. */
    private val _currentFacts = MutableStateFlow<String?>(null)
    val currentFacts: StateFlow<String?> = _currentFacts

    /** Сколько суммарно токенов потрачено на обновление facts за сессию. */
    private val _factsTokensTotal = MutableStateFlow(0)
    val factsTokensTotal: StateFlow<Int> = _factsTokensTotal

    /** Список веток диалога (для стратегии BRANCHING). */
    private val _branches = MutableStateFlow<List<DialogBranch>>(emptyList())
    val branches: StateFlow<List<DialogBranch>> = _branches

    /** ID активной ветки (null, если стратегия не BRANCHING или веток ещё нет). */
    private val _currentBranchId = MutableStateFlow<String?>(null)
    val currentBranchId: StateFlow<String?> = _currentBranchId

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
        applyStrategy()
    }

    fun selectAgent(index: Int) {
        if (index == _selectedAgentIndex.value) return
        saveCurrentSession()
        _selectedAgentIndex.value = index
        _messages.value = emptyList()
        agents[index].reset()
        _currentSummary.value = null
        _summaryTokensTotal.value = 0
        _currentFacts.value = null
        _factsTokensTotal.value = 0
        _branches.value = emptyList()
        _currentBranchId.value = null
        currentSessionId = System.currentTimeMillis()
        applyStrategy()
    }

    fun setMaxTokens(value: Int?) {
        _maxTokens.value = value
    }

    fun setContextHistorySize(value: Int) {
        _contextHistorySize.value = value
        agents.forEach { it.contextHistorySize = value }
        applyStrategy()
    }

    fun setMaxContextTokens(value: Int) {
        _maxContextTokens.value = value
    }

    fun setStrategy(value: ContextStrategy) {
        if (_strategy.value == value) return
        _strategy.value = value
        applyStrategy()
        // При входе в режим BRANCHING — если веток ещё нет, создаём «главную» из текущего состояния.
        if (value == ContextStrategy.BRANCHING && _branches.value.isEmpty()) {
            initMainBranch()
        }
    }

    fun setSummarizeEvery(value: Int) {
        _summarizeEvery.value = value
        applyStrategy()
    }

    private fun applyStrategy() {
        val cfg = CompressionConfig(
            keepLastN = _contextHistorySize.value.coerceAtLeast(2),
            summarizeEvery = _summarizeEvery.value,
            enabled = _strategy.value == ContextStrategy.SUMMARY,
        )
        agents.forEach {
            it.compressionConfig = cfg
            it.strategy = _strategy.value
            it.contextHistorySize = _contextHistorySize.value
        }
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
        viewModelScope.launch { performTurn(query, _pendingAttachments.value) }
    }

    /**
     * Один ход: добавляет user-сообщение, дёргает агента, пишет ответ в список,
     * обновляет summary/facts/ветки.
     * Возвращает true, если ход успешно выполнен.
     */
    private suspend fun performTurn(
        query: String,
        attachments: List<PendingAttachment>,
    ): Boolean {
        val agent = agents[_selectedAgentIndex.value]
        val maxTok = _maxTokens.value
        _pendingAttachments.value = emptyList()
        val activeStrategy = _strategy.value
        val activeBranchId = _currentBranchId.value

        val spentTokens = _messages.value.filterNot { it.isUser }.sumOf { it.totalTokens }
        val limit = _maxContextTokens.value
        if (spentTokens > 0 && spentTokens >= limit) {
            _messages.value = _messages.value + ChatMessage(
                isUser = false,
                text = "Контекст переполнен: потрачено $spentTokens / $limit токенов.\n" +
                        "Очистите историю чтобы продолжить.",
                isContextOverflow = true,
            )
            return false
        }

        val fileIds = attachments.map { it.fileId }
        val attachmentNames = attachments.map { it.displayName }

        _messages.value = _messages.value + ChatMessage(
            isUser = true,
            text = query,
            attachmentNames = attachmentNames,
            strategy = activeStrategy,
            branchId = activeBranchId,
        )
        _isLoading.value = true

        val outcome = runCatching { agent.run(query, maxTok, fileIds) }
        handleTurnOutcome(outcome, activeStrategy, activeBranchId)
        _isLoading.value = false
        return outcome.isSuccess
    }

    private fun handleTurnOutcome(
        outcome: Result<AgentResult>,
        activeStrategy: ContextStrategy,
        activeBranchId: String?,
    ) {
        outcome
            .onSuccess { result -> appendAssistantMessage(result, activeStrategy, activeBranchId) }
            .onFailure { error ->
                _messages.value = _messages.value + ChatMessage(
                    isUser = false,
                    text = "Ошибка: ${error.message ?: "Неизвестная ошибка"}",
                    strategy = activeStrategy,
                    branchId = activeBranchId,
                )
            }
    }

    private fun appendAssistantMessage(
        result: AgentResult,
        activeStrategy: ContextStrategy,
        activeBranchId: String?,
    ) {
        val historyTokens = _messages.value.filterNot { it.isUser }
            .sumOf { it.totalTokens } + result.totalTokens

        if (result.summarySnapshot != null) {
            _currentSummary.value = result.summarySnapshot
        }
        if (result.summaryTokens > 0) {
            _summaryTokensTotal.value = _summaryTokensTotal.value + result.summaryTokens
        }
        if (result.factsSnapshot != null) {
            _currentFacts.value = result.factsSnapshot
        }
        if (result.factsTokens > 0) {
            _factsTokensTotal.value = _factsTokensTotal.value + result.factsTokens
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
            compressionUsed = activeStrategy == ContextStrategy.SUMMARY,
            thinkPromptTokens = result.thinkPromptTokens,
            estimatedFullPromptTokens = result.estimatedFullPromptTokens,
            historyFolded = result.historyFolded,
            factsTokens = result.factsTokens,
            factsSnapshot = result.factsSnapshot,
            strategy = activeStrategy,
            branchId = activeBranchId,
        )

        if (activeStrategy == ContextStrategy.BRANCHING && activeBranchId != null) {
            syncCurrentBranchState()
        }
        saveCurrentSession()
    }

    fun clearHistory() {
        _messages.value = emptyList()
        _pendingAttachments.value = emptyList()
        agents[_selectedAgentIndex.value].reset()
        _currentSummary.value = null
        _summaryTokensTotal.value = 0
        _currentFacts.value = null
        _factsTokensTotal.value = 0
        _branches.value = emptyList()
        _currentBranchId.value = null
        currentSessionId = System.currentTimeMillis()
        if (_strategy.value == ContextStrategy.BRANCHING) initMainBranch()
    }

    fun loadSession(session: ChatSession) {
        val agentIndex = PRESET_AGENTS.indexOfFirst { it.name == session.agentName }
        if (agentIndex >= 0) {
            agents[_selectedAgentIndex.value].reset()
            _selectedAgentIndex.value = agentIndex
        }

        // Восстанавливаем конфигурацию, если она сохранена.
        session.config?.let { cfg ->
            _maxTokens.value = cfg.maxTokens
            _contextHistorySize.value = cfg.contextHistorySize
            _maxContextTokens.value = cfg.maxContextTokens
            _strategy.value = cfg.strategy
            _summarizeEvery.value = cfg.summarizeEvery
        }

        _messages.value = session.messages
        _currentSummary.value =
            session.messages.lastOrNull { it.summarySnapshot != null }?.summarySnapshot
        _summaryTokensTotal.value = session.messages.sumOf { it.compressedSummaryTokens }
        _currentFacts.value =
            session.messages.lastOrNull { it.factsSnapshot != null }?.factsSnapshot
        _factsTokensTotal.value = session.messages.sumOf { it.factsTokens }

        // Восстанавливаем ветки; для каждой ветки также восстанавливаем агента.
        if (session.branches.isNotEmpty()) {
            _branches.value = session.branches
            _currentBranchId.value = session.currentBranchId
            val activeBranch = session.branches.firstOrNull { it.id == session.currentBranchId }
                ?: session.branches.first()
            if (agentIndex >= 0) {
                agents[agentIndex].restoreState(
                    rawHistory = activeBranch.agentState.history,
                    summary = activeBranch.agentState.summary,
                    facts = activeBranch.agentState.facts,
                    foldedCount = activeBranch.agentState.foldedCount,
                )
            }
        } else {
            _branches.value = emptyList()
            _currentBranchId.value = null
            if (agentIndex >= 0) {
                val users = session.messages.filter { it.isUser }.map { it.text }
                val assistants = session.messages.filter { !it.isUser }.map { it.text }
                agents[agentIndex].restoreHistory(users.zip(assistants))
            }
        }

        currentSessionId = session.id
        applyStrategy()
    }

    // ── Ветки диалога ────────────────────────────────────────────────────────

    /** Создаёт «главную» ветку из текущего состояния (вызывается при входе в BRANCHING). */
    private fun initMainBranch() {
        val agent = agents[_selectedAgentIndex.value]
        val main = DialogBranch(
            id = "main-${System.currentTimeMillis()}",
            name = "main",
            parentId = null,
            createdAt = System.currentTimeMillis(),
            messages = _messages.value,
            agentState = agent.snapshotState(),
        )
        _branches.value = listOf(main)
        _currentBranchId.value = main.id
    }

    /**
     * Сохраняет чекпоинт и создаёт новую ветку, разветвляясь от текущего состояния.
     * После вызова активна именно новая ветка, чтобы пользователь сразу мог в ней писать.
     */
    fun forkBranch(name: String) {
        if (_strategy.value != ContextStrategy.BRANCHING) return
        // Прежде всего — фиксируем текущее состояние родительской ветки.
        syncCurrentBranchState()
        val parentId = _currentBranchId.value
        val agent = agents[_selectedAgentIndex.value]
        val branch = DialogBranch(
            id = "br-${System.currentTimeMillis()}",
            name = name.ifBlank { "branch ${_branches.value.size + 1}" },
            parentId = parentId,
            createdAt = System.currentTimeMillis(),
            messages = _messages.value,
            agentState = agent.snapshotState(),
        )
        _branches.value = _branches.value + branch
        _currentBranchId.value = branch.id
    }

    /** Переключается на указанную ветку — восстанавливает её сообщения и состояние агента. */
    fun switchBranch(branchId: String) {
        if (_strategy.value != ContextStrategy.BRANCHING) return
        if (branchId == _currentBranchId.value) return
        // Сохраняем состояние текущей ветки перед переключением.
        syncCurrentBranchState()
        val target = _branches.value.firstOrNull { it.id == branchId } ?: return
        _messages.value = target.messages
        agents[_selectedAgentIndex.value].restoreState(
            rawHistory = target.agentState.history,
            summary = target.agentState.summary,
            facts = target.agentState.facts,
            foldedCount = target.agentState.foldedCount,
        )
        _currentSummary.value = target.agentState.summary
        _currentFacts.value = target.agentState.facts
        _currentBranchId.value = branchId
    }

    fun deleteBranch(branchId: String) {
        val list = _branches.value
        if (list.size <= 1) return
        val remaining = list.filterNot { it.id == branchId }
        _branches.value = remaining
        if (_currentBranchId.value == branchId) {
            switchBranch(remaining.first().id)
        }
    }

    /** Синхронизирует снимок активной ветки с текущим состоянием экрана и агента. */
    private fun syncCurrentBranchState() {
        val id = _currentBranchId.value ?: return
        val list = _branches.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val updated = list[idx].copy(
            messages = _messages.value,
            agentState = agents[_selectedAgentIndex.value].snapshotState(),
        )
        _branches.value = list.toMutableList().also { it[idx] = updated }
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
        val config = ChatConfig(
            maxTokens = _maxTokens.value,
            contextHistorySize = _contextHistorySize.value,
            maxContextTokens = _maxContextTokens.value,
            strategy = _strategy.value,
            summarizeEvery = _summarizeEvery.value,
        )
        val session = ChatSession(
            id = currentSessionId,
            agentName = agentNames[_selectedAgentIndex.value],
            timestamp = currentSessionId,
            messages = msgs,
            config = config,
            branches = _branches.value,
            currentBranchId = _currentBranchId.value,
        )
        SessionStorage.save(context, session)
        _savedSessions.value = SessionStorage.loadAll(context)
    }
}


