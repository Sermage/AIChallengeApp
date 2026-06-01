package com.example.aichallengeapp.data

import android.util.Log
import com.example.aichallengeapp.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
    suspend fun chat(messages: List<MessageObj>): ChatResponseObj {
        val token = getValidToken()
        return chatApi.chat(
            authorization = "Bearer $token",
            body = ChatRequestObj(model = MODEL, messages = messages),
        )
    }
}
