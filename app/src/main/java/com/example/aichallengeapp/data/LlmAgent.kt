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
)

/**
 * Простой LLM-агент. Принимает запрос пользователя и выполняет двухшаговую цепочку:
 *   Шаг 1 (THINK) — строит внутреннее рассуждение.
 *   Шаг 2 (ANSWER) — формулирует финальный ответ на основе рассуждения.
 *
 * Вся логика обращения к API инкапсулирована внутри агента.
 * История диалога сохраняется между вызовами [run] для поддержки контекста.
 */
class LlmAgent(val config: AgentConfig) {

    private val history = mutableListOf<MessageObj>()

    /**
     * Запускает агента с [userQuery] и возвращает [AgentResult] со всеми шагами.
     */
    suspend fun run(userQuery: String): AgentResult {
        val startMs = System.currentTimeMillis()
        val steps = mutableListOf<AgentStep>()
        var totalTokens = 0

        // — Шаг 1: Думаем —
        val thinkResponse = GigaChatClient.chat(
            messages = buildThinkMessages(userQuery),
            temperature = config.temperature,
            model = config.model,
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
        )
        val answer = answerResponse.choices.firstOrNull()?.message?.content.orEmpty()
        val answerTokens = answerResponse.usage?.totalTokens ?: 0
        totalTokens += answerTokens
        steps += AgentStep(StepType.ANSWER, answer, answerTokens)

        // Сохраняем последний обмен в историю для контекста следующего запроса
        history += MessageObj("user", userQuery)
        history += MessageObj("assistant", answer)

        return AgentResult(
            steps = steps,
            answer = answer,
            totalTokens = totalTokens,
            elapsedMs = System.currentTimeMillis() - startMs,
        )
    }

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

    private fun buildThinkMessages(query: String): List<MessageObj> {
        val system = MessageObj(
            role = "system",
            content = """${config.systemPrompt}

Перед тем как дать ответ, подробно изложи свои рассуждения: что ты знаешь о проблеме, какие есть варианты, что важно учесть. Пиши только процесс мышления — без финального ответа.""".trimIndent(),
        )
        // последние 2 обмена для поддержки контекста
        val recentHistory = history.takeLast(4)
        return listOf(system) + recentHistory + listOf(MessageObj("user", query))
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
