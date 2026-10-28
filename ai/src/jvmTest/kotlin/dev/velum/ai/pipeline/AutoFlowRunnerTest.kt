package dev.velum.ai.pipeline

import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.ai.engine.AiTaskRouter
import dev.velum.ai.local.LocalDiarizer
import dev.velum.ai.local.LocalLlmEngine
import dev.velum.ai.local.LocalTranscriber
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.VoiceProfileStore
import dev.velum.ai.local.createLocalModelManager
import dev.velum.ai.openai.OpenAiDiarizer
import dev.velum.ai.openai.OpenAiLlmEngine
import dev.velum.ai.openai.OpenAiTranscriber
import dev.velum.core.autoflow.AutoFlowPostAction
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AutoFlowRunnerTest {
    @Test
    fun isStableNonMatch_onlyTerminalStatuses() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        assertTrue(AutoFlowRunner.isStableNonMatch(insert(repo, "ready", 10)))
        assertTrue(AutoFlowRunner.isStableNonMatch(insert(repo, "failed", 10)))
        assertFalse(AutoFlowRunner.isStableNonMatch(insert(repo, "recording", 0)))
        assertFalse(AutoFlowRunner.isStableNonMatch(insert(repo, "imported", 0)))
    }

    @Test
    fun incompleteDuration_thenMatch_runsOnceAndPostsOnReady() = runBlocking {
        val (repo, processor) = harness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var processCalls = 0
        val scheduler = RecordingJobScheduler { id, _, _, _ ->
            processCalls++
            kotlinx.coroutines.runBlocking {
                repo.updateStatus(id, "generating")
                repo.updateStatus(id, "ready")
                repo.updateSummary(id, "## Note\nok")
            }
        }
        val runner = AutoFlowRunner(
            repository = repo,
            processor = processor,
            scope = scope,
            jobScheduler = scheduler,
        )
        runner.start()
        delay(200)
        // Subscribe before the match so a buffered emit cannot be missed under load.
        val webhook = async {
            runner.actions.first { it is AutoFlowUiAction.PostWebhook }
        }

        // Disable seed default flow; use a strict duration floor.
        repo.setAutoflowEnabled("flow-default", false)
        repo.upsertAutoflow(
            id = "flow-1",
            name = "Note Pro",
            enabled = true,
            wakePhrase = null,
            durationMinSeconds = 5,
            durationMaxSeconds = 3600,
            sourceFilter = "note_pro",
            templateId = null,
            autoLabelVoice = true,
            language = "auto",
            notify = true,
            postAction = AutoFlowPostAction.Webhook.stored,
        )

        repo.insertRecording(
            id = "rec-af-1",
            sessionId = 1,
            folderId = null,
            title = "Incomplete",
            source = "note_pro",
            deviceSerial = null,
            startedAt = 1_700_000_000L,
            durationSeconds = 0,
            sizeBytes = 0,
            localPath = null,
            transcript = null,
            summaryMarkdown = null,
            status = "recording",
        )
        delay(400)
        assertEquals(0, processCalls, "duration=0 must not match durationMin=5")

        repo.updateLocalPath("rec-af-1", "/tmp/af.wav", durationSeconds = 12, sizeBytes = 100)
        repo.updateStatus("rec-af-1", "imported")

        withTimeout(15_000) {
            while (processCalls < 1 || repo.getRecording("rec-af-1")?.status != "ready") {
                delay(50)
            }
        }
        assertEquals(1, processCalls)
        assertEquals("ready", repo.getRecording("rec-af-1")?.status)

        val action = withTimeout(15_000) { webhook.await() }
        assertEquals("rec-af-1", (action as AutoFlowUiAction.PostWebhook).recordingId)
        runner.stop()
    }

    @Test
    fun failedProcessing_doesNotFirePostAction() = runBlocking {
        val (repo, processor) = harness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scheduler = RecordingJobScheduler { id, _, _, _ ->
            kotlinx.coroutines.runBlocking {
                repo.updateStatus(id, "generating")
                repo.updateStatus(id, "failed")
            }
        }
        val runner = AutoFlowRunner(
            repository = repo,
            processor = processor,
            scope = scope,
            jobScheduler = scheduler,
        )
        runner.start()
        delay(200)
        repo.setAutoflowEnabled("flow-default", false)
        repo.upsertAutoflow(
            id = "flow-fail",
            name = "All",
            enabled = true,
            wakePhrase = null,
            durationMinSeconds = 0,
            durationMaxSeconds = 3600,
            sourceFilter = "all",
            templateId = null,
            autoLabelVoice = false,
            language = "auto",
            notify = true,
            postAction = AutoFlowPostAction.Webhook.stored,
        )
        repo.insertRecording(
            id = "rec-fail",
            sessionId = null,
            folderId = null,
            title = "Fail",
            source = "phone",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 10,
            sizeBytes = 1,
            localPath = "/tmp/x.wav",
            transcript = null,
            summaryMarkdown = null,
            status = "imported",
        )
        withTimeout(10_000) {
            while (repo.getRecording("rec-fail")?.status != "failed") delay(50)
        }
        val emitted = mutableListOf<AutoFlowUiAction>()
        val job = scope.launch { runner.actions.collect { emitted += it } }
        delay(500)
        job.cancel()
        assertTrue(emitted.none { it is AutoFlowUiAction.PostWebhook })
        runner.stop()
    }

    private suspend fun insert(
        repo: VelumRepository,
        status: String,
        duration: Long,
    ) = run {
        val id = "stub-$status-$duration-${kotlin.random.Random.nextInt()}"
        repo.insertRecording(
            id = id,
            sessionId = null,
            folderId = null,
            title = "t",
            source = "phone",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = duration,
            sizeBytes = 0,
            localPath = null,
            transcript = null,
            summaryMarkdown = null,
            status = status,
        )
        repo.getRecording(id)!!
    }

    private fun harness(): Pair<VelumRepository, RecordingProcessor> {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        val settings = SettingsRepository(InMemorySecureStore()).also {
            runBlocking { it.load() }
        }
        val modelRoot = ModelStoreDir(
            File.createTempFile("velum-af", "").also { it.delete(); it.mkdirs() }.absolutePath,
        )
        val modelManager = createLocalModelManager(storeRoot = modelRoot)
        val voices = VoiceProfileStore(modelRoot)
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) {
            install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) }
        }
        val aiClient = OpenAiCompatibleClient(http)
        val router = AiTaskRouter(
            settings = settings.settings,
            localTranscriber = LocalTranscriber(modelManager),
            apiTranscriber = OpenAiTranscriber(aiClient, settings),
            localDiarizer = LocalDiarizer(modelManager, voices),
            apiDiarizer = OpenAiDiarizer(),
            localLlm = LocalLlmEngine(modelManager),
            apiLlm = OpenAiLlmEngine(aiClient, settings),
        )
        return repo to RecordingProcessor(router, repo, settings)
    }
}
