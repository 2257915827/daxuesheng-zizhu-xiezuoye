package com.example.aiscreenassistant.ai

import android.graphics.Bitmap
import com.example.aiscreenassistant.model.*
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Provider-neutral HTTP adapter. The server must not persist uploaded images. */
class HttpAiClient(private val baseUrl: String, private val token: String? = null) : AiClient {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    override suspend fun analyzeScreen(bitmap: Bitmap, context: ScreenContext) = call("analyzeScreen", bitmap, context, null)
    override suspend fun createActionPlan(bitmap: Bitmap, context: ScreenContext, answer: AiResponse) = call("createActionPlan", bitmap, context, answer)
    override suspend fun verifyAction(bitmap: Bitmap, context: ScreenContext, expected: String): Boolean = call("verifyAction", bitmap, context, null).confidence >= .65f

    private fun call(path: String, bitmap: Bitmap, context: ScreenContext, previous: AiResponse?): AiResponse {
        val temp = File.createTempFile("ai-screen-", ".png")
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, temp.outputStream())
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("context", json.encodeToString(ScreenContext.serializer(), context))
                .addFormDataPart("previous", previous?.let { json.encodeToString(AiResponse.serializer(), it) } ?: "")
                .addFormDataPart("image", temp.name, temp.asRequestBody("image/png".toMediaType())).build()
            val request = Request.Builder().url("${baseUrl.trimEnd('/')}/$path").post(body).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "AI 服务返回 ${response.code}" }
                return json.decodeFromString(AiResponse.serializer(), response.body?.string() ?: error("AI 响应为空"))
            }
        } finally { temp.delete(); bitmap.recycle() }
    }
}
