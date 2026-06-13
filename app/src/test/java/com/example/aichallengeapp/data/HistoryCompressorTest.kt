package com.example.aichallengeapp.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCompressorTest {

    private fun fakeResponse(text: String, tokens: Int): ChatResponseObj = ChatResponseObj(
        choices = listOf(
            ChoiceObj(message = MessageObj(role = "assistant", content = text)),
        ),
        usage = UsageObj(promptTokens = 0, completionTokens = tokens, totalTokens = tokens),
    )

    @Test
    fun `summarize without previous summary builds plain request`() = runBlocking {
        var captured: List<MessageObj>? = null
        val compressor = HistoryCompressor(chat = { msgs ->
            captured = msgs
            fakeResponse("СВОДКА", tokens = 42)
        })

        val messages = listOf(
            MessageObj("user", "Привет, меня зовут Сергей"),
            MessageObj("assistant", "Здравствуйте, Сергей"),
        )
        val result = compressor.summarize(previousSummary = null, messagesToFold = messages)

        assertEquals("СВОДКА", result.summary)
        assertEquals(42, result.tokensSpent)
        assertNotNull(captured)
        val userPrompt = captured!!.last().content
        assertTrue(
            "должен содержать вступление про сжатие, без 'Текущая сводка'",
            userPrompt.contains("История диалога для сжатия:")
                    && !userPrompt.contains("Текущая сводка"),
        )
        assertTrue("должен содержать имя", userPrompt.contains("Сергей"))
    }

    @Test
    fun `summarize with previous summary references it in prompt`() = runBlocking {
        var captured: List<MessageObj>? = null
        val compressor = HistoryCompressor(chat = { msgs ->
            captured = msgs
            fakeResponse("ОБНОВЛЁННАЯ СВОДКА", tokens = 17)
        })

        val result = compressor.summarize(
            previousSummary = "Пользователь — Сергей, обсуждали Android.",
            messagesToFold = listOf(
                MessageObj("user", "А теперь расскажи про Kotlin"),
                MessageObj("assistant", "Kotlin — это…"),
            ),
        )

        assertEquals("ОБНОВЛЁННАЯ СВОДКА", result.summary)
        assertEquals(17, result.tokensSpent)
        val userPrompt = captured!!.last().content
        assertTrue(userPrompt.contains("Текущая сводка"))
        assertTrue(userPrompt.contains("Сергей"))
        assertTrue(userPrompt.contains("Новые сообщения"))
    }

    @Test
    fun `summarize trims whitespace and uses last choice content`() = runBlocking {
        val compressor = HistoryCompressor(chat = { _ ->
            fakeResponse("  Сводка с пробелами  \n", tokens = 5)
        })
        val result = compressor.summarize(null, listOf(MessageObj("user", "x")))
        assertEquals("Сводка с пробелами", result.summary)
    }
}
