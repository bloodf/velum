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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
        val processCalls = AtomicInteger(0)
        val scheduler = RecordingJobScheduler { id, _, _, _ ->
            processCalls.incrementAndGet()
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
        try {
            runner.start()
            awaitPass(runner, atLeast = 1)

            // Attach before any match so a no-replay emit cannot be missed under load.
            val webhook = async(start = CoroutineStart.UNDISPATCHED) {
                runner.actions.first { it is AutoFlowUiAction.PostWebhook }
            }

            // Disable seed default flow; use a strict duration floor.
            awaitNextPass(runner) {
                repo.setAutoflowEnabled("flow-default", false)
            }
            awaitNextPass(runner) {
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
            }

            awaitNextPass(runner) {
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
            }
            assertEquals(0, processCalls.get(), "duration=0 must not match durationMin=5")

            repo.updateLocalPath("rec-af-1", "/tmp/af.wav", durationSeconds = 12, sizeBytes = 100)
            repo.updateStatus("rec-af-1", "imported")

            withTimeout(15_000) {
                while (processCalls.get() < 1 || repo.getRecording("rec-af-1")?.status != "ready") {
                    delay(50)
                }
            }
            assertEquals(1, processCalls.get())
            assertEquals("ready", repo.getRecording("rec-af-1")?.status)

            val action = withTimeout(15_000) { webhook.await() }
            assertEquals("rec-af-1", (action as AutoFlowUiAction.PostWebhook).recordingId)
        } finally {
            runner.stop()
            scope.cancel()
        }
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
        try {
            runner.start()
            awaitPass(runner, atLeast = 1)

            val emitted = java.util.concurrent.ConcurrentLinkedQueue<AutoFlowUiAction>()
            val collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runner.actions.collect { emitted += it }
            }

            awaitNextPass(runner) {
                repo.setAutoflowEnabled("flow-default", false)
            }
            awaitNextPass(runner) {
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
            }
            // Matching insert runs processing; passIndex advances only after the failed terminal.
            awaitNextPass(runner) {
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
            }
            assertEquals("failed", repo.getRecording("rec-fail")?.status)
            collectJob.cancel()
            assertTrue(emitted.none { it is AutoFlowUiAction.PostWebhook })
        } finally {
            runner.stop()
            scope.cancel()
        }
    }

    @Test
    fun terminalReady_afterSeed_isNotRetranscribed() = runBlocking {
        val (repo, processor) = harness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processCalls = AtomicInteger(0)
        val scheduler = RecordingJobScheduler { _, _, retranscribe, _ ->
            processCalls.incrementAndGet()
            error("must not enqueue ready rows (retranscribe=$retranscribe)")
        }
        // Pre-existing ready simulates process death after WorkManager finished.
        repo.insertRecording(
            id = "rec-ready-done",
            sessionId = null,
            folderId = null,
            title = "Already ready",
            source = "note_pro",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 30,
            sizeBytes = 1,
            localPath = "/tmp/r.wav",
            transcript = "old",
            summaryMarkdown = "## old",
            status = "ready",
        )
        repo.setAutoflowEnabled("flow-default", false)
        repo.upsertAutoflow(
            id = "flow-ready-skip",
            name = "Note Pro",
            enabled = true,
            wakePhrase = null,
            durationMinSeconds = 0,
            durationMaxSeconds = 3600,
            sourceFilter = "note_pro",
            templateId = "velum.meeting",
            autoLabelVoice = false,
            language = "auto",
            notify = true,
            postAction = AutoFlowPostAction.Webhook.stored,
            modelId = "gpt-test",
            endpointProfileId = "profile-test",
        )
        val runner = AutoFlowRunner(
            repository = repo,
            processor = processor,
            scope = scope,
            jobScheduler = scheduler,
        )
        try {
            runner.start()
            awaitPass(runner, atLeast = 1)
            // Force another combine emission after seed.
            awaitNextPass(runner) {
                repo.updateRecordingTitle("rec-ready-done", "Already ready (touched)")
            }
            assertEquals(0, processCalls.get(), "ready at seed must not rematch / re-ASR")
            assertEquals("ready", repo.getRecording("rec-ready-done")?.status)
        } finally {
            runner.stop()
            scope.cancel()
        }
    }

    @Test
    fun generatingBecomesReady_withoutRematchRetranscribe() = runBlocking {
        val (repo, processor) = harness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processCalls = AtomicInteger(0)
        val scheduler = RecordingJobScheduler { id, _, retranscribe, _ ->
            processCalls.incrementAndGet()
            error("orphan rematch must not retranscribe completed ready (id=$id retranscribe=$retranscribe)")
        }
        repo.insertRecording(
            id = "rec-gen-to-ready",
            sessionId = 1,
            folderId = null,
            title = "In flight then done",
            source = "note_pro",
            deviceSerial = null,
            startedAt = 1_700_000_000L,
            durationSeconds = 30,
            sizeBytes = 100,
            localPath = "/tmp/af.wav",
            transcript = "done",
            summaryMarkdown = "## done",
            status = "generating",
        )
        repo.setAutoflowEnabled("flow-default", false)
        repo.upsertAutoflow(
            id = "flow-gen-ready",
            name = "Note Pro",
            enabled = true,
            wakePhrase = null,
            durationMinSeconds = 5,
            durationMaxSeconds = 3600,
            sourceFilter = "note_pro",
            templateId = "velum.meeting",
            autoLabelVoice = true,
            language = "auto",
            notify = true,
            postAction = AutoFlowPostAction.None.stored,
        )
        val runner = AutoFlowRunner(
            repository = repo,
            processor = processor,
            scope = scope,
            jobScheduler = scheduler,
        )
        try {
            runner.start()
            awaitPass(runner, atLeast = 1)
            // WorkManager finished while app was dead / restarting — status becomes ready.
            awaitNextPass(runner) {
                repo.updateStatus("rec-gen-to-ready", "ready")
            }
            assertEquals(0, processCalls.get(), "terminal ready after in-flight must not re-ASR")
        } finally {
            runner.stop()
            scope.cancel()
        }
    }

    @Test
    fun seed_doesNotClaimImported_soRematchCanRun() = runBlocking {
        val (repo, processor) = harness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processCalls = AtomicInteger(0)
        val scheduler = RecordingJobScheduler { id, templateId, _, _ ->
            processCalls.incrementAndGet()
            kotlinx.coroutines.runBlocking {
                assertEquals("velum.meeting", templateId)
                repo.updateStatus(id, "generating")
                repo.updateStatus(id, "ready")
                repo.updateSummary(id, "## ok")
            }
        }
        repo.insertRecording(
            id = "rec-imported-seed",
            sessionId = null,
            folderId = null,
            title = "Waiting import",
            source = "note_pro",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 20,
            sizeBytes = 10,
            localPath = "/tmp/imp.wav",
            transcript = null,
            summaryMarkdown = null,
            status = "imported",
        )
        repo.setAutoflowEnabled("flow-default", false)
        repo.upsertAutoflow(
            id = "flow-imported",
            name = "Note Pro",
            enabled = true,
            wakePhrase = null,
            durationMinSeconds = 0,
            durationMaxSeconds = 3600,
            sourceFilter = "note_pro",
            templateId = "velum.meeting",
            autoLabelVoice = true,
            language = "auto",
            notify = true,
            postAction = AutoFlowPostAction.None.stored,
        )
        val runner = AutoFlowRunner(
            repository = repo,
            processor = processor,
            scope = scope,
            jobScheduler = scheduler,
        )
        try {
            runner.start()
            awaitPass(runner, atLeast = 1) // seed claims only ready/failed — not imported
            assertEquals(0, processCalls.get(), "seed pass must not enqueue")
            // Trigger a post-seed combine emission; imported must still be matchable.
            repo.updateRecordingTitle("rec-imported-seed", "Waiting import (touched)")
            withTimeout(15_000) {
                while (processCalls.get() < 1) delay(50)
            }
            assertEquals(1, processCalls.get(), "imported at seed must remain matchable")
        } finally {
            runner.stop()
            scope.cancel()
        }
    }

    @Test
    fun seed_skips_in_flight_so_generating_can_rematch() = runBlocking {
        val (repo, processor) = harness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processCalls = AtomicInteger(0)
        val scheduler = RecordingJobScheduler { id, _, _, _ ->
            processCalls.incrementAndGet()
            kotlinx.coroutines.runBlocking {
                repo.updateStatus(id, "generating")
                repo.updateStatus(id, "ready")
                repo.updateSummary(id, "## ok")
            }
        }
        // Pre-existing generating row simulates process death mid-job before runner start.
        repo.insertRecording(
            id = "rec-inflight",
            sessionId = 1,
            folderId = null,
            title = "In flight",
            source = "note_pro",
            deviceSerial = null,
            startedAt = 1_700_000_000L,
            durationSeconds = 30,
            sizeBytes = 100,
            localPath = "/tmp/af.wav",
            transcript = null,
            summaryMarkdown = null,
            status = "generating",
        )
        repo.setAutoflowEnabled("flow-default", false)
        repo.upsertAutoflow(
            id = "flow-death",
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
            postAction = AutoFlowPostAction.None.stored,
        )

        val runner = AutoFlowRunner(
            repository = repo,
            processor = processor,
            scope = scope,
            jobScheduler = scheduler,
        )
        try {
            runner.start()
            // Let the first combine emission seed (must still see generating / skip it).
            awaitPass(runner, atLeast = 1)
            assertEquals(0, processCalls.get(), "generating seed must not enqueue yet")
            repo.updateStatus("rec-inflight", "imported")
            withTimeout(15_000) {
                while (processCalls.get() < 1) delay(50)
            }
            assertEquals(1, processCalls.get(), "in-flight seed skip must allow rematch after restart")
        } finally {
            runner.stop()
            scope.cancel()
        }
    }

    private suspend fun awaitPass(runner: AutoFlowRunner, atLeast: Int) {
        withTimeout(15_000) {
            runner.passIndex.first { it >= atLeast }
        }
    }

    /** Runs [block] then waits until [AutoFlowRunner.passIndex] advances past its pre-block value. */
    private suspend fun awaitNextPass(runner: AutoFlowRunner, block: suspend () -> Unit) {
        val before = runner.passIndex.value
        block()
        awaitPass(runner, atLeast = before + 1)
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
