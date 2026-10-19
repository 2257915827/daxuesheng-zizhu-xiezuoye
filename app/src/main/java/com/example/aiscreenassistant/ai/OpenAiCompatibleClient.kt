package com.example.aiscreenassistant.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.aiscreenassistant.model.QuestionAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String) : Exception(message)

class OpenAiCompatibleClient(baseUrl: String, private val token: String, private val model: String) {
    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $token").get().build()
        execute(request) { parseModels(it) }
    }

    suspend fun testConnection(selectedModel: String?): ConnectionResult = withContext(Dispatchers.IO) {
        val models = listModels()
        ConnectionResult(models.size, selectedModel.isNullOrBlank() || selectedModel in models)
    }

    suspend fun answerQuestion(bitmap: Bitmap): QuestionAnswer = withContext(Dispatchers.IO) {
        try {
            val image = bitmapToDataUrl(bitmap)
            val payload = buildJsonObject {
                put("model", JsonPrimitive(model.ifBlank { throw ApiException("未选择模型") }))
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive(SYSTEM_PROMPT))
                    })
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", JsonPrimitive("image_url")); put("image_url", buildJsonObject { put("url", JsonPrimitive(image)) }) })
                        })
                    })
                })
                put("max_completion_tokens", JsonPrimitive(256))
            }
            val request = Request.Builder().url("$baseUrl/chat/completions").header("Authorization", "Bearer $token")
                .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            execute(request) { body ->
                val root = json.parseToJsonElement(body).jsonObject
                val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
                val text = when (content) {
                    is JsonPrimitive -> content.content
                    is JsonArray -> content.joinToString("\n") { part -> runCatching { part.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull().orEmpty() }
                    else -> throw ApiException("模型未返回答案内容")
                }
                val answer = parseAnswer(text)
                val cachedTokens = root["usage"]?.jsonObject?.get("prompt_tokens_details")?.jsonObject
                    ?.get("cached_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                answer.copy(cacheHitTokens = cachedTokens)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun <T> execute(request: Request, parser: (String) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw ApiException("HTTP ${it.code}：${parseError(body)}")
                        if (body.isBlank()) throw ApiException("服务返回空响应")
                        parser(body)
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private fun parseError(body: String): String = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: body }.getOrDefault(body).take(300)
    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
        return "data:image/jpeg;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
    }

    companion object {
        const val SYSTEM_PROMPT = "你是屏幕题目答案助手。分析截图中当前最主要的一道题，只输出 JSON：{\"questionType\":\"MULTIPLE_CHOICE|FILL_IN|SHORT_ANSWER|UNKNOWN\",\"answer\":\"最终答案\",\"confidence\":0.0,\"briefReason\":\"\"}，confidence 必须是 0 到 1 之间的数字。只给最终答案，不要解释、推导或描述原理；不要操作屏幕或返回操作指令。选择题答案需包含选项标号和内容。若题干可判断且截图可见选项不完整、正确答案确定不在可见选项中，不要从不完整选项中猜选，answer 写“正确答案未显示在截图可见选项中”，并给出较高置信度；若无法判断题目或正确答案则 answer 留空并降低置信度。填空题和简答题给出可直接参考或复制的完整答案。"
        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            if (trimmed.isBlank()) throw ApiException("请输入 API 地址")
            if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) throw ApiException("API 地址必须以 http:// 或 https:// 开头")
            return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
        }
        fun parseModels(body: String): List<String> {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
            return root["data"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.distinct().sorted()
        }
        fun parseAnswer(content: String): QuestionAnswer {
            val from = content.indexOf('{')
            val to = content.lastIndexOf('}')
            if (from < 0 || to <= from) throw ApiException("模型响应不是预期的答案 JSON")
            val answer = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(QuestionAnswer.serializer(), content.substring(from, to + 1)) }
                .getOrElse { throw ApiException("答案结构无效：${it.message}") }
            if (answer.answer.isBlank()) throw ApiException("没有识别到可靠答案，请调整屏幕后重试")
            if (answer.confidence !in 0f..1f || answer.confidence < .55f) throw ApiException("识别置信度不足，请检查题目是否完整清晰后重试")
            return answer
        }
    }
}

data class ConnectionResult(val modelCount: Int, val selectedModelAvailable: Boolean)
