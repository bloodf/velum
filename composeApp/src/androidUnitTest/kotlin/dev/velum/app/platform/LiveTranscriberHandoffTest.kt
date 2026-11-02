package dev.velum.app.platform

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
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
    private var destroyed = false
    private var queued = 0
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
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            CompositionLocalProvider(LocalStrings provides PtBr) {
                VelumTheme(darkTheme = false) {
                    LiveTranscriptPane(container, recordingId, title = "", openTranscriber = {
                        assertTrue(modelLoad.await(30, TimeUnit.SECONDS), "Synthetic model load was never released")
                        check(!modelMissing) { "Synthetic model failure" }
                        transcriber
                    })
                }
            }
        }
        compose.waitUntil(10_000) { mic.onPcmChunk != null }
    }

    @After fun tearDown() {
        modelLoad.countDown()
        leaveScreen()
        installRecordingFilesDir(ApplicationProvider.getApplicationContext<Application>().filesDir.path)
        container.databaseHandle.driver.close()
        root.deleteRecursively()
    }

    @Test fun capture_starts_during_the_load_and_its_first_audio_reaches_the_transcriber() {
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
        leaveScreen()
        assertEquals(0, transcriber.finishes.get())

        modelLoad.countDown()

        assertTrue(transcriber.finished.await(10, TimeUnit.SECONDS), "Late transcriber was never finished")
        assertEquals(1, transcriber.finishes.get())
        assertEquals(0, mic.stops)
    }

    @Test fun stop_during_the_load_finalizes_the_recording_with_the_transcript() {
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
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()

        compose.waitUntil(10_000) { mic.stops == 1 }
        assertEquals(0, transcriber.finishes.get())
        assertEquals(0, queued)
        assertEquals("recording", recordingRow().status)
    }

    @Test fun leaving_during_the_transcript_wait_still_finalizes_and_queues() {
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
        compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
        modelMissing = true
        modelLoad.countDown()

        compose.waitUntil(10_000) { queued == 1 }
        val row = recordingRow()
        assertEquals("queued", row.status)
        assertEquals(null, row.transcript)
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
        override fun start(sampleRateHz: Int, languageHint: String?, initialPrompt: String?) { starts.incrementAndGet() }
        override fun pushPcm(pcm16Le: ByteArray) { pushed += pcm16Le.toList() }
        override fun finish(): String { finishes.incrementAndGet(); finished.countDown(); return FINAL }
        companion object { const val FINAL = "Synthetic final transcript" }
    }

    private class SyntheticCapture : MicCapture {
        override val sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
        override val isSupported = true
        override val isCapturing get() = sessions.value is MicSessionState.Recording
        override val isPaused = false
        override val markers = emptyList<MicMarker>()
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
