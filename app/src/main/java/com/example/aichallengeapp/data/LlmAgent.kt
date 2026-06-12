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
)

/**
 * Простой LLM-агент. Принимает запрос пользователя и выполняет двухшаговую цепочку:
 *   Шаг 1 (THINK) — строит внутреннее рассуждение.
 *   Шаг 2 (ANSWER) — формулирует финальный ответ на основе рассуждения.
 *
 * История диалога сохраняется между вызовами [run] для поддержки контекста.
 */
class LlmAgent(val config: AgentConfig) {

    /** Количество последних сообщений истории, передаваемых в контекст. */
    var contextHistorySize: Int = 4

    private val history = mutableListOf<MessageObj>()

    /** Запускает агента с историей диалога и сохраняет обмен в историю. */
    suspend fun run(
        userQuery: String,
        maxTokens: Int? = null,
        attachments: List<String> = emptyList(),
    ): AgentResult {
        val result = runInternal(userQuery, maxTokens, historyOverride = null, attachments)
        history += MessageObj("user", userQuery)
        history += MessageObj("assistant", result.answer)
        return result
    }

    /** Запускает агента без истории диалога — только текущий запрос. */
    suspend fun runNoHistory(
        userQuery: String,
        maxTokens: Int? = null,
        attachments: List<String> = emptyList(),
    ): AgentResult = runInternal(userQuery, maxTokens, historyOverride = emptyList(), attachments)

    /** Очищает историю диалога. */
    fun reset() {
        history.clear()
    }

    /** Восстанавливает последние [pairs] обменов «user/assistant» для поддержки контекста. */
    fun restoreHistory(pairs: List<Pair<String, String>>) {
        history.clear()
        pairs.takeLast(2).forEach { (user, assistant) ->
            history += MessageObj("user", user)
            history += MessageObj("assistant", assistant)
        }
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

        // Суммируем по обоим шагам — requestTokens + completionTokens == totalTokens
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
        )
    }

    private fun buildThinkMessages(
        query: String,
        historyOverride: List<MessageObj>?,
        attachments: List<String>,
    ): List<MessageObj> {
        val system = MessageObj(
            role = "system",
            content = """${config.systemPrompt}

Перед тем как дать ответ, подробно изложи свои рассуждения: что ты знаешь о проблеме, какие есть варианты, что важно учесть. Пиши только процесс мышления — без финального ответа.""".trimIndent(),
        )
        val recentHistory = (historyOverride ?: history).takeLast(contextHistorySize)
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
