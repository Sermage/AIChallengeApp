package com.example.aichallengeapp.data

/** Параметры сжатия истории диалога. */
data class CompressionConfig(
    /** Сколько последних сообщений хранить «как есть». */
    val keepLastN: Int = 6,
    /** Каждые N сообщений (user + assistant) пересоздаём summary. */
    val summarizeEvery: Int = 10,
    /** Включена ли компрессия. Если false — агент работает по старой логике. */
    val enabled: Boolean = true,
)

/** Результат компрессии: текст сводки и токены, потраченные на её генерацию. */
data class CompressionResult(
    val summary: String,
    val tokensSpent: Int,
    val elapsedMs: Long,
)

/**
 * Сжимает старые сообщения в короткую сводку, чтобы не передавать их в LLM целиком.
 * Принимает предыдущий summary и инкрементально его обновляет, добавляя новые сообщения.
 */
class HistoryCompressor(
    private val chat: suspend (List<MessageObj>) -> ChatResponseObj = { msgs ->
        GigaChatClient.chat(messages = msgs, temperature = 0.3)
    },
) {

    /**
     * Строит новую сводку диалога.
     *
     * @param previousSummary  предыдущая сводка (или null, если компрессия запускается впервые)
     * @param messagesToFold   сообщения, которые нужно учесть в новой сводке
     *                         (обычно — все сообщения, выпавшие за пределы окна keepLastN)
     */
    suspend fun summarize(
        previousSummary: String?,
        messagesToFold: List<MessageObj>,
    ): CompressionResult {
        val start = System.currentTimeMillis()
        val prompt = buildSummaryPrompt(previousSummary, messagesToFold)
        val response = chat(prompt)
        val text = response.choices.firstOrNull()?.message?.content.orEmpty().trim()
        return CompressionResult(
            summary = text,
            tokensSpent = response.usage?.totalTokens ?: 0,
            elapsedMs = System.currentTimeMillis() - start,
        )
    }

    private fun buildSummaryPrompt(
        previousSummary: String?,
        messages: List<MessageObj>,
    ): List<MessageObj> {
        val system = MessageObj(
            role = "system",
            content = """Ты обновляешь краткую сводку диалога между пользователем и ассистентом.
Сохрани ключевые факты, имена, числа, договорённости и нерешённые вопросы.
Игнорируй вежливые формулировки и пустую вводную речь.
Пиши сжато: 3–6 предложений, без вступлений и заключений.""".trimIndent(),
        )

        val transcript = messages.joinToString("\n") { msg ->
            val who = when (msg.role) {
                "user" -> "Пользователь"
                "assistant" -> "Ассистент"
                else -> msg.role
            }
            "$who: ${msg.content}"
        }

        val userContent = buildString {
            if (!previousSummary.isNullOrBlank()) {
                appendLine("Текущая сводка диалога:")
                appendLine(previousSummary)
                appendLine()
                appendLine("Новые сообщения, которые нужно учесть:")
            } else {
                appendLine("История диалога для сжатия:")
            }
            append(transcript)
            appendLine()
            appendLine()
            append("Выдай обновлённую сводку.")
        }

        return listOf(system, MessageObj(role = "user", content = userContent))
    }
}
