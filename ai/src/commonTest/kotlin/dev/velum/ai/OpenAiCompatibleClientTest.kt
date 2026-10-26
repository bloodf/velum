package dev.velum.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OpenAiCompatibleClientTest {
    @Test
    fun listModels_parsesOpenAiShape() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().endsWith("/models"))
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"data":[{"id":"gpt-4o-mini","owned_by":"openai"},{"id":"whisper-1","owned_by":"openai"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiCompatibleClient(httpClient = mockClient(engine))
        val models = client.listModels("https://api.openai.com/v1", "test-key")
        assertEquals(listOf("gpt-4o-mini", "whisper-1"), models.map { it.id })
    }

    @Test
    fun chatCompletion_returnsContent() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"Hello notes"}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiCompatibleClient(httpClient = mockClient(engine))
        val text = client.chatCompletion(
            baseUrl = "http://localhost/v1",
            apiKey = "k",
            model = "gpt-test",
            messages = listOf(ChatMessage("user", "Summarize")),
        )
        assertEquals("Hello notes", text)
    }

    @Test
    fun joinUrl_trimsSlashes() {
        assertEquals(
            "https://example.com/v1/models",
            OpenAiCompatibleClient.joinUrl("https://example.com/v1/", "/models"),
        )
    }

    private fun mockClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) }
    }
}
