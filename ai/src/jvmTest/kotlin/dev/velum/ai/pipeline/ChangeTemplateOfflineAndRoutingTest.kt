package dev.velum.ai.pipeline

import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.engine.AudioInput
import dev.velum.ai.engine.DiarizationResult
import dev.velum.ai.engine.Diarizer
import dev.velum.ai.engine.JobProgress
import dev.velum.ai.engine.LlmEngine
import dev.velum.ai.engine.LlmRequest
import dev.velum.ai.engine.LlmResult
import dev.velum.ai.engine.Transcriber
import dev.velum.ai.engine.TranscriptionResult
import dev.velum.ai.openai.OpenAiLlmEngine
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.AiApiTask
import dev.velum.core.settings.ApiEndpointProfile
import dev.velum.core.settings.SettingsRepository
import dev.velum.core.settings.TaskModelBinding
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * #103: change-template regeneration with the real [OpenAiLlmEngine] behind a recording HTTP engine.
 * (a) Local summary mode produces the new summary while any network call would fail the test.
 * (b) API summary mode sends exactly the configured Summary profile (host + key) and model.
 */
class ChangeTemplateOfflineAndRoutingTest {
    private val transcript = "Speaker 1: we agreed to ship on Friday.\nSpeaker 2: I will own the release notes."

    private class SeenCall(val url: String, val bearer: String?, val model: String?, val system: String?)

