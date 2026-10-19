package com.example.aiscreenassistant.model

import kotlinx.serialization.Serializable

@Serializable
enum class QuestionType { MULTIPLE_CHOICE, FILL_IN, SHORT_ANSWER, UNKNOWN }

@Serializable
data class QuestionAnswer(
    val questionType: QuestionType = QuestionType.UNKNOWN,
    val answer: String = "",
    val confidence: Float = 0f,
    val briefReason: String = "",
    val cacheHitTokens: Int = 0
)
