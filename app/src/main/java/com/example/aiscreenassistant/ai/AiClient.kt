package com.example.aiscreenassistant.ai

import android.graphics.Bitmap
import com.example.aiscreenassistant.model.*

interface AiClient {
    suspend fun analyzeScreen(bitmap: Bitmap, context: ScreenContext): AiResponse
    suspend fun createActionPlan(bitmap: Bitmap, context: ScreenContext, answer: AiResponse): AiResponse
    suspend fun verifyAction(bitmap: Bitmap, context: ScreenContext, expected: String): Boolean
}

class UnconfiguredAiClient : AiClient {
    override suspend fun analyzeScreen(bitmap: Bitmap, context: ScreenContext) = AiResponse(explanation = "尚未配置 AI 服务，请先设置 API。")
    override suspend fun createActionPlan(bitmap: Bitmap, context: ScreenContext, answer: AiResponse) = AiResponse(explanation = "AI 服务未配置。")
    override suspend fun verifyAction(bitmap: Bitmap, context: ScreenContext, expected: String) = false
}
