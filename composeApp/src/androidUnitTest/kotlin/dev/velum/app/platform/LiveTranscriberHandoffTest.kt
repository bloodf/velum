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
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.engine.StreamingTranscriber
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("velum.db")
        root = Files.createTempDirectory("velum-handoff-synthetic").toFile()
        installRecordingFilesDir(root.path)
        container = AppContainer(InMemorySecureStore(), DriverFactory(context),
            modelStoreRoot = ModelStoreDir(File(root, "models").path), micCapture = mic,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> queued++ } })
        recordingId = runBlocking { container.createLocalRecording(status = "recording") }
    }

    private fun showPane(
        finishWaitMs: Long = 30_000,
        reportedFailures: MutableList<String>? = null,
        awaitAttach: Boolean = true,
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
            CompositionLocalProvider(LocalStrings provides PtBr) {
                VelumTheme(darkTheme = false) {
                    // As in App: the failure observer lives in the composition.
                    if (reportedFailures != null) LaunchedEffect(Unit) { observeMicFailures(container) { reportedFailures += it } }
                    LiveTranscriptPane(container, recordingId, title = "", openTranscriber = open, finishWaitMs = finishWaitMs)
                }
            }
        }
        if (awaitAttach) compose.waitUntil(10_000) { mic.onPcmChunk != null }
    }

    @After fun tearDown() {
        modelLoad.countDown()
        transcriber.pushGate.countDown()
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

        compose.waitUntil(10_000) { transcriber.pushed.size == 3 }
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

        compose.waitUntil(10_000) { mic.stops == 1 && container.activeMicRecordingId == null }
        val row = runBlocking { container.repository.observeRecording(recordingId).first() }!!
        assertEquals("queued", row.status)
        assertEquals(SyntheticTranscriber.FINAL, row.transcript)
        assertEquals(1, transcriber.finishes.get())
        assertTrue(compose.onAllNodesWithText(PtBr.micStartFailed).fetchSemanticsNodes().isEmpty())
    }

    @Test fun stop_turns_the_mic_off_before_the_transcript_wait_ends() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()

        compose.waitUntil(10_000) { mic.stops == 1 }
        assertEquals(0, transcriber.finishes.get())
        assertEquals(0, queued)
        assertEquals("recording", recordingRow().status)

        // Stop is non-cancellable: let it finish before teardown closes the database.
        modelLoad.countDown()
        compose.waitUntil(10_000) { queued == 1 }
    }

    @Test fun reentering_during_the_transcript_wait_shows_no_error_and_opens_no_second_model() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        compose.waitUntil(10_000) { mic.stops == 1 }
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
        compose.waitUntil(10_000) { transcriber.starts.get() == 1 }
        leaveScreen()
        runBlocking { mic.stop() }

        showPane(awaitAttach = false)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(PtBr.micStartFailed).fetchSemanticsNodes().isEmpty())
        assertEquals(1, opens.get())

        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        compose.waitUntil(10_000) { queued == 1 }
        assertEquals("queued", recordingRow().status)
    }

    @Test fun leaving_during_the_transcript_wait_still_finalizes_and_queues() {
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        compose.waitUntil(10_000) { mic.stops == 1 }

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

        compose.waitUntil(10_000) { queued == 1 }
        val row = recordingRow()
        assertEquals("queued", row.status)
        assertEquals(null, row.transcript)
    }

    // #196: with no usable streaming model the pane says so, shows no stand-in text, and saves the audio only.
    @Test fun no_streaming_model_shows_a_message_not_fake_text_and_saves_audio_only() {
        showPane(open = { opens.incrementAndGet(); null })
        compose.waitUntil(10_000) { compose.onAllNodesWithText(PtBr.ux.liveNoModel).fetchSemanticsNodes().isNotEmpty() }
        mic.onPcmChunk!!.invoke(ByteArray(1024))
        listOf("partial", "chunk", "final").forEach {
            assertTrue(compose.onAllNodesWithText(it, substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty(), "fake text '$it' shown")
        }
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()

        compose.waitUntil(10_000) { queued == 1 }
        val row = recordingRow()
        assertEquals("queued", row.status)
        assertEquals(null, row.transcript)
        assertEquals(1, mic.stops)
    }

    @Test fun a_stuck_load_cannot_hold_stop_past_the_limit_and_is_still_finished_later() {
        showPane(finishWaitMs = 200)
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()

        compose.waitUntil(10_000) { queued == 1 }
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

        compose.waitUntil(10_000) { transcriber.pushed.size == LIVE_PCM_BACKLOG_CHUNKS }
        assertEquals((extra + 1).toByte(), transcriber.pushed.first())

        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        compose.waitUntil(10_000) { queued == 1 }
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
        compose.waitUntil(10_000) { queued == 1 }
        assertEquals("queued", recordingRow().status)
    }

    @Test fun returning_to_a_running_recording_reattaches_without_a_second_capture() {
        modelLoad.countDown()
        showPane()
        compose.waitUntil(10_000) { transcriber.starts.get() == 1 }
        leaveScreen()
        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "First transcriber was never finished")
        assertEquals(null, mic.onPcmChunk)

        showPane()
        compose.waitUntil(10_000) { transcriber.starts.get() == 2 }
        mic.onPcmChunk!!.invoke(byteArrayOf(9))

        compose.waitUntil(10_000) { transcriber.pushed.toList() == listOf<Byte>(9) }
        assertEquals(1, mic.starts)
        assertEquals(0, mic.stops)
    }

    @Test fun markers_are_kept_when_nothing_was_transcribed() {
        mic.currentMarkers = listOf(MicMarker(offsetMs = 65_000, label = "Synthetic note"))
        showPane()
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        modelMissing = true
        modelLoad.countDown()

        compose.waitUntil(10_000) { queued == 1 }
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
        compose.waitUntil(10_000) { reported.isNotEmpty() }
        modelLoad.countDown()
        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "Transcriber was never finished")

        compose.waitUntil(10_000) { container.activeMicRecordingId == null }
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

        compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic stop failure").fetchSemanticsNodes().isNotEmpty() }
        val row = recordingRow()
        assertEquals("failed", row.status)
        assertEquals(SyntheticTranscriber.FINAL, row.transcript)
        assertEquals(null, container.activeMicRecordingId)
        assertEquals(0, queued)
    }

    @Test fun only_real_text_is_savable_and_never_with_the_preview_header() {
        assertEquals(null, savableLiveTranscript("**partial** · 0:00\n"))
        assertEquals(null, savableLiveTranscript("**partial** · 1:05\n\n"))
        assertEquals(null, savableLiveTranscript("**Speaker 1** · 0:00\n\n"))
        assertEquals("Synthetic words\n", savableLiveTranscript("**partial** · 1:05\nSynthetic words\n"))
        assertEquals("**Speaker 1** · 0:00\nSynthetic words\n", savableLiveTranscript("**Speaker 1** · 0:00\nSynthetic words\n"))
    }

    private fun recordingRow() = runBlocking { container.repository.observeRecording(recordingId).first() }!!

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
        override fun finish(): String { finishes.incrementAndGet(); finished.countDown(); return FINAL }
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
        override var onPcmChunk: ((ByteArray) -> Unit)? = null
        @Volatile var starts = 0
        @Volatile var stops = 0
        private var session: MicSessionIdentity? = null
        override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult {
            starts++
            val started = MicSessionIdentity(starts.toLong(), absolutePath)
            session = started
            sessions.value = MicSessionState.Recording(absolutePath, 0, started.token)
            return MicStartResult.Started(started)
        }
        override suspend fun stop(): MicStopResult {
            val current = session!!
            stopFailure?.let { message ->
                sessions.value = MicSessionState.Failed(message, session = current)
                return MicStopResult.Failed(message)
            }
            // Like the platform: a repeated stop returns the retained terminal state.
            if (sessions.value !is MicSessionState.Stopped) stops++
            sessions.value = MicSessionState.Stopped(current.absolutePath, 1, 64, current.token)
            return MicStopResult.Stopped(current.absolutePath, 1, 64, current)
        }
        override suspend fun pause() = MicPauseResult.Ok
        override suspend fun resume() = MicPauseResult.Ok
        override suspend fun addMarker(label: String) = MicMarkerResult.NotCapturing
    }
}
