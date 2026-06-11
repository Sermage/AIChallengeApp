package com.example.aichallengeapp.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Retrofit-интерфейс для получения OAuth-токена GigaChat.
 * Базовый URL: https://ngw.devices.sberbank.ru:9443/api/v2/
 */
interface GigaChatAuthApi {

    /**
     * Обменивае�� Authorization Key (Basic) на Bearer access_token.
     * @param authorization "Basic <GIGACHAT_AUTH_KEY>"
     * @param rqUid уникальный UUID запроса (обязательный заголовок Сбера)
     * @param scope область доступа, например GIGACHAT_API_PERS
     */
    @FormUrlEncoded
    @POST("oauth")
    suspend fun getToken(
        @Header("Authorization") authorization: String,
        @Header("RqUID") rqUid: String,
        @Field("scope") scope: String,
    ): TokenResponseObj
}

/**
 * Retrofit-интерфейс для обращения к GigaChat Chat Completions.
 * Базовый URL: https://gigachat.devices.sberbank.ru/api/v1/
 */
interface GigaChatApi {

    @POST("chat/completions")
    suspend fun chat(
        @Header("Authorization") authorization: String,
        @Body body: ChatRequestObj,
    ): ChatResponseObj

    @Multipart
    @POST("files")
    suspend fun uploadFile(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("purpose") purpose: RequestBody,
    ): FileUploadResponseObj
}
