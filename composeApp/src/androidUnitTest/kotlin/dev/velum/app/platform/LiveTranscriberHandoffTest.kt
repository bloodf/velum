package dev.velum.app.platform

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.engine.StreamingTranscriber
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.En
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.recording.LIVE_PCM_BACKLOG_CHUNKS
import dev.velum.app.ui.recording.LiveTranscriptPane
import dev.velum.app.ui.recording.savableLiveTranscript
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** The model load is held open by [modelLoad], so nothing here can pass by winning a race against it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LiveTranscriberHandoffTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val modelLoad = CountDownLatch(1)
    private val transcriber = SyntheticTranscriber()
    private val mic = SyntheticCapture()
    private lateinit var root: File
    private lateinit var container: AppContainer
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var recordingId: String
    private var destroyed = true
    private var queued = 0
    private val opens = AtomicInteger()
    @Volatile private var modelMissing = false
    private val uiStrings = androidx.compose.runtime.mutableStateOf<Strings>(PtBr)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("velum.db")
        root = Files.createTempDirectory("velum-handoff-synthetic").toFile()
        installRecordingFilesDir(root.path)
        container = AppContainer(InMemorySecureStore(), DriverFactory(context),
            modelStoreRoot = ModelStoreDir(File(root, "models").path), micCapture = mic,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> queued++; dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED } })
        recordingId = runBlocking { container.createLocalRecording(status = "recording") }
    }

    private fun showPane(
        finishWaitMs: Long = 30_000,
        reportedFailures: MutableList<String>? = null,
        awaitAttach: Boolean = true,
        realOpener: Boolean = false,
        open: (AppContainer) -> StreamingTranscriber? = {
            opens.incrementAndGet()
            assertTrue(modelLoad.await(30, TimeUnit.SECONDS), "Synthetic model load was never released")
            check(!modelMissing) { "Synthetic model failure" }
            transcriber
        },
    ) {
        destroyed = false
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            CompositionLocalProvider(LocalStrings provides uiStrings.value) {
                VelumTheme(darkTheme = false) {
                    // As in App: the failure observer lives in the composition.
                    if (reportedFailures != null) LaunchedEffect(Unit) { observeMicFailures(container) { reportedFailures += it } }
                    if (realOpener) {
                        LiveTranscriptPane(container, recordingId, title = "", finishWaitMs = finishWaitMs)
                    } else {
                        LiveTranscriptPane(container, recordingId, title = "", openTranscriber = open, finishWaitMs = finishWaitMs)
                    }
                }
            }
        }
        if (awaitAttach) waitUntil(10_000) { mic.onPcmChunk != null }
    }

    @After fun tearDown() {
        modelLoad.countDown()
        transcriber.pushGate.countDown()
        transcriber.finishGate.countDown()
        leaveScreen()
        installRecordingFilesDir(ApplicationProvider.getApplicationContext<Application>().filesDir.path)
        container.databaseHandle.driver.close()
        root.deleteRecursively()
    }

    @Test fun capture_starts_during_the_load_and_attached_audio_reaches_the_transcriber() {
        showPane()
        assertEquals(1, mic.starts)
        assertEquals(0, transcriber.starts.get())
        compose.onAllNodesWithText(PtBr.micStartFailed).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

        (1..3).forEach { mic.onPcmChunk!!.invoke(byteArrayOf(it.toByte())) }
        modelLoad.countDown()

        waitUntil(10_000) { transcriber.pushed.size == 3 }
        assertEquals(listOf<Byte>(1, 2, 3), transcriber.pushed.toList())
        assertEquals(1, transcriber.starts.get())
    }

    @Test fun leaving_during_the_load_still_finishes_the_late_transcriber() {
        showPane()
        leaveScreen()
        assertEquals(0, transcriber.finishes.get())

        modelLoad.countDown()

        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "Late transcriber was never finished")
        assertEquals(1, transcriber.finishes.get())
        assertEquals(0, mic.stops)
    }

    @Test fun stop_during_the_load_finalizes_the_recording_with_the_transcript() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        modelLoad.countDown()

        waitUntil(10_000) { mic.stops == 1 && container.activeMicRecordingId == null }
        val row = runBlocking { container.repository.observeRecording(recordingId).first() }!!
        assertEquals("queued", row.status)
        assertEquals(SyntheticTranscriber.FINAL, row.transcript)
        assertEquals(1, transcriber.finishes.get())
        assertTrue(compose.onAllNodesWithText(PtBr.micStartFailed).fetchSemanticsNodes().isEmpty())
    }

    @Test fun stop_turns_the_mic_off_before_the_transcript_wait_ends() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()

        waitUntil(10_000) { mic.stops == 1 }
        assertEquals(0, transcriber.finishes.get())
        assertEquals(0, queued)
        assertEquals("recording", recordingRow().status)

        // Stop is non-cancellable: let it finish before teardown closes the database.
        modelLoad.countDown()
        waitUntil(10_000) { queued == 1 }
    }

    @Test fun reentering_during_the_transcript_wait_shows_no_error_and_opens_no_second_model() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        waitUntil(10_000) { mic.stops == 1 }
        leaveScreen()

        showPane(awaitAttach = false)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(PtBr.micStartFailed).fetchSemanticsNodes().isEmpty())
        assertEquals(1, opens.get())

        modelLoad.countDown()
        waitOnMainLooper { queued == 1 }
        assertEquals(SyntheticTranscriber.FINAL, recordingRow().transcript)
    }

    @Test fun a_session_stopped_from_the_notification_can_still_be_finalized_from_the_screen() {
        modelLoad.countDown()
        showPane()
        waitUntil(10_000) { transcriber.starts.get() == 1 }
        leaveScreen()
        runBlocking { mic.stop() }

        showPane(awaitAttach = false)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(PtBr.micStartFailed).fetchSemanticsNodes().isEmpty())
        assertEquals(1, opens.get())

        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        waitUntil(10_000) { queued == 1 }
        assertEquals("queued", recordingRow().status)
    }

    @Test fun leaving_during_the_transcript_wait_still_finalizes_and_queues() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        waitUntil(10_000) { mic.stops == 1 }

        leaveScreen()
        modelLoad.countDown()

        waitOnMainLooper { queued == 1 }
        val row = recordingRow()
        assertEquals("queued", row.status)
        assertEquals(SyntheticTranscriber.FINAL, row.transcript)
        assertEquals(null, container.activeMicRecordingId)
    }

    @Test fun stop_without_any_transcribed_text_persists_no_placeholder() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        modelMissing = true
        modelLoad.countDown()

        waitUntil(10_000) { queued == 1 }
        val row = recordingRow()
        assertEquals("queued", row.status)
        assertEquals(null, row.transcript)
    }

    // #196: with no usable streaming model the pane says so, shows no stand-in text, and saves the audio only.
    @Test fun no_streaming_model_shows_a_message_not_fake_text_and_saves_audio_only() {
        showPane(open = { opens.incrementAndGet(); null })
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isNotEmpty() }
        mic.onPcmChunk!!.invoke(ByteArray(1024))
        listOf("partial", "chunk", "final").forEach {
            assertTrue(compose.onAllNodesWithText(it, substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty(), "fake text '$it' shown")
        }
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()

        waitUntil(10_000) { queued == 1 }
        val row = recordingRow()
        assertEquals("queued", row.status)
        assertEquals(null, row.transcript)
        assertEquals(1, mic.stops)
    }

    // The shipped opener, with no model in the empty store: the same message, no fake text.
    @Test fun the_real_opener_with_no_ready_model_shows_the_no_model_message() {
        showPane(realOpener = true)
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("chunk", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        waitUntil(10_000) { queued == 1 }
        assertEquals(null, recordingRow().transcript)
    }

    @Test fun a_model_that_fails_to_start_says_unavailable_not_missing() {
        showPane(open = { error("synthetic start failure") })
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveModelUnavailable).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isEmpty())
    }

    @Test fun a_mic_error_is_not_followed_by_a_claim_that_recording_continues() {
        mic.startResult = MicStartResult.Failed("synthetic mic failure")
        showPane(open = { opens.incrementAndGet(); null }, awaitAttach = false)
        waitUntil(10_000) { compose.onAllNodesWithText("synthetic mic failure").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(10_000) { opens.get() == 1 }
        compose.waitForIdle()
        Thread.sleep(500) // the notice, if it were shown, is set right after the open returns null
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isEmpty())
    }

    // #205: a stop from the notification ends the session without this screen's Stop.
    @Test fun a_stop_from_the_notification_removes_the_recording_continues_notice() {
        showPane(open = { null })
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isNotEmpty() }
        mic.sessions.value = MicSessionState.Stopped("/synthetic.wav", 1, 64, 1)
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun the_pane_says_the_model_is_loading_instead_of_staying_blank() {
        showPane()
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveModelLoading).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun the_loading_line_goes_away_once_the_model_is_ready_even_with_no_text_yet() {
        showPane()
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveModelLoading).fetchSemanticsNodes().isNotEmpty() }
        modelLoad.countDown()
        waitUntil(10_000) { transcriber.starts.get() == 1 }
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveModelLoading).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun the_notice_clears_when_stop_begins_even_before_the_mic_has_stopped() {
        mic.stopGate = kotlinx.coroutines.CompletableDeferred()
        showPane(open = { null })
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        // The mic session is still Recording here (stop is held), so only the stopping flag can clear it.
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isEmpty() }
        assertTrue(mic.sessions.value is MicSessionState.Recording)
        mic.stopGate.complete(Unit)
        waitUntil(10_000) { queued == 1 }
    }

    @Test fun the_notice_follows_an_api_key_added_while_the_pane_is_open() {
        showPane(open = { null })
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { container.settingsRepository.setApiKey("synthetic-key") }
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModel).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun a_mic_error_re_translates_on_a_language_change() {
        mic.startResult = MicStartResult.PermissionRequired
        showPane(open = { null }, awaitAttach = false)
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.micPermissionRationale).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { uiStrings.value = En }
        waitUntil(10_000) { compose.onAllNodesWithText(En.micPermissionRationale).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun the_no_model_notice_does_not_promise_an_api_that_is_not_set() {
        showPane(open = { null })
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText(PtBr.ux.liveNoModel).fetchSemanticsNodes().isEmpty())
    }

    @Test fun with_an_api_key_the_notice_mentions_the_api() {
        runBlocking { container.settingsRepository.setApiKey("synthetic-key") }
        showPane(open = { null })
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModel).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun a_language_change_mid_recording_re_resolves_the_notice() {
        showPane(open = { null })
        waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { uiStrings.value = En }
        waitUntil(10_000) { compose.onAllNodesWithText(En.ux.liveNoModelNoApi).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText(PtBr.ux.liveNoModelNoApi).fetchSemanticsNodes().isEmpty())
    }

    // #205: markers have no other copy, so they are saved when dropped, not only at Stop.
    @Test fun a_marker_is_saved_the_moment_it_is_dropped() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.ux.liveMark).performClick()
        waitOnMainLooper { recordingRow().transcript?.contains("## Markers") == true }
        assertTrue(recordingRow().transcript!!.contains("1:05"))
        assertEquals("recording", recordingRow().status)
    }

    // #205: a stop from the notification used to leave the row "recording" forever.
    @Test fun a_stop_from_the_notification_finalizes_the_row() {
        showPane(reportedFailures = mutableListOf())
        mic.sessions.value = MicSessionState.Stopped(File(recordingsDirectoryPath(), "$recordingId.wav").path, 1, 64, mic.starts.toLong())
        waitOnMainLooper { queued == 1 }
        assertEquals("queued", recordingRow().status)
    }

    // The in-app Stop keeps finalizing itself with the live text; the observer must not pre-empt it.
    // The live text the pane holds is kept when the notification stops the mic.
    @Test fun a_notification_stop_keeps_the_live_text_the_pane_holds() {
        showPane(reportedFailures = mutableListOf())
        modelLoad.countDown()
        waitUntil(10_000) { transcriber.starts.get() == 1 }
        transcriber.onPartial("Words heard live")
        waitUntil(10_000) { container.micRecordings.liveText(recordingId) == "Words heard live" }
        mic.sessions.value = MicSessionState.Stopped(File(recordingsDirectoryPath(), "$recordingId.wav").path, 1, 64, mic.starts.toLong())
        waitOnMainLooper { queued == 1 }
        assertEquals("Words heard live", recordingRow().transcript)
    }

    // A marker dropped just before Stop must not overwrite the final live text.
    @Test fun a_marker_write_after_finalize_does_not_overwrite_the_final_text() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        modelLoad.countDown()
        waitUntil(10_000) { queued == 1 }
        val finalText = recordingRow().transcript
        mic.currentMarkers = mic.currentMarkers + MicMarker(offsetMs = 90_000, label = "late marker")
        runBlocking { persistLiveMarkers(container, recordingId) }
        assertEquals(finalText, recordingRow().transcript)
    }

    @Test fun the_in_app_stop_is_not_pre_empted_by_the_stop_observer() {
        showPane(reportedFailures = mutableListOf())
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        modelLoad.countDown()
        waitUntil(10_000) { queued == 1 }
        assertEquals(SyntheticTranscriber.FINAL, recordingRow().transcript)
        assertEquals(1, queued)
    }

    @Test fun a_stuck_load_cannot_hold_stop_past_the_limit_and_is_still_finished_later() {
        showPane(finishWaitMs = 200)
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()

        waitUntil(10_000) { queued == 1 }
        assertEquals("queued", recordingRow().status)
        assertEquals(null, recordingRow().transcript)
        assertEquals(0, transcriber.finishes.get())

        modelLoad.countDown()
        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "Late transcriber was never finished")
    }

    @Test fun backlog_overflow_drops_the_oldest_audio_and_saves_no_text_with_a_gap() {
        showPane()
        val extra = 5
        (1..LIVE_PCM_BACKLOG_CHUNKS + extra).forEach { mic.onPcmChunk!!.invoke(byteArrayOf(it.toByte())) }
        modelLoad.countDown()

        waitUntil(10_000) { transcriber.pushed.size == LIVE_PCM_BACKLOG_CHUNKS }
        assertEquals((extra + 1).toByte(), transcriber.pushed.first())

        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        waitUntil(10_000) { queued == 1 }
        assertEquals(null, recordingRow().transcript)
    }

    @Test fun leaving_skips_the_queued_backlog_and_finishes_immediately() {
        val held = CountDownLatch(1)
        transcriber.pushGate = held
        showPane()
        mic.onPcmChunk!!.invoke(byteArrayOf(1))
        modelLoad.countDown()
        assertTrue(transcriber.pushEntered.await(10, TimeUnit.SECONDS), "First push never started")
        mic.onPcmChunk!!.invoke(byteArrayOf(2))
        mic.onPcmChunk!!.invoke(byteArrayOf(3))

        leaveScreen()
        held.countDown()

        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "Transcriber was never finished")
        assertEquals(listOf<Byte>(1), transcriber.pushed.toList())
    }

    @Test fun a_failed_start_still_finishes_the_transcriber_and_leaves_capture_alone() {
        transcriber.failStart = true
        showPane()
        modelLoad.countDown()

        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "Transcriber was never finished")
        assertEquals(0, mic.stops)
        assertTrue(mic.onPcmChunk != null)

        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        waitUntil(10_000) { queued == 1 }
        assertEquals("queued", recordingRow().status)
    }

    @Test fun returning_to_a_running_recording_reattaches_without_a_second_capture() {
        modelLoad.countDown()
        showPane()
        waitUntil(10_000) { transcriber.starts.get() == 1 }
        leaveScreen()
        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "First transcriber was never finished")
        assertEquals(null, mic.onPcmChunk)

        showPane()
        waitUntil(10_000) { transcriber.starts.get() == 2 }
        mic.onPcmChunk!!.invoke(byteArrayOf(9))

        waitUntil(10_000) { transcriber.pushed.toList() == listOf<Byte>(9) }
        assertEquals(1, mic.starts)
        assertEquals(0, mic.stops)
    }

    @Test fun markers_are_kept_when_nothing_was_transcribed() {
        mic.currentMarkers = listOf(MicMarker(offsetMs = 65_000, label = "Synthetic note"))
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        modelMissing = true
        modelLoad.countDown()

        waitUntil(10_000) { queued == 1 }
        assertEquals("## Markers\n- **1:05** Synthetic note\n", recordingRow().transcript)
    }

    @Test fun saving_markers_again_replaces_the_section_instead_of_adding_a_second() = runBlocking {
        val path = File(recordingsDirectoryPath(), "$recordingId.wav").path
        val session = assertIs<MicStartResult.Started>(container.micRecordings.startOrAttach(recordingId, path)).session
        container.repository.updateTranscript(recordingId, "Synthetic words\n\n## Markers\n- **0:01** Old\n")

        assertIs<MicStopResult.Stopped>(finalizeMicRecording(container, recordingId, path, session, markers = listOf(MicMarker(1_000, "Old"), MicMarker(2_000, "New"))))

        assertEquals("Synthetic words\n\n## Markers\n- **0:01** Old\n- **0:02** New\n", recordingRow().transcript)
    }

    @Test fun a_failed_stop_reported_by_the_failure_observer_keeps_status_and_markers() {
        mic.stopFailure = "Synthetic stop failure"
        mic.currentMarkers = listOf(MicMarker(offsetMs = 1_000, label = ""))
        val reported = mutableListOf<String>()
        showPane(reportedFailures = reported)
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        waitUntil(10_000) { reported.isNotEmpty() }
        modelLoad.countDown()
        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "Transcriber was never finished")

        waitUntil(10_000) { container.activeMicRecordingId == null }
        assertEquals(listOf("Synthetic stop failure"), reported)
        assertEquals("failed", recordingRow().status)
        assertEquals(0, queued)
        assertTrue(recordingRow().transcript.orEmpty().contains("- **0:01**"))
    }

    @Test fun a_failed_stop_reaching_the_finalizer_first_shows_the_error_and_saves_the_transcript() {
        mic.stopFailure = "Synthetic stop failure"
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        modelLoad.countDown()

        waitUntil(10_000) { compose.onAllNodesWithText("Synthetic stop failure").fetchSemanticsNodes().isNotEmpty() }
        val row = recordingRow()
        assertEquals("failed", row.status)
        assertEquals(SyntheticTranscriber.FINAL, row.transcript)
        assertEquals(null, container.activeMicRecordingId)
        assertEquals(0, queued)
    }

    /** Runs [scenario] and returns every Compose state write made off the main thread while it ran. */
    private fun offMainWrites(scenario: () -> Unit): List<String> {
        val mainThread = Looper.getMainLooper().thread
        val found = CopyOnWriteArrayList<String>()
        val handle = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver {
            if (Thread.currentThread() !== mainThread) {
                // No frame filter: a write from the Compose runtime (a collector resumed inline) has none of ours.
                val frames = Throwable().stackTrace.filterNot { it.className.startsWith("androidx.compose.runtime.snapshots") }
                found += "${Thread.currentThread().name}: " + frames.take(8).joinToString(" <- ")
            }
        }
        try {
            scenario()
        } finally {
            handle.dispose()
        }
        return found
    }

    /** From another thread, as the transcriber and the capture thread call in; the test thread itself is Main. */
    private fun fromAnotherThread(block: () -> Unit) {
        var failure: Throwable? = null
        val t = Thread { try { block() } catch (e: Throwable) { failure = e } }
        t.start()
        t.join(10_000)
        assertFalse(t.isAlive, "the call from another thread did not return")
        failure?.let { throw it }
    }

    /**
     * The failure in CI (CalledFromWrongThreadException at ViewRootImpl, then an ArrayIndexOutOfBoundsException) is
     * what an off-Main state write looks like when the Compose test clock resumes a collector inline on the writing
     * thread. Production resumes on Main, so the screen must not depend on that: it writes its state on Main.
     * This runs a live partial from the transcriber's thread, dropped audio from the capture thread (the transcriber
     * is held, so drops are certain) and a failed Stop, and records every off-Main state write, ours or not.
     */
    @Test fun the_pane_writes_no_state_off_the_main_thread_through_partials_drops_and_a_failed_stop() {
        val offMain = offMainWrites {
            mic.stopFailure = "Synthetic stop failure"
            transcriber.pushGate = CountDownLatch(1) // the first push blocks, so the queue fills and drops
            showPane()
            modelLoad.countDown()
            waitUntil(10_000) { transcriber.starts.get() > 0 }
            fromAnotherThread { transcriber.onPartial("**partial** · 0:01\nSynthetic words\n") }
            fromAnotherThread { repeat(LIVE_PCM_BACKLOG_CHUNKS + 8) { mic.onPcmChunk?.invoke(ByteArray(2)) } }
            transcriber.pushGate.countDown()
            compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
            waitUntil(10_000) { compose.onAllNodesWithText("Synthetic stop failure").fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(offMain.isEmpty(), "state written off the main thread:\n${offMain.joinToString("\n")}")
    }

    @Test fun a_partial_is_published_for_a_notification_stop_the_moment_it_arrives() {
        showPane()
        modelLoad.countDown()
        waitUntil(10_000) { transcriber.starts.get() > 0 }
        fromAnotherThread { transcriber.onPartial("**partial** · 0:01\nSynthetic words\n") }
        // No looper idle, no frame: a screen that is off produces none.
        assertEquals("Synthetic words\n", container.micRecordings.liveText(recordingId))
    }

    @Test fun pause_and_marker_taps_write_their_state_on_main() {
        val offMain = offMainWrites {
            showPane()
            modelLoad.countDown()
            waitUntil(10_000) { transcriber.starts.get() > 0 }
            compose.onNodeWithText(PtBr.ux.livePause, useUnmergedTree = true).performClick()
            waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveResume, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(PtBr.ux.liveMark, useUnmergedTree = true).performClick()
            val oneMarker = PtBr.ux.liveMarkersFormat.replace("%s", MicInputSource.Mic.localizedLabel(PtBr)).replace("%d", "1")
            waitUntil(10_000) { compose.onAllNodesWithText(oneMarker, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(offMain.isEmpty(), "state written off the main thread:\n${offMain.joinToString("\n")}")
    }

    @Test fun a_late_no_model_notice_from_the_opener_thread_is_written_on_main() {
        val offMain = offMainWrites {
            modelMissing = true
            showPane()
            modelLoad.countDown()
            waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveModelUnavailable).fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(offMain.isEmpty(), "state written off the main thread:\n${offMain.joinToString("\n")}")
    }

    @Test fun the_last_partial_is_the_fallback_even_when_the_screen_has_gone_and_the_final_text_never_comes() {
        mic.currentMarkers = emptyList()
        transcriber.finishGate = CountDownLatch(1) // finish() never returns within the wait
        val waitMs = 5_000L
        showPane(finishWaitMs = waitMs)
        modelLoad.countDown()
        waitUntil(10_000) { transcriber.starts.get() > 0 }
        val stoppedAt = System.nanoTime()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick() // Stop is now waiting for the final text
        leaveScreen() // the screen's scope is cancelled; posts to it are dropped
        fromAnotherThread { transcriber.onPartial("**partial** · 0:01\nLast words\n") }
        // The partial must be in before the wait times out, or this would pass on the timeout alone.
        assertTrue((System.nanoTime() - stoppedAt) / 1_000_000 < waitMs / 2, "the partial arrived too late for the test to mean anything")
        waitOnMainLooper { recordingRow().status != "recording" }
        transcriber.finishGate.countDown()
        assertEquals("Last words\n", recordingRow().transcript, "the fallback text must be the transcriber's last partial")
    }

    @Test fun only_real_text_is_savable_and_never_with_the_preview_header() {
        assertEquals(null, savableLiveTranscript("**partial** · 0:00\n"))
        assertEquals(null, savableLiveTranscript("**partial** · 1:05\n\n"))
        assertEquals(null, savableLiveTranscript("**Speaker 1** · 0:00\n\n"))
        assertEquals("Synthetic words\n", savableLiveTranscript("**partial** · 1:05\nSynthetic words\n"))
        assertEquals("**Speaker 1** · 0:00\nSynthetic words\n", savableLiveTranscript("**Speaker 1** · 0:00\nSynthetic words\n"))
    }

    private fun recordingRow() = runBlocking { container.repository.observeRecording(recordingId).first() }!!

    /**
     * compose.waitUntil that also idles the main looper on each poll. The pane hops its state writes to Main, and
     * Robolectric's paused looper runs a posted task only when something idles it; waitUntil's own sleep does not.
     */
    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) = compose.waitUntil(timeoutMillis) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    /** The compose rule has nothing to wait on once the screen has been destroyed. */
    private fun waitOnMainLooper(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "Condition not met after leaving the screen")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun leaveScreen() {
        if (destroyed) return
        destroyed = true
        activity.pause().stop().destroy()
    }

    private class SyntheticTranscriber : StreamingTranscriber {
        val starts = AtomicInteger()
        val finishes = AtomicInteger()
        val finished = CountDownLatch(1)
        val pushed = CopyOnWriteArrayList<Byte>()
        override val engineId = "synthetic-slow-open"
        override var onPartial: (String) -> Unit = {}
        @Volatile var failStart = false
        @Volatile var pushGate = CountDownLatch(0)
        val pushEntered = CountDownLatch(1)
        override fun start(sampleRateHz: Int, languageHint: String?, initialPrompt: String?) {
            starts.incrementAndGet()
            check(!failStart) { "Synthetic start failure" }
        }
        override fun pushPcm(pcm16Le: ByteArray) {
            pushEntered.countDown()
            pushGate.await(30, TimeUnit.SECONDS)
            pushed += pcm16Le.toList()
        }
        @Volatile var finishGate = CountDownLatch(0)
        override fun finish(): String { finishes.incrementAndGet(); finishGate.await(30, TimeUnit.SECONDS); finished.countDown(); return FINAL }
        companion object { const val FINAL = "Synthetic final transcript" }
    }

    private class SyntheticCapture : MicCapture {
        override val sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
        override val isSupported = true
        override val isCapturing get() = sessions.value is MicSessionState.Recording
        override val isPaused = false
        override val markers get() = currentMarkers
        @Volatile var currentMarkers = emptyList<MicMarker>()
        @Volatile var stopFailure: String? = null
        @Volatile var startResult: MicStartResult? = null
        @Volatile var stopGate = kotlinx.coroutines.CompletableDeferred<Unit>().also { it.complete(Unit) }
        override var onPcmChunk: ((ByteArray) -> Unit)? = null
        @Volatile var starts = 0
        @Volatile var stops = 0
        private var session: MicSessionIdentity? = null
        override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult {
            starts++
            startResult?.let { return it }
            val started = MicSessionIdentity(starts.toLong(), absolutePath)
            session = started
            sessions.value = MicSessionState.Recording(absolutePath, 0, started.token)
            return MicStartResult.Started(started)
        }
        override suspend fun stop(): MicStopResult {
            val current = session!!
            stopGate.await()
            stopFailure?.let { message ->
                sessions.value = MicSessionState.Failed(message, session = current)
                return MicStopResult.Failed(message)
            }
            // Like the platform: a repeated stop returns the retained terminal state.
            if (sessions.value !is MicSessionState.Stopped) stops++
            sessions.value = MicSessionState.Stopped(current.absolutePath, 1, 64, current.token)
            return MicStopResult.Stopped(current.absolutePath, 1, 64, current)
        }
        // They suspend onto a worker, as the platform does, so a caller that does not hop back resumes off Main.
        override suspend fun pause() = withContext(Dispatchers.Default) { MicPauseResult.Ok }
        override suspend fun resume() = withContext(Dispatchers.Default) { MicPauseResult.Ok }
        override suspend fun addMarker(label: String): MicMarkerResult = withContext(Dispatchers.Default) {
            val marker = MicMarker(offsetMs = 65_000, label = label)
            currentMarkers = currentMarkers + marker
            MicMarkerResult.Ok(marker)
        }
    }
}
