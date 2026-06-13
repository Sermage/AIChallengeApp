package com.example.aichallengeapp.data

/** Конфигурация агента: имя, системный промпт, модель, температура. */
data class AgentConfig(
    val name: String,
    val systemPrompt: String,
    val model: String = GigaChatClient.MODEL,
    val temperature: Double = 0.7,
)

/** Тип шага в цепочке рассуждений агента. */
enum class StepType(val label: String) {
    THINK("Размышление"),
    ANSWER("Финальный ответ"),
}

/** Один шаг выполнения агента. */
data class AgentStep(
    val type: StepType,
    val content: String,
    val tokens: Int = 0,
)

/** Итог работы агента за один запрос. */
data class AgentResult(
    val steps: List<AgentStep>,
    val answer: String,
    val totalTokens: Int,
    val elapsedMs: Long,
    /** Сумма prompt_tokens по обоим шагам (THINK + ANSWER). */
    val requestTokens: Int,
    /** Сумма completion_tokens по обоим шагам (THINK + ANSWER). */
    val completionTokens: Int,
    /** true, если ответ обрублен по лимиту max_tokens (finish_reason = "length"). */
    val finishedByLength: Boolean = false,
    /** Накладные расходы на обновление summary в этом запросе (0, если не было перегенерации). */
    val summaryTokens: Int = 0,
    /** Актуальный summary после этого вызова (если компрессия включена). */
    val summarySnapshot: String? = null,
    /**
     * prompt_tokens только THINK-шага — это единственный вызов, в который попадает
     * история диалога и summary, поэтому именно его имеет смысл сравнивать с оценкой
     * «без сжатия».
     */
    val thinkPromptTokens: Int = 0,
    /**
     * Грубая оценка prompt_tokens THINK-шага, если бы вся история передавалась без сжатия.
     * Используется для расчёта «сэкономлено за счёт компрессии».
     */
    val estimatedFullPromptTokens: Int = 0,
    /** true, если к моменту этого вызова часть истории уже была свёрнута в summary. */
    val historyFolded: Boolean = false,
)

/**
 * Простой LLM-агент. Принимает запрос пользователя и выполняет двухшаговую цепочку:
 *   Шаг 1 (THINK) — строит внутреннее рассуждение.
 *   Шаг 2 (ANSWER) — формулирует финальный ответ на основе рассуждения.
 *
 * Поддерживает сжатие истории: старые сообщения заменяются краткой сводкой,
 * пересоздаваемой каждые `compressionConfig.summarizeEvery` сообщений.
 */
