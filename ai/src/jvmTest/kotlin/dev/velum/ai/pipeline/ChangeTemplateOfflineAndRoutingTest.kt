package dev.velum.ai.pipeline

import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.ai.engine.AiTask
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
import dev.velum.ai.engine.DEFAULT_LOCAL_LLM_MODEL_ID
import dev.velum.ai.engine.ModeResolution
import dev.velum.ai.engine.resolveModeOrPrompt
import dev.velum.ai.local.LOCAL_MODEL_NOT_READY_MESSAGE
import dev.velum.ai.local.LocalLlmEngine
import dev.velum.ai.local.LocalModelKind
import dev.velum.ai.local.LocalModelManager
import dev.velum.ai.local.LocalModelSpec
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.openai.OpenAiLlmEngine
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.velum.core.db.VelumDatabase
import java.io.File
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
        val r = rig(net) { setApiKey("sk-test-not-real"); setSummaryMode(ComputeMode.Local) }
        assertIs<JobProgress.Succeeded>(r.change("velum.meeting"))
        val before = r.repo.getRecording("rec")!!.summary_markdown

        r.settings.setSummaryMode(ComputeMode.Api)
        assertIs<JobProgress.Failed>(r.change("velum.sales"))

        assertEquals(1, net.calls.size, "the API was tried once")
        assertEquals(before, r.repo.getRecording("rec")!!.summary_markdown, "prior summary survives a 503")
        val versions = r.repo.getSummaryVersions("rec")
        assertEquals(listOf("velum.meeting"), versions.map { it.template_id })
        assertEquals(1L, versions.single().is_active)
        assertTrue(r.repo.getProcessingError("rec").orEmpty().isNotBlank())
    }

    /** Local engine that works first and then behaves like a device without the model. */
    private class FlakyLocal(val missing: LlmEngine) : LlmEngine {
        override val engineId = "flaky-local"
        var modelPresent = true
        override suspend fun complete(request: LlmRequest, modelId: String): LlmResult =
            if (modelPresent) LlmResult("## local ok", engineId, modelId) else missing.complete(request, modelId)
    }

    private fun emptyModelManager(): LocalModelManager {
        val dir = File.createTempFile("velum-ct103", "").also { it.delete(); it.mkdirs() }
        return LocalModelManager(
            storeRoot = ModelStoreDir(dir.absolutePath),
            httpClient = HttpClient(MockEngine { respond("") }),
            catalog = listOf(
                LocalModelSpec(
                    id = DEFAULT_LOCAL_LLM_MODEL_ID, displayName = "Local LLM", kind = LocalModelKind.Llm,
                    approxBytes = 1, license = "test", notes = "", languages = "en", downloadUrl = null,
                ),
            ),
        )
    }

    @Test
    fun missing_local_model_with_an_api_key_prompts_and_never_calls_the_api() = runBlocking<Unit> {
        val net = Network()
        val manager = emptyModelManager()
        val flaky = FlakyLocal(LocalLlmEngine(manager))
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            it.load()
            it.setSummaryMode(ComputeMode.Local)
            it.setApiKey("sk-test-not-real") // an API route exists, but Local is what the user chose
        }
        val router = AiTaskRouter(
            settings = settings.settings,
            localTranscriber = noopAsr, apiTranscriber = noopAsr, localDiarizer = noopDiar, apiDiarizer = noopDiar,
            localLlm = flaky, apiLlm = OpenAiLlmEngine(net.client, settings),
        )
        val processor = RecordingProcessor(router, repo, settings)
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = transcript, summaryMarkdown = null, status = "ready",
        )
        assertIs<JobProgress.Succeeded>(processor.process("rec", "velum.meeting", retranscribe = false).last())
        val before = repo.getRecording("rec")!!.summary_markdown

        // UI gate: the Summary task needs the local model, so the screen shows the download prompt.
        val resolution = resolveModeOrPrompt(AiTask.Summary, settings.settings.value, manager)
        assertIs<ModeResolution.NeedLocalModel>(resolution)
        assertEquals(DEFAULT_LOCAL_LLM_MODEL_ID, resolution.modelId)

        // Even if a run starts anyway, it fails with the actionable message and does not fall back to the API.
        flaky.modelPresent = false
        assertIs<JobProgress.Failed>(processor.process("rec", "velum.sales", retranscribe = false).last())
        assertEquals(0, net.calls.size, "a missing local model must not silently use the API")
        assertEquals(before, repo.getRecording("rec")!!.summary_markdown)
        assertEquals(1, repo.getSummaryVersions("rec").size)
        assertTrue(repo.getProcessingError("rec").orEmpty().contains("Model manager"), "actionable: ${repo.getProcessingError("rec")}")
    }

    @Test
    fun a_summary_with_no_model_selected_uses_whichever_gemma_is_downloaded() = runBlocking<Unit> {
        val dir = File.createTempFile("velum-260", "").also { it.delete(); it.mkdirs() }
        // Not the offered default: a route that hard-codes the default would call this one missing.
        File(dir, "gemma-4-e4b/weights.litertlm").apply { parentFile.mkdirs(); writeBytes(ByteArray(2048)) }
        val manager = LocalModelManager(
            storeRoot = ModelStoreDir(dir.absolutePath),
            httpClient = HttpClient(MockEngine { respond("") }),
            catalog = listOf(
                LocalModelSpec(
                    id = "gemma-4-e4b", displayName = "Local LLM", kind = LocalModelKind.Llm, approxBytes = 1,
                    license = "test", notes = "", languages = "en", downloadUrl = "https://example.test/m", sha256 = dev.velum.ai.local.sha256Hex(ByteArray(2048)),
                    archiveFileName = "weights.litertlm",
                ),
            ),
        )
        manager.awaitInitialization()
        val net = Network()
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also { it.load(); it.setSummaryMode(ComputeMode.Local) }
        val router = AiTaskRouter(
            settings = settings.settings,
            localTranscriber = noopAsr, apiTranscriber = noopAsr, localDiarizer = noopDiar, apiDiarizer = noopDiar,
            localLlm = LocalLlmEngine(manager), apiLlm = OpenAiLlmEngine(net.client, settings),
        )
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = transcript, summaryMarkdown = null, status = "ready",
        )
        RecordingProcessor(router, repo, settings).process("rec", "velum.meeting", retranscribe = false).last()
        // The JVM runtime can't run the model, so it fails later, for its own reason: never "model missing".
        assertTrue(repo.getProcessingError("rec") != LOCAL_MODEL_NOT_READY_MESSAGE, repo.getProcessingError("rec").orEmpty())
    }

    @Test
    fun versions_and_the_active_template_survive_a_cold_restart() = runBlocking<Unit> {
        val file = File.createTempFile("velum-ct103-restart", ".db").also { it.deleteOnExit() }
        file.delete()
        val url = "jdbc:sqlite:${file.absolutePath}"
        val net = Network()
        val handle = createDatabase(DriverFactory(url))
        val repo = VelumRepository(handle)
        val settings = SettingsRepository(InMemorySecureStore()).also { it.load(); it.setSummaryMode(ComputeMode.Local) }
        val local = LocalLlm()
        val router = AiTaskRouter(
            settings = settings.settings,
            localTranscriber = noopAsr, apiTranscriber = noopAsr, localDiarizer = noopDiar, apiDiarizer = noopDiar,
            localLlm = local, apiLlm = OpenAiLlmEngine(net.client, settings),
        )
        val processor = RecordingProcessor(router, repo, settings)
        repo.insertRecording(
            id = "rec", sessionId = null, folderId = null, title = "t", source = "phone", deviceSerial = null,
            startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = transcript, summaryMarkdown = null, status = "ready",
        )
        assertIs<JobProgress.Succeeded>(processor.process("rec", "velum.meeting", retranscribe = false).last())
        val first = repo.getRecording("rec")!!.summary_markdown!!
        assertIs<JobProgress.Succeeded>(processor.process("rec", "velum.sales", retranscribe = false).last())
        val second = repo.getRecording("rec")!!.summary_markdown!!
        handle.driver.close()

        // New process: a fresh driver and repository on the same file, nothing carried in memory.
        val reopened = JdbcSqliteDriver(url)
        try {
            val repo2 = VelumRepository(VelumDatabase(reopened), reopened)
            val rec = repo2.getRecording("rec")!!
            assertEquals(second, rec.summary_markdown)
            assertEquals(transcript, rec.transcript)
            val versions = repo2.getSummaryVersions("rec")
            assertEquals(setOf("velum.meeting", "velum.sales"), versions.map { it.template_id }.toSet())
            assertEquals(first, versions.single { it.template_id == "velum.meeting" }.markdown, "previous summary kept with its template")
            assertEquals(0L, versions.single { it.template_id == "velum.meeting" }.is_active)
            assertEquals("velum.sales", versions.single { it.is_active != 0L }.template_id, "active template survives")
            assertTrue(versions.all { it.template_name.isNotBlank() })
        } finally {
            reopened.close()
        }
    }
}
