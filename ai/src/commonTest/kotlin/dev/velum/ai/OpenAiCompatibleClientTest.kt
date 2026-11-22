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

    @Test
    fun listModels_unauthorizedThrows401() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":{"message":"Incorrect API key"}}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiCompatibleClient(httpClient = mockClient(engine))
        val error = runCatching {
            client.listModels("https://api.openai.com/v1", "bad-key")
        }.exceptionOrNull()
        assertTrue(error is AiApiException)
        assertEquals(401, (error as AiApiException).status)
        assertTrue(error.message!!.contains("GET /models"))
    }

    @Test
    fun listModels_missingEndpointThrows404() = runTest {
        val engine = MockEngine {
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val client = OpenAiCompatibleClient(httpClient = mockClient(engine))
        val error = runCatching {
            client.listModels("https://example.com/v1", "k")
        }.exceptionOrNull()
        assertTrue(error is AiApiException)
        assertEquals(404, (error as AiApiException).status)
    }

    @Test
    fun transcribe_acceptsWavUploadAndReturnsText() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"text":"hello from wav"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiCompatibleClient(httpClient = mockClient(engine))
        val wav = dev.velum.ai.audio.PcmWav.encode(ByteArray(16) { 1 }, 16_000)
        val result = client.transcribe(
            baseUrl = "https://api.example.test/v1",
            apiKey = "k",
            model = "whisper-1",
            filename = "window.wav",
            audioBytes = wav,
        )
        assertEquals("hello from wav", result.text)
        assertEquals("audio/wav", dev.velum.ai.audio.PcmWav.contentTypeForFilename("window.wav"))
    }

    private fun requestBody(data: io.ktor.client.request.HttpRequestData): String =
        (data.body as io.ktor.http.content.TextContent).text

    @Test
    fun chatCompletion_sendsTheDefaultTemperature() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += requestBody(request)
            respond("""{"choices":[{"message":{"content":"ok"}}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        OpenAiCompatibleClient(mockClient(engine)).chatCompletion("http://localhost/v1", "k", "gpt-4o-mini", listOf(ChatMessage("user", "x")))
        assertTrue(bodies.single().contains("\"temperature\":0.3"), bodies.single())
    }

    @Test
    fun chatCompletion_retriesWithoutTemperatureWhenTheModelRejectsIt() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += requestBody(request)
            if (bodies.size == 1) {
                respond(
                    """{"error":{"message":"Unsupported value: 'temperature' does not support 0.3 with this model. Only the default (1) value is supported.","param":"temperature","code":"unsupported_value"}}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond("""{"choices":[{"message":{"role":"assistant","content":"Reasoned notes"}}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val text = OpenAiCompatibleClient(mockClient(engine)).chatCompletion("http://localhost/v1", "k", "o3-mini", listOf(ChatMessage("user", "x")))
        assertEquals("Reasoned notes", text)
        assertEquals(2, bodies.size)
        assertTrue(!bodies[1].contains("temperature"), bodies[1])
    }

    @Test
    fun chatCompletion_doesNotRetryAnUnrelatedBadRequest() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("""{"error":{"message":"context_length_exceeded"}}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val error = runCatching {
            OpenAiCompatibleClient(mockClient(engine)).chatCompletion("http://localhost/v1", "k", "m", listOf(ChatMessage("user", "x")))
        }.exceptionOrNull()
        assertEquals(1, calls)
        assertEquals(400, (error as AiApiException).status)
    }

    @Test
    fun chatCompletion_nullOrBlankContentIsAnExplicitFailure() = runTest {
        for (reply in listOf(
            """{"choices":[{"message":{"role":"assistant","content":null,"refusal":"I can't help with that."}}]}""",
            """{"choices":[{"message":{"role":"assistant","content":"   "}}]}""",
            """{"choices":[]}""",
        )) {
            val engine = MockEngine { respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val error = runCatching {
                OpenAiCompatibleClient(mockClient(engine)).chatCompletion("http://localhost/v1", "k", "m", listOf(ChatMessage("user", "x")))
            }.exceptionOrNull()
            assertTrue(error is AiApiException, "$reply -> $error")
            assertEquals(EMPTY_CHAT_COMPLETION_MESSAGE, error.message)
        }
    }

    private fun mockClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) }
    }
}
