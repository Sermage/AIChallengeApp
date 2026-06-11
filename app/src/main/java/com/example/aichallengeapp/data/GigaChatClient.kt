package com.example.aichallengeapp.data

import android.util.Log
import com.example.aichallengeapp.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Синглтон для работы с GigaChat API. Управляет OAuth-токеном с кешированием. */
object GigaChatClient {

    // Модель по умолчанию
    const val MODEL = "GigaChat"

    // Область доступа для личного кабинета
    private const val SCOPE = "GIGACHAT_API_PERS"

    // Буфер за 60 секунд до истечения токена — обновляем заранее
    private const val TOKEN_EXPIRY_BUFFER_MS = 60_000L

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * ВНИМАНИЕ: TrustManager принимает любые сертификаты.
     * Используется только для разработки, так как GigaChat использует
     * сертификат российского удостоверяющего центра, не входящего в
     * стандартное хранилище Android.
     * В продакшене необходимо добавить сертификат Сбера в Network Security Config.
     */
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    /** Общий OkHttpClient с логированием и отключённой проверкой SSL (dev-режим). */
    private fun buildOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            },
        )
        .build()

    // Retrofit для получения токена (отдельный хост Сбера)
    private val authApi: GigaChatAuthApi = Retrofit.Builder()
        .baseUrl("https://ngw.devices.sberbank.ru:9443/api/v2/")
        .client(buildOkHttp())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GigaChatAuthApi::class.java)

    // Retrofit для Chat Completions
    private val chatApi: GigaChatApi = Retrofit.Builder()
        .baseUrl("https://gigachat.devices.sberbank.ru/api/v1/")
        .client(buildOkHttp())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GigaChatApi::class.java)

    // Кешированный токен и время его истечения
    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0L

    /**
     * Возвращает действующий Bearer-токен.
     * Если токен отсутствует или скоро истечёт — запрашивает новый.
     */
    private suspend fun getValidToken(): String {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAt - TOKEN_EXPIRY_BUFFER_MS) {
            return cachedToken!!
        }

        Log.d("GigaChat", "Получаем новый OAuth-токен…")
        val response = authApi.getToken(
            authorization = "Basic ${BuildConfig.GIGACHAT_AUTH_KEY}",
            rqUid = UUID.randomUUID().toString(),
            scope = SCOPE,
        )
        cachedToken = response.accessToken
        tokenExpiresAt = response.expiresAt
        Log.d("GigaChat", "Токен получен, истекает в $tokenExpiresAt")
        return response.accessToken
    }

    /**
     * Отправляет [messages] в GigaChat и возвращает ответ модели.
     * Автоматически обновляет токен при необходимости.
     */
    suspend fun chat(
        messages: List<MessageObj>,
        maxTokens: Int? = null,
        stopSequences: List<String>? = null,
        temperature: Double? = null,
        model: String = MODEL,
    ): ChatResponseObj {
        val token = getValidToken()
        Log.d("GigaChat", "=== ЗАПРОС ===")
        Log.d("GigaChat", "model=$model, temperature=$temperature, maxTokens=$maxTokens")
        messages.forEachIndexed { i, m ->
            Log.d(
                "GigaChat",
                "msg[$i] role=${m.role}: ${m.content.take(200)}"
            )
        }

        val response = chatApi.chat(
            authorization = "Bearer $token",
            body = ChatRequestObj(
                model = model,
                messages = messages,
                maxTokens = maxTokens,
                stop = stopSequences,
                temperature = temperature,
            ),
        )

        val responseText = response.choices.firstOrNull()?.message?.content.orEmpty()
        Log.d("GigaChat", "=== ОТВЕТ ===")
        responseText.chunked(800).forEach { Log.d("GigaChat", it) }
        Log.d("GigaChat", "=== КОНЕЦ ===")

        return response
    }

    /**
     * Загружает файл (изображение или PDF) в GigaChat Files API
     * и возвращает ID файла для последующей передачи в чат.
     */
    suspend fun uploadFile(bytes: ByteArray, filename: String, mimeType: String): String {
        val token = getValidToken()
        val fileBody = bytes.toRequestBody(
            mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        )
        val filePart = MultipartBody.Part.createFormData("file", filename, fileBody)
        val purposePart = "general".toRequestBody("text/plain".toMediaType())
        Log.d("GigaChat", "Загружаем файл: $filename ($mimeType, ${bytes.size} байт)")
        val response = chatApi.uploadFile("Bearer $token", filePart, purposePart)
        Log.d("GigaChat", "Файл загружен: id=${response.id}")
        return response.id
    }
}
