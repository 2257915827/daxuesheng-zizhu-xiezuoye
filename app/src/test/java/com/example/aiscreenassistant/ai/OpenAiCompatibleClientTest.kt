package com.example.aiscreenassistant.ai

import com.example.aiscreenassistant.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiCompatibleClientTest {
    @Test fun parseChoiceAnswerAndIgnoreUnexpectedActionFields() {
        val parsed = OpenAiCompatibleClient.parseAnswer(
            """```json
                {"questionType":"MULTIPLE_CHOICE","answer":"B. 光合作用","confidence":0.94,"briefReason":"题干描述植物制造养分。","actions":[{"type":"TAP"}]}
            ```""".trimIndent()
        )
        assertEquals(QuestionType.MULTIPLE_CHOICE, parsed.questionType)
        assertEquals("B. 光合作用", parsed.answer)
    }

    @Test fun parseFillInAndShortAnswerTypes() {
        assertEquals(
            QuestionType.FILL_IN,
            OpenAiCompatibleClient.parseAnswer("""{"questionType":"FILL_IN","answer":"蒸发","confidence":0.8}""").questionType
        )
        assertEquals(
            QuestionType.SHORT_ANSWER,
            OpenAiCompatibleClient.parseAnswer("""{"questionType":"SHORT_ANSWER","answer":"答案文本","confidence":0.8}""").questionType
        )
    }

    @Test fun rejectLowConfidenceAndEmptyAnswers() {
        assertThrows(ApiException::class.java) {
            OpenAiCompatibleClient.parseAnswer("""{"questionType":"UNKNOWN","answer":"可能是 A","confidence":0.4}""")
        }
        assertThrows(ApiException::class.java) {
            OpenAiCompatibleClient.parseAnswer("""{"questionType":"UNKNOWN","answer":"","confidence":0.95}""")
        }
    }

    @Test fun rejectMalformedResponse() {
        assertThrows(ApiException::class.java) { OpenAiCompatibleClient.parseAnswer("看不清楚") }
    }
}
