package com.example.aiscreenassistant

import com.example.aiscreenassistant.ai.OpenAiCompatibleClient
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiCompatibleClientTest {
    @Test fun normalizesRootAndVersionedUrls() {
        assertEquals("https://example.com/v1", OpenAiCompatibleClient.normalizeBaseUrl("https://example.com/"))
        assertEquals("https://example.com/v1", OpenAiCompatibleClient.normalizeBaseUrl("https://example.com/v1/"))
    }

    @Test fun parsesSortsAndDeduplicatesModels() {
        val models = OpenAiCompatibleClient.parseModels("""{"data":[{"id":"z-model"},{"id":"a-model"},{"id":"z-model"}]}""")
        assertEquals(listOf("a-model", "z-model"), models)
    }
}
