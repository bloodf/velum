package dev.velum.ai.pipeline

import dev.velum.ai.engine.JobProgress
import dev.velum.core.autoflow.AutoFlowPostAction
import dev.velum.core.calendar.CalendarEventProvider
import dev.velum.core.time.VelumDateTime
import dev.velum.core.calendar.CalendarEventSummary
import dev.velum.core.calendar.CalendarLookupResult
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.db.Autoflow
import dev.velum.core.db.Recording
import dev.velum.core.settings.ApiModelOverride
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class AutoFlowRunnerTest {
    @Test
    fun isStableNonMatch_onlyTerminalStatuses() {
        assertTrue(AutoFlowRunner.isStableNonMatch(recording("ready", "ready")))
        assertTrue(AutoFlowRunner.isStableNonMatch(recording("failed", "failed")))
        assertFalse(AutoFlowRunner.isStableNonMatch(recording("live", "recording", 0)))
        assertFalse(AutoFlowRunner.isStableNonMatch(recording("import", "imported", 0)))
    }

    @Test
    fun incompleteDuration_thenMatch_runsOnceAndPostsOnReady() = runTest {
        val f = Fixture(this, rule = rule(durationMin = 5))
        f.start()
        f.publish(recording("rec-af-1", "recording", 0))
        assertTrue(f.enqueued.isEmpty(), "duration=0 must not match durationMin=5")
        // Publish the finalized import as a single snapshot. Its path, duration
        // and state must all be visible before the synthetic scheduler completes.
        f.publish(f.row("rec-af-1").copy(duration_seconds = 12, local_path = "/tmp/af.wav", status = "imported"))
        assertEquals(listOf("rec-af-1"), f.enqueued)
        assertEquals("ready", f.row("rec-af-1").status)
        assertEquals(listOf("rec-af-1"), f.actions.filterIsInstance<AutoFlowUiAction.PostWebhook>().map { it.recordingId })
    }

    @Test
    fun failedProcessing_doesNotFirePostAction() = runTest {
        val f = Fixture(this)
        f.enqueue = { id -> f.store.change(id) { it.copy(status = "failed") } }
        f.start()
        f.publish(recording("rec-fail"))
        assertEquals("failed", f.row("rec-fail").status)
        assertTrue(f.actions.none { it is AutoFlowUiAction.PostWebhook })
        assertTrue(f.runner.isRunning)
    }

    @Test
    fun terminalReady_afterSeed_isNotRetranscribed() = runTest {
        val f = Fixture(this)
        f.store.put(recording("rec-ready-done", "ready"))
        f.start()
        f.publish(f.row("rec-ready-done").copy(title = "Already ready (touched)"))
        assertTrue(f.enqueued.isEmpty())
        assertEquals("ready", f.row("rec-ready-done").status)
    }

    @Test
    fun generatingBecomesReady_withoutRematchRetranscribe() = runTest {
        val f = Fixture(this)
        f.store.put(recording("rec-gen-to-ready", "generating"))
        f.start()
        f.publish(f.row("rec-gen-to-ready").copy(status = "ready"))
        assertTrue(f.enqueued.isEmpty(), "WorkManager already finished; never re-ASR")
    }

    @Test
    fun seed_doesNotClaimImported_soRematchCanRun() = runTest {
        val f = Fixture(this)
        f.store.put(recording("rec-imported-seed"))
        f.start()
        assertTrue(f.enqueued.isEmpty(), "seed pass must not enqueue")
        f.publish(f.row("rec-imported-seed").copy(title = "Waiting import (touched)"))
        assertEquals(listOf("rec-imported-seed"), f.enqueued)
        assertEquals("velum.meeting", f.templates.single())
        assertEquals(ApiModelOverride(profileId = "test-profile", modelId = "test-model"), f.overrides.single())
    }

    @Test
    fun keptJob_givesTheRowItsPriorStatusBack_notQueued_untilItFinishes() = runTest {
        val f = Fixture(this)
        f.start()
        f.keepRunning = true
        f.enqueue = { /* the running job owns the row */ }
        f.store.put(recording("rec-kept", "paused_thermal"))
        runCurrent() // the runner now waits on the kept job, so the pass does not complete
        assertEquals("paused_thermal", f.row("rec-kept").status, "a kept job must not leave the row on queued or invent generating")
        assertTrue(f.actions.none { it is AutoFlowUiAction.PostWebhook }, "no post-action before the job finishes")
        f.complete("rec-kept")
        testScheduler.advanceTimeBy(2_000)
        runCurrent()
        assertEquals("ready", f.row("rec-kept").status)
        assertEquals(listOf("rec-kept"), f.actions.filterIsInstance<AutoFlowUiAction.PostWebhook>().map { it.recordingId })
    }

    @Test
    fun jobEndingBeforeTheQueuedWrite_isTerminal_andNothingIsEnqueued() = runTest {
        val f = Fixture(this)
        f.start()
        // The row turns ready between the runner reading it and its conditional queued write.
        f.store.activeTemplates["rec-gap"] = "velum.meeting" // the finished job produced this flow's summary
        f.store.beforeConditionalQueue = { id -> f.store.change(id) { it.copy(status = "ready", summary_markdown = "## done") } }
        f.publish(recording("rec-gap", "imported"))
        assertTrue(f.enqueued.isEmpty(), "a finished job must not be re-enqueued")
        assertEquals("ready", f.row("rec-gap").status, "the row must not be pushed back to queued")
        assertEquals(listOf("rec-gap"), f.actions.filterIsInstance<AutoFlowUiAction.PostWebhook>().map { it.recordingId })
    }

    @Test
    fun keptJobThatHasNotStarted_keepsTheImportedStatus_notGenerating() = runTest {
        val f = Fixture(this)
        f.start()
        f.keepRunning = true
        f.enqueue = { }
        f.store.put(recording("rec-notyet", "imported"))
        runCurrent()
        assertEquals("imported", f.row("rec-notyet").status)
    }

    /** The row turns ready (with [template] as its active summary) the first time the runner re-reads it. */
    private fun Fixture.finishesBeforeTheRunnerActs(template: String) {
        var flipped = false
        store.beforeRead = { id ->
            if (!flipped) {
                flipped = true
                store.activeTemplates[id] = template
                store.change(id) { it.copy(status = "ready", summary_markdown = "## done") }
            }
        }
    }

    @Test
    fun jobFinishedForTheFlowsOwnTemplate_isSkipped_andThePostActionFiresOnce() = runTest {
        val f = Fixture(this) // the flow asks for velum.meeting
        f.start()
        f.finishesBeforeTheRunnerActs("velum.meeting")
        f.publish(recording("rec-same", "imported"))
        assertTrue(f.enqueued.isEmpty(), "the finished job already did this flow's work")
        assertEquals(listOf("rec-same"), f.actions.filterIsInstance<AutoFlowUiAction.PostWebhook>().map { it.recordingId })
    }

    @Test
    fun aBlankTemplateFlow_reEnqueuesWithTheEffectiveTemplate_soTheFinishedWorkerIsReplaced() = runTest {
        val f = Fixture(this, rule = rule().copy(template_id = null))
        f.start()
        f.finishesBeforeTheRunnerActs("velum.sales")
        f.publish(recording("rec-blank", "imported"))
        assertEquals(listOf<String?>("velum.meeting"), f.templates, "a blank template must be enqueued as real parameters")
    }

    @Test
    fun aKeptEnqueueNeverRestoresReady_soThePostActionDoesNotFireOnAnotherTemplatesSummary() = runTest {
        val f = Fixture(this, rule = rule().copy(template_id = null))
        f.start()
        f.keepRunning = true
        f.enqueue = { }
        f.finishesBeforeTheRunnerActs("velum.sales")
        f.store.put(recording("rec-kept-ready", "imported"))
        runCurrent()
        assertTrue(f.row("rec-kept-ready").status != "ready", "the other template's ready must not be put back")
        assertTrue(f.actions.none { it is AutoFlowUiAction.PostWebhook }, "no post-action on the wrong summary")
    }

    @Test
    fun anUneditedTplMeetingFlow_matchesTheStoredBuiltin_butAnEditedOneDoesNot() = runTest {
        val unedited = Fixture(this, rule = rule().copy(template_id = "tpl-meeting"))
        unedited.start()
        unedited.finishesBeforeTheRunnerActs("velum.meeting") // the processor stores the resolved built-in id
        unedited.publish(recording("rec-unedited", "imported"))
        assertTrue(unedited.enqueued.isEmpty(), "tpl-meeting resolves to velum.meeting: the finished work is the flow's")

        val edited = Fixture(this, rule = rule().copy(template_id = "tpl-meeting"))
        edited.store.exactTemplates += "tpl-meeting" // the user's edited row keeps its id
        edited.start()
        edited.finishesBeforeTheRunnerActs("velum.meeting")
        edited.publish(recording("rec-edited", "imported"))
        assertEquals(listOf("rec-edited"), edited.enqueued, "an edited tpl-meeting is a different template from the built-in")
    }

    @Test
    fun jobFinishedForAnotherTemplate_doesNotSatisfyTheFlow_soItRunsAgain() = runTest {
        val f = Fixture(this) // the flow asks for velum.meeting
        f.start()
        f.finishesBeforeTheRunnerActs("velum.sales")
        f.publish(recording("rec-other", "imported"))
        assertEquals(listOf("rec-other"), f.enqueued, "the flow's own template still has to be generated")
        assertEquals("ready", f.row("rec-other").status)
    }

    @Test
    fun seed_skips_in_flight_so_generating_can_rematch() = runTest {
        val f = Fixture(this)
        f.store.put(recording("rec-inflight", "generating"))
        f.start()
        assertTrue(f.enqueued.isEmpty())
        f.publish(f.row("rec-inflight").copy(status = "imported"))
        assertEquals(listOf("rec-inflight"), f.enqueued)
    }

    @Test
    fun schedulerThrows_marksFailed_andProcessesNextRecording() = runTest {
        recovery { f ->
            f.enqueue = { id ->
                if (id == "bad") error("synthetic scheduler failure") else f.complete(id)
            }
        }
    }

    @Test
    fun processorFlowThrows_marksFailed_andProcessesNextRecording() = runTest {
        recovery(scheduler = false) { f ->
            f.process = { id ->
                flow {
                    if (id == "bad") error("synthetic processor failure")
                    f.complete(id)
                    emit(JobProgress.Succeeded())
                }
            }
        }
    }

    @Test
    fun processorFactoryThrows_marksFailed_andProcessesNextRecording() = runTest {
        recovery(scheduler = false) { f ->
            f.process = { id ->
                if (id == "bad") error("synthetic factory failure")
                flow { f.complete(id); emit(JobProgress.Succeeded()) }
            }
        }
    }

    @Test
    fun calendarThrows_marksFailed_andProcessesNextRecording() = runTest {
        recovery { f ->
            var calls = 0
            f.lookup = {
                if (++calls == 1) error("synthetic calendar failure") else CalendarLookupResult.Ok(null)
            }
        }
    }

    @Test
    fun repositoryEnqueueStatusThrows_marksFailed_andProcessesNextRecording() = runTest {
        recovery { f -> f.store.beforeStatus = { id, status -> if (id == "bad" && status == "queued") error("synthetic write failure") } }
    }

    @Test
    fun repositoryReadThrows_marksFailed_andProcessesNextRecording() = runTest {
        recovery { f -> f.store.beforeRead = { id -> if (id == "bad") error("synthetic read failure") } }
    }

    @Test
    fun repositoryCalendarTitleThrows_marksFailed_andProcessesNextRecording() = runTest {
        recovery { f ->
            f.lookup = { CalendarLookupResult.Ok(CalendarEventSummary("Synthetic event", 0, Long.MAX_VALUE)) }
            f.store.beforeTitle = { id -> if (id == "bad") error("synthetic title write failure") }
        }
    }

    @Test
    fun failureStateWriteAlsoThrows_surfacesGenericAction_withoutRetryLoop() = runTest {
        val f = recovery(expectedBadStatus = "imported") { f ->
            var calls = 0
            f.lookup = { if (++calls == 1) error("private synthetic exception message") else CalendarLookupResult.Ok(null) }
            f.store.beforeStatus = { id, status -> if (id == "bad" && status == "failed") error("synthetic unavailable storage") }
        }
        assertEquals(1, f.actions.count { it === AutoFlowUiAction.ProcessingFailed })
        assertTrue(f.actions.none { it.toString().contains("private synthetic") })
    }

    @Test
    fun postActionRepositoryThrows_preservesReady_andProcessesNextRecording() = runTest {
        recovery(expectedBadStatus = "ready") { f ->
            var reads = 0
            f.store.beforeRead = { id -> if (id == "bad" && ++reads == 3) error("synthetic post-action read failure") }
        }
    }

    @Test
    fun postActionCalendarThrows_preservesReady_andProcessesNextRecording() = runTest {
        val f = Fixture(this, rule = rule(postAction = AutoFlowPostAction.CalendarInsert))
        var lookups = 0
        f.lookup = { if (++lookups == 2) error("synthetic insert lookup failure") else CalendarLookupResult.Ok(null) }
        f.start()
        f.publish(recording("bad"), recording("good"))
        assertTrue(f.runner.isRunning)
        assertEquals("ready", f.row("bad").status)
        assertEquals("ready", f.row("good").status)
        assertEquals(1, f.actions.count { it === AutoFlowUiAction.ProcessingFailed })
        assertEquals(listOf(recording("good").title), f.actions.filterIsInstance<AutoFlowUiAction.InsertCalendarEvent>().map { it.title })
        f.publish(recording("later"))
        assertEquals("ready", f.row("later").status)
    }

    @Test
    fun cancellationIsRethrown_passIndexAdvances_andRestartDoesNotOverlapCollectors() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        f.lookup = {
            if (++calls == 1) { entered.complete(Unit); gate.await() }
            CalendarLookupResult.Ok(null)
        }
        f.start()
        val seed = f.runner.passIndex.value
        f.store.put(recording("cancelled"))
        runCurrent()
        assertTrue(entered.isCompleted)
        f.runner.stop()
        f.runner.start() // May run while old collector is still in finally.
        runCurrent()
        assertTrue(f.runner.passIndex.value >= seed + 2, "cancelled and restarted passes complete")
        assertTrue(f.runner.isRunning, "old completion must not clear the new job")
        assertEquals("ready", f.row("cancelled").status)
        assertEquals(listOf("cancelled"), f.enqueued)
        assertTrue(f.actions.none { it === AutoFlowUiAction.ProcessingFailed })
    }

    @Test
    fun dependencyCancellationDoesNotMarkFailed_orProcessNextRecording() = runTest {
        val f = Fixture(this)
        f.enqueue = { throw CancellationException("synthetic cancellation") }
        f.start()
        val seed = f.runner.passIndex.value
        f.publish(recording("bad"), recording("good"))
        assertFalse(f.runner.isRunning)
        assertEquals(seed + 1, f.runner.passIndex.value)
        assertEquals("queued", f.row("bad").status)
        assertEquals("imported", f.row("good").status)
        assertTrue(f.actions.none { it === AutoFlowUiAction.ProcessingFailed })
    }

    @Test
    fun cancelledFailureWriteDoesNotBecomeGenericFailure() = runTest {
        val f = Fixture(this)
        f.enqueue = { error("synthetic enqueue error") }
        f.store.beforeStatus = { _, status -> if (status == "failed") throw CancellationException("cancelled during error handling") }
        f.start()
        val seed = f.runner.passIndex.value
        f.publish(recording("bad"))
        assertFalse(f.runner.isRunning)
        assertEquals(seed + 1, f.runner.passIndex.value)
        assertTrue(f.actions.none { it === AutoFlowUiAction.ProcessingFailed })
        f.store.beforeStatus = { _, _ -> }
        f.enqueue = { f.complete(it) }
        f.runner.start()
        runCurrent()
        assertEquals("ready", f.row("bad").status, "cancelled failure handling must not claim the row")
    }

    @Test
    fun completedUpstream_canRestart_withoutAStaleJobReference() = runTest {
        val f = Fixture(this)
        f.store.finite = true
        f.store.put(recording("finite"))
        f.start()
        assertFalse(f.runner.isRunning)
        assertEquals(1, f.runner.passIndex.value)
        f.runner.start()
        runCurrent()
        assertFalse(f.runner.isRunning)
        assertEquals("ready", f.row("finite").status)
        assertEquals(2, f.runner.passIndex.value)
    }

    @Test
    fun failedUpstream_surfacesGenericFailure_andCanRestart() = runTest {
        val f = Fixture(this)
        f.store.failSubscription = true
        f.runner.start()
        runCurrent()
        assertFalse(f.runner.isRunning)
        assertEquals(0, f.runner.passIndex.value, "subscription failure is not a collected pass")
        assertEquals(listOf<AutoFlowUiAction>(AutoFlowUiAction.ProcessingFailed), f.actions)
        f.store.failSubscription = false
        f.start()
        f.publish(recording("recovered"))
        assertTrue(f.runner.isRunning)
        assertEquals("ready", f.row("recovered").status)
    }

    @Test
    fun failureBackpressure_deliversEveryFailure_andThenProcessesNextRecording() = runTest {
        val f = Fixture(this, collectActions = false)
        val releaseConsumer = CompletableDeferred<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.runner.actions.collect {
                f.actions += it
                if (f.actions.size == 1) releaseConsumer.await()
            }
        }
        f.enqueue = { id ->
            if (id.startsWith("bad-")) error("synthetic enqueue failure") else f.complete(id)
        }
        f.start()
        val seed = f.runner.passIndex.value
        repeat(12) { f.store.put(recording("bad-$it")) }
        f.store.put(recording("good"))
        runCurrent()
        assertTrue(f.runner.isRunning)
        assertEquals(seed, f.runner.passIndex.value, "pass waits for the subscribed consumer")
        releaseConsumer.complete(Unit)
        runCurrent()
        assertEquals(12, f.actions.count { it === AutoFlowUiAction.ProcessingFailed })
        assertEquals("ready", f.row("good").status)
        assertTrue(f.runner.passIndex.value > seed)
    }

    @Test
    fun realRepositoryAdapter_observesInsert_andPersistsProcessingResult() = runBlocking {
        val database = createDatabase(DriverFactory())
        val repo = VelumRepository(database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            repo.setAutoflowEnabled("flow-default", false)
            repo.upsertAutoflow(
                id = "flow-real", name = "Synthetic", enabled = true, wakePhrase = null,
                durationMinSeconds = 0, durationMaxSeconds = 3600, sourceFilter = "all",
                templateId = null, autoLabelVoice = false, language = "auto", notify = true,
                postAction = AutoFlowPostAction.Webhook.stored,
            )
            val runner = AutoFlowRunner(
                RepositoryAutoFlowRecordingStore(repo),
                { id, _, _ -> flow { repo.updateStatus(id, "ready"); repo.updateSummary(id, "## Synthetic result"); emit(JobProgress.Succeeded()) } },
                scope,
                calendar = object : CalendarEventProvider { override suspend fun currentOrNextEvent(aroundEpochMs: Long) = CalendarLookupResult.Ok(null) },
            )
            val action = async(start = CoroutineStart.UNDISPATCHED) {
                runner.actions.first { it is AutoFlowUiAction.PostWebhook }
            }
            runner.start()
            withTimeout(10_000) { runner.passIndex.first { it > 0 } }
            repo.insertRecording(
                id = "real-observer", sessionId = null, folderId = null,
                title = "Synthetic integration", source = "phone", deviceSerial = null,
                startedAt = 1, durationSeconds = 10, sizeBytes = 0, localPath = null,
                transcript = null, summaryMarkdown = null, status = "imported",
            )
            assertEquals("real-observer", (withTimeout(10_000) { action.await() } as AutoFlowUiAction.PostWebhook).recordingId)
            assertEquals("ready", repo.getRecording("real-observer")?.status)
            assertEquals("## Synthetic result", repo.getRecording("real-observer")?.summary_markdown)
            assertTrue(runner.isRunning)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.join()
            database.driver.close()
        }
    }

    @Test
    fun a_calendar_prompt_with_no_collector_is_kept_for_when_one_arrives() = runBlocking {
        val database = createDatabase(DriverFactory())
        val repo = VelumRepository(database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blocked = AtomicInteger()
        try {
            repo.setAutoflowEnabled("flow-default", false)
            val runner = AutoFlowRunner(
                RepositoryAutoFlowRecordingStore(repo),
                { _, _, _ -> flow { error("Not used") } },
                scope,
                calendar = object : CalendarEventProvider {
                    override suspend fun currentOrNextEvent(aroundEpochMs: Long) = CalendarLookupResult.PermissionDenied
                },
            )
            runner.start()
            withTimeout(10_000) { runner.passIndex.first { it > 0 } }
            suspend fun settle(change: suspend () -> Unit) {
                val before = runner.passIndex.value
                change()
                withTimeout(10_000) { runner.passIndex.first { it > before } }
            }
            // The app has no collector while its composition is gone (backgrounded, or an activity recreate).
            settle {
                repo.insertRecording(
                    id = "one", sessionId = null, folderId = null, title = "Recording synthetic", source = "phone",
                    deviceSerial = null, startedAt = 1, durationSeconds = 10, sizeBytes = 0, localPath = null,
                    transcript = null, summaryMarkdown = null, status = "imported",
                )
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runner.actions.collect { if (it is AutoFlowUiAction.BlockedOnPermission) blocked.incrementAndGet() }
            }
            settle { repo.updateStatus("one", "queued") }
            withTimeout(10_000) { while (blocked.get() == 0) kotlinx.coroutines.delay(5) }
            settle { repo.updateStatus("one", "generating") }
            assertEquals(1, blocked.get())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.join()
            database.driver.close()
        }
    }

    @Test
    fun missing_calendar_permission_asks_once_not_on_every_status_change_of_an_import() = runBlocking {
        val database = createDatabase(DriverFactory())
        val repo = VelumRepository(database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blocked = AtomicInteger()
        try {
            repo.setAutoflowEnabled("flow-default", false)
            val runner = AutoFlowRunner(
                RepositoryAutoFlowRecordingStore(repo),
                { _, _, _ -> flow { error("Not used") } },
                scope,
                calendar = object : CalendarEventProvider {
                    override suspend fun currentOrNextEvent(aroundEpochMs: Long) = CalendarLookupResult.PermissionDenied
                },
            )
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runner.actions.collect { if (it is AutoFlowUiAction.BlockedOnPermission) blocked.incrementAndGet() }
            }
            runner.start()
            withTimeout(10_000) { runner.passIndex.first { it > 0 } }
            fun insert(id: String) = runBlocking {
                repo.insertRecording(
                    id = id, sessionId = null, folderId = null, title = "Recording synthetic", source = "phone",
                    deviceSerial = null, startedAt = 1, durationSeconds = 10, sizeBytes = 0, localPath = null,
                    transcript = null, summaryMarkdown = null, status = "imported",
                )
            }
            suspend fun settle(change: suspend () -> Unit) {
                val before = runner.passIndex.value
                change()
                withTimeout(10_000) { runner.passIndex.first { it > before } }
            }
            settle { insert("one") }
            settle { repo.updateStatus("one", "queued") }
            settle { repo.updateStatus("one", "generating") }
            settle { insert("two") }
            assertEquals(1, blocked.get())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.join()
            database.driver.close()
        }
    }

    @Test
    fun realRepositoryImportUpdate_waitsForCompleteInput_thenProcessesExactlyOnce() = runBlocking {
        val database = createDatabase(DriverFactory())
        val repo = VelumRepository(database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val completeImport = CompletableDeferred<Unit>()
        val processCalls = AtomicInteger()
        try {
            repo.setAutoflowEnabled("flow-default", false)
            repo.upsertAutoflow(
                id = "flow-update", name = "Synthetic", enabled = true, wakePhrase = null,
                durationMinSeconds = 5, durationMaxSeconds = 3600, sourceFilter = "note_pro",
                templateId = null, autoLabelVoice = false, language = "auto", notify = true,
                postAction = AutoFlowPostAction.Webhook.stored,
            )
            repo.insertRecording(
                id = "real-update", sessionId = null, folderId = null,
                title = "Incomplete import", source = "note_pro", deviceSerial = null,
                startedAt = 1, durationSeconds = 0, sizeBytes = 0, localPath = null,
                transcript = null, summaryMarkdown = null, status = "recording",
            )
            val runner = AutoFlowRunner(
                RepositoryAutoFlowRecordingStore(repo),
                { id, _, _ -> flow {
                    processCalls.incrementAndGet()
                    // A real worker completes asynchronously, after import has
                    // published its path/duration and final imported status.
                    completeImport.await()
                    repo.updateStatus(id, "ready")
                    repo.updateSummary(id, "## Synthetic import result")
                    emit(JobProgress.Succeeded())
                } },
                scope,
                calendar = object : CalendarEventProvider {
                    override suspend fun currentOrNextEvent(aroundEpochMs: Long) = CalendarLookupResult.Ok(null)
                },
            )
            val action = async(start = CoroutineStart.UNDISPATCHED) {
                runner.actions.first { it is AutoFlowUiAction.PostWebhook }
            }
            runner.start()
            withTimeout(10_000) { runner.passIndex.first { it > 0 } }
            val seed = runner.passIndex.value
            repo.updateRecordingTitle("real-update", "Still incomplete")
            withTimeout(10_000) { runner.passIndex.first { it > seed } }
            assertEquals(0, processCalls.get(), "the incomplete DB snapshot must not match")
            repo.updateLocalPath("real-update", "/tmp/synthetic.wav", 12, 32)
            repo.updateStatus("real-update", "imported")
            completeImport.complete(Unit)
            assertEquals("real-update", (withTimeout(10_000) { action.await() } as AutoFlowUiAction.PostWebhook).recordingId)
            assertEquals("ready", repo.getRecording("real-update")?.status)
            assertEquals(1, processCalls.get())
            assertTrue(runner.isRunning)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.join()
            database.driver.close()
        }
    }

    private fun TestScope.recovery(
        scheduler: Boolean = true,
        expectedBadStatus: String = "failed",
        configure: (Fixture) -> Unit,
    ): Fixture {
        val f = Fixture(this, scheduler = scheduler)
        configure(f)
        f.start()
        f.publish(recording("bad"), recording("good"))
        assertTrue(f.runner.isRunning, "one bad recording must not stop the collector")
        assertEquals(expectedBadStatus, f.row("bad").status)
        assertEquals("ready", f.row("good").status)
        assertTrue(f.actions.any { it === AutoFlowUiAction.ProcessingFailed })
        assertEquals(listOf("good"), f.actions.filterIsInstance<AutoFlowUiAction.PostWebhook>().map { it.recordingId })
        f.publish(recording("later"))
        assertEquals("ready", f.row("later").status, "a later emission must still be consumed")
        assertTrue(f.runner.isRunning)
        return f
    }

    private class Fixture(
        val test: TestScope,
        scheduler: Boolean = true,
        rule: Autoflow = rule(),
        collectActions: Boolean = true,
    ) {
        val store = Store(rule)
        // All fixture coroutines use the same TestCoroutineScheduler/thread.
        // Cross-thread integration below uses StateFlow and Deferred signals.
        val enqueued = mutableListOf<String>()
        val templates = mutableListOf<String?>()
        val overrides = mutableListOf<ApiModelOverride?>()
        val actions = mutableListOf<AutoFlowUiAction>()
        var enqueue: (String) -> Unit = { complete(it) }
        /** Makes the scheduler report that it kept an already running job instead of starting one. */
        var keepRunning = false
        var process: (String) -> Flow<JobProgress> = { id -> flow { complete(id); emit(JobProgress.Succeeded()) } }
        var lookup: suspend () -> CalendarLookupResult = { CalendarLookupResult.Ok(null) }
        var calendarAround: suspend (Long) -> CalendarLookupResult = { lookup() }
        val runner = AutoFlowRunner(
            store, { id, _, _ -> process(id) }, test.backgroundScope,
            calendar = object : CalendarEventProvider { override suspend fun currentOrNextEvent(aroundEpochMs: Long) = calendarAround(aroundEpochMs) },
            jobScheduler = if (scheduler) object : RecordingJobScheduler {
                override fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?) {
                    enqueueReportingStart(recordingId, templateId, retranscribe, apiModelOverride)
                }
                override fun enqueueReportingStart(
                    recordingId: String,
                    templateId: String?,
                    retranscribe: Boolean,
                    apiModelOverride: ApiModelOverride?,
                ): Boolean {
                    enqueued += recordingId
                    templates += templateId
                    overrides += apiModelOverride
                    enqueue(recordingId)
                    return !keepRunning
                }
            } else null,
        )
        init {
            if (collectActions) test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                runner.actions.collect { actions += it }
            }
        }
        fun start() {
            val previous = runner.passIndex.value
            runner.start()
            test.runCurrent()
            assertTrue(runner.passIndex.value > previous, "seed pass must complete before input mutation")
        }
        fun publish(vararg rows: Recording) {
            val previous = runner.passIndex.value
            rows.forEach { store.put(it) }
            test.runCurrent()
            assertTrue(runner.passIndex.value > previous, "input pass must complete, even on error or cancellation")
        }
        fun row(id: String) = store.rows.value.single { it.id == id }
        fun complete(id: String) { store.change(id) { it.copy(status = "ready", summary_markdown = "## Synthetic note") } }
    }

    private class Store(rule: Autoflow) : AutoFlowRecordingStore {
        val rows = MutableStateFlow<List<Recording>>(emptyList())
        val rules = MutableStateFlow(listOf(rule))
        var beforeRead: (String) -> Unit = {}
        var beforeStatus: (String, String) -> Unit = { _, _ -> }
        var beforeTitle: (String) -> Unit = {}
        var finite = false
        var failSubscription = false
        override fun observeRecordings(): Flow<List<Recording>> =
            if (failSubscription) flow { error("synthetic subscription failure") }
            else if (finite) flowOf(rows.value) else rows
        override fun observeAutoflows(): Flow<List<Autoflow>> = if (finite) flowOf(rules.value) else rules
        override suspend fun getRecording(id: String): Recording? {
            beforeRead(id)
            return rows.value.firstOrNull { it.id == id }
        }
        override suspend fun updateStatus(id: String, status: String) {
            beforeStatus(id, status)
            change(id) { it.copy(status = status) }
        }
        var beforeConditionalQueue: (String) -> Unit = {}
        override suspend fun markQueuedIfActive(id: String): Boolean {
            beforeConditionalQueue(id)
            val current = rows.value.firstOrNull { it.id == id }?.status
            if (current == null || current == "ready" || current == "failed") return false
            updateStatus(id, "queued")
            return true
        }
        override suspend fun restoreStatusIfQueued(id: String, status: String): Boolean {
            if (rows.value.firstOrNull { it.id == id }?.status != "queued") return false
            updateStatus(id, status)
            return true
        }
        /** Template of each recording's active summary, set by tests that need a finished row. */
        val activeTemplates = mutableMapOf<String, String>()
        override suspend fun activeSummaryTemplateId(id: String): String? = activeTemplates[id]
        /** Template rows that exist under their exact id (an edited builtin). */
        val exactTemplates = mutableSetOf<String>()
        override suspend fun exactTemplateId(templateId: String): String? = templateId.takeIf { it in exactTemplates }
        override suspend fun updateRecordingTitle(id: String, title: String) {
            beforeTitle(id)
            change(id) { it.copy(title = title) }
        }
        fun change(id: String, transform: (Recording) -> Recording) {
            rows.value = rows.value.map { if (it.id == id) transform(it) else it }
        }
        fun put(recording: Recording) {
            rows.value = rows.value.filterNot { it.id == recording.id } + recording
        }
    }

    @Test
    fun calendarRenamesProductionDefaultTitlesInBothLocales_andKeepsEditedTitles() = runTest {
        val f = Fixture(this)
        f.lookup = { CalendarLookupResult.Ok(CalendarEventSummary("Planning", 0, Long.MAX_VALUE)) }
        f.start()
        f.publish(
            recording("pt").copy(started_at = 1_792_000_000, title = defaultTitle(1_792_000_000, "pt-BR")),
            recording("en").copy(started_at = 1_792_000_000, title = defaultTitle(1_792_000_000, "en")),
            recording("edited").copy(started_at = 1_792_000_000, title = "My own title"),
        )
        assertEquals("Planning", f.row("pt").title)
        assertEquals("Planning", f.row("en").title)
        assertEquals("My own title", f.row("edited").title)
    }

    @Test
    fun calendarRenameOnlyAcceptsAnEventThatOverlapsTheRecording() = runTest {
        val f = Fixture(this)
        val start = 1_792_000_000L
        val ms = start * 1000
        var event: CalendarEventSummary? = null
        f.calendarAround = { CalendarLookupResult.Ok(event) }
        f.start()
        fun rec(id: String) = recording(id).copy(started_at = start, duration_seconds = 600, title = defaultTitle(start, "en"))
        event = CalendarEventSummary("Running", ms - 5 * 60_000, ms + 30 * 60_000) // on at the start
        f.publish(rec("running"))
        event = CalendarEventSummary("Soon", ms + 4 * 60_000, ms + 35 * 60_000) // begins within the grace after the start
        f.publish(rec("soon"))
        event = CalendarEventSummary("Later", ms + 5 * 3600_000, ms + 6 * 3600_000) // a meeting hours after the start
        f.publish(rec("later"))
        event = CalendarEventSummary("Tomorrow", ms + 20 * 3600_000, ms + 21 * 3600_000) // a day-ahead "next" event
        f.publish(rec("tomorrow"))
        event = CalendarEventSummary("Earlier", ms - 3 * 3600_000, ms - 2 * 3600_000) // ended before the start
        f.publish(rec("earlier"))
        assertEquals("Running", f.row("running").title)
        assertEquals("Soon", f.row("soon").title)
        assertEquals(defaultTitle(start, "en"), f.row("later").title)
        assertEquals(defaultTitle(start, "en"), f.row("tomorrow").title)
        assertEquals(defaultTitle(start, "en"), f.row("earlier").title)
    }

    @Test
    fun calendarLookupUsesTheRecordingStartNotNow() = runTest {
        val f = Fixture(this)
        val asked = mutableListOf<Long>()
        f.calendarAround = { asked += it; CalendarLookupResult.Ok(null) }
        f.start()
        f.publish(recording("old").copy(started_at = 1_700_000_000, title = defaultTitle(1_700_000_000, "en")))
        assertEquals(listOf(1_700_000_000_000L), asked.distinct())
    }

    companion object {
        private fun defaultTitle(startedAt: Long, locale: String) = VelumDateTime.formatFriendlyTitle(startedAt, locale)

        private fun recording(id: String, status: String = "imported", duration: Long = 10) = Recording(
            id = id, session_id = null, folder_id = null, title = defaultTitle(1, "en"),
            source = "note_pro", device_serial = null, started_at = 1, duration_seconds = duration,
            size_bytes = 0, local_path = null, transcript = null, summary_markdown = null,
            status = status, trashed = 0, created_at = 1, processing_warning = null,
        )
        private fun rule(durationMin: Long = 0, postAction: AutoFlowPostAction = AutoFlowPostAction.Webhook) = Autoflow(
            id = "test-flow", name = "Synthetic flow", enabled = 1, wake_phrase = null,
            duration_min_seconds = durationMin, duration_max_seconds = 3600,
            source_filter = "note_pro", template_id = "velum.meeting", auto_label_voice = 0,
            language = "auto", notify = 1, post_action = postAction.stored,
            model_id = "test-model", endpoint_profile_id = "test-profile",
        )
    }
}
