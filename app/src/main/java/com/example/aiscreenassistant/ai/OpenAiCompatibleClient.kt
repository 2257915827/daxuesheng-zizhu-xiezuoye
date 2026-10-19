package com.example.aiscreenassistant.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.aiscreenassistant.model.AiResponse
import com.example.aiscreenassistant.model.ScreenContext
import kotlinx.coroutines.Dispatchers
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

class OpenAiCompatibleClient(baseUrl: String, private val token: String, private val model: String) : AiClient {
    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $token").get().build()
        execute(request) { body -> parseModels(body) }
    }

    suspend fun testConnection(selectedModel: String?): ConnectionResult = withContext(Dispatchers.IO) {
        val models = listModels()
        ConnectionResult(models.size, selectedModel.isNullOrBlank() || selectedModel in models)
    }

    override suspend fun analyzeScreen(bitmap: Bitmap, context: ScreenContext): AiResponse = chat(bitmap, context, "识别屏幕上的当前作业题目和选项。若题目尚未完成，选择一个最佳选项并只返回一个 SELECT_OPTION 动作；target 必须是屏幕中选项的准确文本或可访问节点 id。答案选中后若页面明确有“下一题/继续答题”按钮，可返回 NEXT_QUESTION；长页面可返回 SCROLL。只允许 SELECT_OPTION、NEXT_QUESTION、SCROLL、WAIT，绝不可提交、发送、输入文本、点击提交或执行其他操作。若已没有未完成题目，taskComplete=true。只输出符合 AiResponse JSON 结构的 JSON：explanation、answer、confidence、taskComplete、actions；每个动作含 type、target、reason、confidence。")
    override suspend fun createActionPlan(bitmap: Bitmap, context: ScreenContext, answer: AiResponse): AiResponse = chat(bitmap, context, "基于当前页面给出一个安全的选择题选项动作。不得提交。已有答案：${answer.answer ?: answer.explanation}")
    override suspend fun verifyAction(bitmap: Bitmap, context: ScreenContext, expected: String): Boolean = chat(bitmap, context, "判断页面是否已达到预期：$expected。只回复 true 或 false。").explanation.trim().contains("true", ignoreCase = true)

    private suspend fun chat(bitmap: Bitmap, context: ScreenContext, instruction: String): AiResponse = withContext(Dispatchers.IO) {
        val image = bitmapToDataUrl(bitmap)
        val prompt = "$instruction\n界面包名：${context.packageName}\n可访问节点：${context.nodes.joinToString(limit = 80)}"
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model.ifBlank { throw ApiException("未选择模型") }))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(prompt)) })
                        add(buildJsonObject { put("type", JsonPrimitive("image_url")); put("image_url", buildJsonObject { put("url", JsonPrimitive(image)) }) })
                    })
                })
            })
        }
        val request = Request.Builder().url("$baseUrl/chat/completions").header("Authorization", "Bearer $token")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            execute(request) { body ->
                val root = json.parseToJsonElement(body).jsonObject
                val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
                val text = when (content) { is JsonPrimitive -> content.content; is JsonArray -> content.joinToString("\n") { part -> runCatching { part.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull() ?: part.toString() }; else -> throw ApiException("模型未返回文本内容") }
                val jsonStart = text.indexOf('{')
                val jsonEnd = text.lastIndexOf('}')
                if (jsonStart < 0 || jsonEnd <= jsonStart) throw ApiException("模型响应不是预期的 JSON 操作格式")
                runCatching { json.decodeFromString(AiResponse.serializer(), text.substring(jsonStart, jsonEnd + 1)) }
                    .getOrElse { throw ApiException("模型 JSON 结构无效：${it.message}") }
            }
        } finally { bitmap.recycle() }
    }

    private fun <T> execute(request: Request, parser: (String) -> T): T = http.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException("HTTP ${response.code}：${parseError(body)}")
        if (body.isBlank()) throw ApiException("服务返回空响应")
        parser(body)
    }

    private fun parseError(body: String): String = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: body }.getOrDefault(body).take(300)
    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        return "data:image/jpeg;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
    }

    companion object {
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
    }
}

data class ConnectionResult(val modelCount: Int, val selectedModelAvailable: Boolean)
