package dev.velum.app.platform

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MicOwnershipUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun opening_stale_B_then_stopping_and_disposing_it_cannot_attach_stop_or_clear_A() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("velum.db")
        val root = Files.createTempDirectory("velum-owner-ui-synthetic").toFile()
        installRecordingFilesDir(root.path)
        val mic = SyntheticCapture()
        var queued = 0
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context),
            modelStoreRoot = ModelStoreDir(File(root, "models").path), micCapture = mic,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> queued++; dev.velum.ai.pipeline.EnqueueResult.STARTED_OR_REPLACED } })
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var destroyed = false
        try {
            val a = runBlocking { container.createLocalRecording(status = "recording") }
            val b = runBlocking { container.createLocalRecording(status = "recording") }
            runBlocking { container.repository.updateTranscript(b, "Synthetic stale B transcript") }
            val wav = File(recordingsDirectoryPath(), "$a.wav").also { WavWriter(it.path).apply { writePcm(ByteArray(32) { 7 }) }.finish() }
            val bytes = wav.readBytes()
            val started = assertIs<MicStartResult.Started>(runBlocking { container.micRecordings.startOrAttach(a, wav.path) })
            var chunks = 0
            val subscription = container.micRecordings.attach(a, started.session) { chunks++ }
            assertTrue(subscription != null)
            val listener = mic.onPcmChunk
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) { RecordingDetailScreen(container, b, onBack = {}) }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic stale B transcript", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(mic.onPcmChunk === listener); assertEquals(a, container.activeMicRecordingId)
            compose.onNodeWithContentDescription(PtBr.stopRecording).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(PtBr.micStartFailed).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("recording", runBlocking { container.repository.observeRecording(b).first() }!!.status)
            assertEquals(0, mic.stops); assertEquals(1, mic.starts); assertEquals(0, queued)
            assertEquals(null, runBlocking { container.repository.observeRecording(b).first() }!!.local_path)
            assertEquals(a, container.activeMicRecordingId); assertContentEquals(bytes, wav.readBytes())
            activity.pause().stop().destroy(); destroyed = true
            assertTrue(mic.onPcmChunk === listener)
            mic.onPcmChunk?.invoke(byteArrayOf(1, 2)); assertEquals(1, chunks)
            subscription!!.close()
        } finally {
            if (!destroyed) activity.pause().stop().destroy()
            installRecordingFilesDir(context.filesDir.path)
            container.databaseHandle.driver.close(); root.deleteRecursively()
        }
    }

    @Test fun controller_rejects_non_owner_attach_and_stale_disposal_preserves_a_replacement_listener() = runBlocking {
        val mic = SyntheticCapture(); val owner = MicRecordingController(mic)
        val started = assertIs<MicStartResult.Started>(owner.startOrAttach("A", "/synthetic/A.wav"))
        val first = owner.attach("A", started.session) { }
        val current = owner.attach("A", started.session) { }
        val listener = mic.onPcmChunk
        assertEquals(null, owner.attach("B", started.session) { error("Foreign audio") })
        assertIs<MicStartResult.Failed>(owner.startOrAttach("B", "/synthetic/B.wav"))
        first!!.close(); assertTrue(mic.onPcmChunk === listener)
        current!!.close(); assertEquals(null, mic.onPcmChunk)
        assertEquals(1, mic.starts); assertEquals(0, mic.stops)
    }

    private class SyntheticCapture : MicCapture {
        override val sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
        override val isSupported = true
        override val isCapturing get() = sessions.value is MicSessionState.Recording
        override val isPaused = false
        override val markers = emptyList<MicMarker>()
        override var onPcmChunk: ((ByteArray) -> Unit)? = null
        var starts = 0; var stops = 0
        override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult {
            val session = MicSessionIdentity((++starts).toLong(), absolutePath)
            sessions.value = MicSessionState.Recording(absolutePath, 0, session.token)
            return MicStartResult.Started(session)
        }
        override suspend fun stop(): MicStopResult { stops++; return MicStopResult.Failed("Synthetic unexpected stop") }
        override suspend fun pause() = MicPauseResult.Ok
        override suspend fun resume() = MicPauseResult.Ok
        override suspend fun addMarker(label: String) = MicMarkerResult.NotCapturing
    }
}