class LlmAgent(
    val config: AgentConfig,
    private val compressor: HistoryCompressor = HistoryCompressor(),
) {

    /** Количество последних сообщений истории, передаваемых в контекст «как есть». */
    var contextHistorySize: Int = 4

    /** Параметры сжатия истории. Если enabled=false — агент работает по старой логике. */
    var compressionConfig: CompressionConfig = CompressionConfig(enabled = false)

    private val history = mutableListOf<MessageObj>()

    /** Текущая сводка диалога (null, пока не было ни одной компрессии). */
    var summary: String? = null
        private set

    /** Суммарный расход токенов на генерацию summary за всю сессию. */
    var summaryTokensSpent: Int = 0
        private set

    /** Сколько сообщений из начала истории уже учтено в summary. */
    private var foldedCount: Int = 0

    /** Запускает агента с историей диалога и сохраняет обмен в историю. */
    suspend fun run(
        userQuery: String,
        maxTokens: Int? = null,
        attachments: List<String> = emptyList(),
    ): AgentResult {
        val result = runInternal(userQuery, maxTokens, historyOverride = null, attachments)
        history += MessageObj("user", userQuery)
        history += MessageObj("assistant", result.answer)

        // После обновления истории — проверяем, не пора ли обновить summary.
        val (summaryTokens, snapshot) = maybeCompress()
        return result.copy(
            summaryTokens = summaryTokens,
            summarySnapshot = snapshot,
            historyFolded = foldedCount > 0,
        )
    }

    /** Запускает агента без истории диалога — только текущий запрос. */
    suspend fun runNoHistory(
        userQuery: String,
        maxTokens: Int? = null,
        attachments: List<String> = emptyList(),
    ): AgentResult = runInternal(userQuery, maxTokens, historyOverride = emptyList(), attachments)

    /** Очищает историю диалога и сводку. */
    fun reset() {
        history.clear()
        summary = null
        summaryTokensSpent = 0
        foldedCount = 0
    }

    /** Восстанавливает последние [pairs] обменов «user/assistant» для поддержки контекста. */
    fun restoreHistory(pairs: List<Pair<String, String>>) {
        reset()
        pairs.takeLast(2).forEach { (user, assistant) ->
            history += MessageObj("user", user)
            history += MessageObj("assistant", assistant)
        }
    }

    /**
     * Если компрессия включена и в «сырой» части истории накопилось больше
     * [CompressionConfig.keepLastN] + [CompressionConfig.summarizeEvery] сообщений,
     * сжимает лишние в summary.
     */
    private suspend fun maybeCompress(): Pair<Int, String?> {
        val cfg = compressionConfig
        if (!cfg.enabled) return 0 to null

        val rawSize = history.size - foldedCount
        val excess = rawSize - cfg.keepLastN
        if (excess < cfg.summarizeEvery) return 0 to summary

        // Берём `excess` сообщений из начала «сырой» части и сворачиваем их в summary.
        val newCutoff = foldedCount + excess
        val batch = history.subList(foldedCount, newCutoff).toList()
        val result = compressor.summarize(summary, batch)
        summary = result.summary
        summaryTokensSpent += result.tokensSpent
        foldedCount = newCutoff
        return result.tokensSpent to summary
    }

    /**
     * Грубая оценка prompt_tokens, если бы вся история передавалась без сжатия.
     * Считаем по символам ≈ 4 символа на токен (типичный коэффициент для русского/латиницы).
     */
    private fun estimateFullPromptTokens(query: String): Int {
        val chars = config.systemPrompt.length +
                history.sumOf { it.content.length } +
                query.length
        return chars / 4
    }

    private suspend fun runInternal(
        userQuery: String,
        maxTokens: Int?,
        historyOverride: List<MessageObj>?,
        attachments: List<String> = emptyList(),
    ): AgentResult {
        val startMs = System.currentTimeMillis()
        val steps = mutableListOf<AgentStep>()
        var totalTokens = 0
        val estimatedFull = estimateFullPromptTokens(userQuery)

        // — Шаг 1: Думаем —
        val thinkResponse = GigaChatClient.chat(
            messages = buildThinkMessages(userQuery, historyOverride, attachments),
            temperature = config.temperature,
            model = config.model,
            maxTokens = maxTokens,
        )
        val thinking = thinkResponse.choices.firstOrNull()?.message?.content.orEmpty()
        val thinkTokens = thinkResponse.usage?.totalTokens ?: 0
        totalTokens += thinkTokens
        steps += AgentStep(StepType.THINK, thinking, thinkTokens)

        // — Шаг 2: Отвечаем —
        val answerResponse = GigaChatClient.chat(
            messages = buildAnswerMessages(userQuery, thinking),
            temperature = config.temperature,
            model = config.model,
            maxTokens = maxTokens,
        )
        val answerChoice = answerResponse.choices.firstOrNull()
        val answer = answerChoice?.message?.content.orEmpty()
        val answerTokens = answerResponse.usage?.totalTokens ?: 0
        totalTokens += answerTokens
        steps += AgentStep(StepType.ANSWER, answer, answerTokens)

        val requestTokens = (thinkResponse.usage?.promptTokens ?: 0) +
                (answerResponse.usage?.promptTokens ?: 0)
        val completionTokens = (thinkResponse.usage?.completionTokens ?: 0) +
                (answerResponse.usage?.completionTokens ?: 0)

        return AgentResult(
            steps = steps,
            answer = answer,
            totalTokens = totalTokens,
            elapsedMs = System.currentTimeMillis() - startMs,
            requestTokens = requestTokens,
            completionTokens = completionTokens,
            finishedByLength = answerChoice?.finishReason == "length",
            thinkPromptTokens = thinkResponse.usage?.promptTokens ?: 0,
            estimatedFullPromptTokens = estimatedFull,
        )
    }

    private fun buildThinkMessages(
        query: String,
        historyOverride: List<MessageObj>?,
        attachments: List<String>,
    ): List<MessageObj> {
        val systemContent = buildString {
            append(config.systemPrompt)
            append("\n\n")
            append("Перед тем как дать ответ, подробно изложи свои рассуждения: что ты знаешь о проблеме, какие есть варианты, что важно учесть. Пиши только процесс мышления — без финального ответа.")
            if (compressionConfig.enabled && !summary.isNullOrBlank() && historyOverride == null) {
                append("\n\n")
                append("Сводка предыдущего диалога (используй её как контекст):\n")
                append(summary)
            }
        }
        val system = MessageObj(role = "system", content = systemContent)

        val recentHistory = if (historyOverride != null) {
            historyOverride.takeLast(contextHistorySize)
        } else if (compressionConfig.enabled) {
            // «Сырая» часть истории (всё, что ещё не сжато) — максимум keepLastN.
            val raw = history.subList(foldedCount, history.size)
            raw.takeLast(minOf(contextHistorySize, compressionConfig.keepLastN))
        } else {
            history.takeLast(contextHistorySize)
        }

        val userMsg = MessageObj(
            role = "user",
            content = query,
            attachments = attachments.takeIf { it.isNotEmpty() },
        )
        return listOf(system) + recentHistory + listOf(userMsg)
    }

    private fun buildAnswerMessages(query: String, thinking: String): List<MessageObj> {
        val system = MessageObj(
            role = "system",
            content = "${config.systemPrompt}\n\nНа основе проведённого анализа дай чёткий и лаконичный финальный ответ.",
        )
        val context = MessageObj(
            role = "user",
            content = "Запрос: $query\n\nМои рассуждения:\n$thinking\n\nСформулируй финальный ответ.",
        )
        return listOf(system, context)
    }
}
