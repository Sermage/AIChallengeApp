package com.example.aichallengeapp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Тело запроса к GigaChat Chat Completions API. */
@Serializable
data class ChatRequestObj(
    val model: String,
    val messages: List<MessageObj>,
)

/** Одно сообщение в диалоге (role: "user" / "assistant" / "system"). */
@Serializable
data class MessageObj(
    val role: String,
    val content: String,
)

/** Тело ответа от GigaChat. */
@Serializable
data class ChatResponseObj(
    val choices: List<ChoiceObj>,
)

/** Один вариант ответа от модели. */
@Serializable
data class ChoiceObj(
    val message: MessageObj,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** Ответ OAuth-сервера Сбера с токеном доступ��. */
@Serializable
data class TokenResponseObj(
    @SerialName("access_token") val accessToken: String,
    /** Время истечения в миллисекундах (Unix timestamp ms). */
    @SerialName("expires_at") val expiresAt: Long,
)
