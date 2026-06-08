package com.example.aichallengeapp.data

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GigaChatRepository @Inject constructor() {

    suspend fun chat(
        messages: List<MessageObj>,
        maxTokens: Int? = null,
        stopSequences: List<String>? = null,
        temperature: Double? = null,
        model: String = "GigaChat",
    ): ChatResponseObj = GigaChatClient.chat(
        messages = messages,
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        temperature = temperature,
        model = model,
    )
}