    /** Records every HTTP call; [offline] makes each one fail like a dead network. */
    private class Network(val offline: Boolean = false, val status: HttpStatusCode = HttpStatusCode.OK) {
        val calls = mutableListOf<SeenCall>()
        val engine = MockEngine { request ->
            val text = when (val body = request.body) {
                is TextContent -> body.text
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                else -> ""
            }
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            calls += SeenCall(
                url = request.url.toString(),
                bearer = request.headers[HttpHeaders.Authorization],
                model = json?.get("model")?.jsonPrimitive?.content,
                system = json?.get("messages")?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content,
            )
            if (offline) throw java.io.IOException("network is disabled in this test")
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"## api summary"}}]}""",
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = OpenAiCompatibleClient(HttpClient(engine) { install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) } })
    }

    private class LocalLlm : LlmEngine {
        override val engineId = "local"
        val requests = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest, modelId: String): LlmResult {
            requests += request
            return LlmResult(text = "## local\n${request.systemPrompt.hashCode()}", engineId = engineId, modelId = modelId)
        }
    }

    private val noopAsr = object : Transcriber {
        override val engineId = "noop-asr"
        override suspend fun transcribe(input: AudioInput, modelId: String): TranscriptionResult = error("ASR must not run")
    }
    private val noopDiar = object : Diarizer {
        override val engineId = "noop-diar"
        override suspend fun diarize(input: AudioInput, modelId: String): DiarizationResult = error("Diarization must not run")
    }

    private class Rig(val repo: VelumRepository, val settings: SettingsRepository, val processor: RecordingProcessor, val local: LocalLlm)

    private suspend fun rig(net: Network, configure: suspend SettingsRepository.() -> Unit): Rig {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setTranscriptionMode(ComputeMode.Local)
            it.setDiarizationMode(ComputeMode.Local)
            it.configure()
        }
        val local = LocalLlm()
        val router = AiTaskRouter(
            settings = settings.settings, // the live flow, so mode and model changes are seen
            localTranscriber = noopAsr, apiTranscriber = noopAsr,
            localDiarizer = noopDiar, apiDiarizer = noopDiar,
            localLlm = local, apiLlm = OpenAiLlmEngine(net.client, settings),
        )
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = transcript, summaryMarkdown = null, status = "ready",
        )
        return Rig(repo, settings, RecordingProcessor(router, repo, settings), local)
    }

    private suspend fun Rig.change(templateId: String) = processor.process("rec", templateId, retranscribe = false).last()

    @Test
    fun local_regeneration_succeeds_with_the_network_down_and_never_calls_the_api() = runBlocking<Unit> {
        val net = Network(offline = true)
        // An API key and profile exist, as on a real install, so a silent API fallback would be possible.
        val r = rig(net) { setSummaryMode(ComputeMode.Local); setApiKey("sk-test-not-real") }

        assertIs<JobProgress.Succeeded>(r.change("velum.meeting"))
        val first = r.repo.getRecording("rec")!!.summary_markdown!!
        assertIs<JobProgress.Succeeded>(r.change("velum.sales"))
        val second = r.repo.getRecording("rec")!!.summary_markdown!!

        assertEquals(0, net.calls.size, "local mode must not touch the network: ${net.calls.map { it.url }}")
        assertNotEquals(first, second)
        assertTrue(r.local.requests.last().userPrompt.contains("release notes"), "existing transcript is the input")
        assertNotEquals(r.local.requests[0].systemPrompt, r.local.requests[1].systemPrompt, "selected template drives the prompt")
        assertEquals(setOf("velum.meeting", "velum.sales"), r.repo.getSummaryVersions("rec").map { it.template_id }.toSet())
    }

    @Test
    fun switching_to_api_while_offline_fails_and_keeps_the_local_summary() = runBlocking<Unit> {
        val net = Network(offline = true)
        val r = rig(net) { setSummaryMode(ComputeMode.Local); setApiKey("sk-test-not-real") }
        assertIs<JobProgress.Succeeded>(r.change("velum.meeting"))
        val before = r.repo.getRecording("rec")!!.summary_markdown

        r.settings.setSummaryMode(ComputeMode.Api)
        assertIs<JobProgress.Failed>(r.change("velum.sales"))

        assertEquals(1, net.calls.size, "the API route was attempted, and only once")
        assertEquals(before, r.repo.getRecording("rec")!!.summary_markdown, "prior summary kept")
        assertEquals(1, r.repo.getSummaryVersions("rec").size)
        assertTrue(r.repo.getProcessingError("rec").orEmpty().isNotBlank(), "failure is recorded for the UI")
    }

    @Test
    fun api_regeneration_uses_the_configured_summary_profile_key_and_model() = runBlocking<Unit> {
        val net = Network()
        val r = rig(net) {
            upsertProfile(ApiEndpointProfile("p-other", "Other host", "https://other.example.test/v1"))
            upsertProfile(ApiEndpointProfile("p-summary", "Summary host", "https://summary.example.test/v1"))
            setProfileApiKey("p-other", "other-key")
            setProfileApiKey("p-summary", "summary-key")
            setActiveProfile("p-other") // the active profile is NOT the Summary one
            setTaskBinding(AiApiTask.Ask, TaskModelBinding("p-other", "ask-model"))
            setTaskBinding(AiApiTask.Summary, TaskModelBinding("p-summary", "summary-model-x"))
            setSummaryMode(ComputeMode.Api)
        }

        assertIs<JobProgress.Succeeded>(r.change("velum.meeting"))
        assertIs<JobProgress.Succeeded>(r.change("velum.sales"))

        assertEquals(2, net.calls.size)
        net.calls.forEach { call ->
            assertEquals("https://summary.example.test/v1/chat/completions", call.url)
            assertEquals("Bearer summary-key", call.bearer)
            assertEquals("summary-model-x", call.model)
        }
        assertNotEquals(net.calls[0].system, net.calls[1].system, "selected template drives the API prompt")
        assertEquals(0, r.local.requests.size, "API mode must not run the local engine")
        assertEquals("## api summary", r.repo.getRecording("rec")!!.summary_markdown!!.lines().first { it.isNotBlank() })
    }

    @Test
    fun changing_the_summary_binding_changes_what_the_next_regeneration_sends() = runBlocking<Unit> {
        val net = Network()
        val r = rig(net) {
            upsertProfile(ApiEndpointProfile("p-a", "A", "https://a.example.test/v1"))
            upsertProfile(ApiEndpointProfile("p-b", "B", "https://b.example.test/v1"))
            setProfileApiKey("p-a", "key-a")
            setProfileApiKey("p-b", "key-b")
            setTaskBinding(AiApiTask.Summary, TaskModelBinding("p-a", "model-a"))
            setSummaryMode(ComputeMode.Api)
        }
        assertIs<JobProgress.Succeeded>(r.change("velum.meeting"))
        r.settings.setTaskBinding(AiApiTask.Summary, TaskModelBinding("p-b", "model-b"))
        assertIs<JobProgress.Succeeded>(r.change("velum.sales"))

        assertEquals(
            listOf("https://a.example.test/v1/chat/completions" to "model-a", "https://b.example.test/v1/chat/completions" to "model-b"),
            net.calls.map { it.url to it.model },
        )
        assertEquals(listOf("Bearer key-a", "Bearer key-b"), net.calls.map { it.bearer })
    }

    @Test
    fun api_server_error_keeps_the_previous_summary_and_reports_it() = runBlocking<Unit> {
        val net = Network(status = HttpStatusCode.ServiceUnavailable)
        val r = rig(net) { setApiKey("sk-test-not-real"); setSummaryMode(ComputeMode.Api) }

        assertIs<JobProgress.Failed>(r.change("velum.meeting"))

        assertEquals(null, r.repo.getRecording("rec")!!.summary_markdown)
        assertEquals(0, r.repo.getSummaryVersions("rec").size)
        assertTrue(r.repo.getProcessingError("rec").orEmpty().isNotBlank())
    }
}
