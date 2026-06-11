package com.example.aichallengeapp.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Тело запроса к GigaChat Chat Completions API. */
@Serializable
data class ChatRequestObj(
    val model: String,
    val messages: List<MessageObj>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null,
)

/** Одно сообщение в диалоге (role: "user" / "assistant" / "system"). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageObj(
    val role: String,
    val content: String,
    /** ID файлов, загруженных через Files API (GigaChat-специфичное поле). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val attachments: List<String>? = null,
)

/** Тело ответа от GigaChat. */
@Serializable
data class ChatResponseObj(
    val choices: List<ChoiceObj>,
    val usage: UsageObj? = null,
)

/** Один вариант ответа от модели. */
@Serializable
data class ChoiceObj(
    val message: MessageObj,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** Статистика токенов. */
@Serializable
data class UsageObj(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

/** Ответ GigaChat Files API после загрузки файла. */
@Serializable
data class FileUploadResponseObj(
    val id: String,
    val filename: String = "",
    val bytes: Long = 0,
)

/** Ответ OAuth-сервера Сбера с токеном доступ��. */
@Serializable
data class TokenResponseObj(
    @SerialName("access_token") val accessToken: String,
    /** Время истечения в миллисекундах (Unix timestamp ms). */
    @SerialName("expires_at") val expiresAt: Long,
)
