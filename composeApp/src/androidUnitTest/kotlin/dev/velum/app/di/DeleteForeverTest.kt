package dev.velum.app.di

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.ai.pipeline.RecordingProcessor
import dev.velum.ai.pipeline.ThermalPolicy
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.platform.MicCapture
import dev.velum.app.platform.MicInputSource
import dev.velum.app.platform.MicMarker
import dev.velum.app.platform.MicMarkerResult
import dev.velum.app.platform.MicPauseResult
import dev.velum.app.platform.MicSessionIdentity
import dev.velum.app.platform.MicSessionState
import dev.velum.app.platform.MicStartResult
import dev.velum.app.platform.MicStopResult
import dev.velum.app.platform.installAppContext
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.ApiModelOverride
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** #387: Delete forever while a job or the mic is using the recording, and what the screen says when it fails. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class DeleteForeverTest {
    @get:Rule val compose = createEmptyComposeRule()

    private class FakeCapture : MicCapture {
        override val sessions = MutableStateFlow<MicSessionState>(MicSessionState.Idle)
        override val isSupported = true
        override val isCapturing get() = sessions.value is MicSessionState.Recording
        override val isPaused = false
        override val markers = emptyList<MicMarker>()
        override var onPcmChunk: ((ByteArray) -> Unit)? = null
        var stops = 0
        var failStop = false
        override suspend fun start(absolutePath: String, inputSource: MicInputSource): MicStartResult {
            val session = MicSessionIdentity(1, absolutePath)
            sessions.value = MicSessionState.Recording(absolutePath, 0, session.token)
            return MicStartResult.Started(session)
        }
        override suspend fun stop(): MicStopResult {
            stops++
            if (failStop) return MicStopResult.Failed("stop failed")
            val path = (sessions.value as MicSessionState.Recording).absolutePath
            sessions.value = MicSessionState.Stopped(path, 1, 0, token = 1)
            return MicStopResult.Stopped(path, 1, 0)
        }
        override suspend fun pause() = MicPauseResult.Unsupported
        override suspend fun resume() = MicPauseResult.Unsupported
        override suspend fun addMarker(label: String) = MicMarkerResult.Unsupported
    }

    /** Holds a processing run at its first thermal check, until it is cancelled. */
    private class HoldingThermal : ThermalPolicy {
        val held = CompletableDeferred<Unit>()
        override fun isHot() = true
        override suspend fun awaitCool(onPaused: suspend (message: String) -> Unit) {
            held.complete(Unit)
            awaitCancellation()
        }
    }

    private fun withContainer(
        mic: MicCapture? = null,
        thermal: ThermalPolicy? = null,
        jobs: ((RecordingProcessor, CoroutineScope) -> RecordingJobScheduler)? = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
        block: suspend (AppContainer) -> Unit,
    ) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        installAppContext(context)
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-delete-forever").toString())
        val files = Files.createTempDirectory("velum-delete-files").toFile()
        installRecordingFilesDir(files.path)
        val driver = DriverFactory(context)
        val store = InMemorySecureStore()
        val container = when {
            mic != null -> AppContainer(store, driver, modelStoreRoot = root, recordingJobSchedulerFactory = jobs, micCapture = mic)
            thermal != null -> AppContainer(store, driver, modelStoreRoot = root, recordingJobSchedulerFactory = jobs, thermalPolicy = thermal)
            else -> AppContainer(store, driver, modelStoreRoot = root, recordingJobSchedulerFactory = jobs)
        }
        try {
            runBlocking { block(container) }
        } finally {
            Dispatchers.resetMain()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            installRecordingFilesDir(null)
            files.deleteRecursively()
        }
    }

    private suspend fun AppContainer.addRow(id: String, status: String = "ready", path: String? = null, source: String = "phone") =
        repository.insertRecording(
            id = id, sessionId = null, folderId = null, title = "Synthetic", source = source, deviceSerial = null,
            startedAt = 1_700_000_000L, durationSeconds = 1, sizeBytes = 0, localPath = path,
            transcript = "**Ana** · 0:00\nSynthetic transcript.", summaryMarkdown = null, status = status,
        )

    @Test fun deleting_a_recording_drops_its_queued_work() {
        val cancelled = mutableListOf<String>()
        val jobs = object : RecordingJobScheduler {
            override fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?) = Unit
            override suspend fun cancel(recordingId: String) { cancelled += recordingId }
        }
        withContainer(jobs = { _, _ -> jobs }) { container ->
            container.addRow("rec-a")
            assertTrue(container.deleteRecordingForever("rec-a"))
            assertEquals(listOf("rec-a"), cancelled)
        }
    }

    @Test fun deleting_a_recording_waits_for_its_running_job() {
        val thermal = HoldingThermal()
        withContainer(thermal = thermal) { container ->
            container.addRow("rec-a")
            coroutineScope {
                val job = launch(Dispatchers.Default) {
                    container.recordingProcessor.process("rec-a", retranscribe = false).collect { }
                }
                thermal.held.await()
                try {
                    assertTrue(container.deleteRecordingForever("rec-a"))
                    assertTrue(job.isCompleted, "the row went while its job was still running")
                } finally {
                    job.cancel()
                }
            }
        }
    }

    @Test fun deleting_a_recording_stops_its_capture_and_its_file_goes() {
        val mic = FakeCapture()
        withContainer(mic = mic) { container ->
            val file = File(recordingsDirectoryPath(), "rec-a.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8)) }
            container.addRow("rec-a", status = "recording")
            assertNotNull(container.micRecordings.startOrAttach("rec-a", file.path) as? MicStartResult.Started)
            assertTrue(container.deleteRecordingForever("rec-a"))
            assertEquals(1, mic.stops, "the mic kept recording a deleted recording")
            assertEquals(null, container.micRecordings.owner.value, "the deleted recording still owns the mic")
            assertFalse(file.exists(), "the capture's file stayed")
        }
    }

    @Test fun failed_mic_stop_keeps_owner_recording_and_audio() {
        val mic = FakeCapture().apply { failStop = true }
        withContainer(mic = mic) { container ->
            val file = File(recordingsDirectoryPath(), "rec-a.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8)) }
            container.addRow("rec-a", status = "recording")
            container.micRecordings.startOrAttach("rec-a", file.path)
            assertFalse(container.deleteRecordingForever("rec-a"))
            assertNotNull(container.micRecordings.owner.value)
            assertNotNull(container.repository.getRecording("rec-a"))
            assertTrue(file.exists())
        }
    }

    @Test fun deleting_another_recording_leaves_the_capture_alone() {
        val mic = FakeCapture()
        withContainer(mic = mic) { container ->
            val file = File(recordingsDirectoryPath(), "rec-live.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8)) }
            container.addRow("rec-live", status = "recording")
            container.addRow("rec-other")
            container.micRecordings.startOrAttach("rec-live", file.path)
            assertTrue(container.deleteRecordingForever("rec-other"))
            assertEquals(0, mic.stops, "deleting another recording stopped the mic")
            assertTrue(file.exists())
        }
    }

    @Test fun a_draft_with_no_saved_path_loses_its_files() = withContainer { container ->
        val recordings = File(recordingsDirectoryPath()).apply { mkdirs() }
        val wav = File(recordings, "rec-draft.wav").apply { writeBytes(ByteArray(8)) }
        val partial = File(recordings, "rec-draft.wav.partial").apply { writeBytes(ByteArray(8)) }
        val other = File(recordings, "rec-other.wav").apply { writeBytes(ByteArray(8)) }
        container.addRow("rec-draft", status = "recording")
        assertTrue(container.deleteRecordingForever("rec-draft"))
        assertFalse(wav.exists(), "the draft's audio stayed")
        assertFalse(partial.exists(), "the draft's partial stayed")
        assertTrue(other.exists(), "another recording's file was deleted")
    }

    private fun showTrash(container: AppContainer) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            CompositionLocalProvider(LocalStrings provides PtBr) {
                VelumTheme(darkTheme = false) { FilesScreen(container, onOpenRecording = {}, onOpenSettings = {}) }
            }
        }
        compose.onNodeWithContentDescription(PtBr.filterSort).performClick()
        val trashLine = hasText(PtBr.trash) and hasClickAction()
        compose.waitUntil(10_000) { compose.onAllNodes(trashLine).fetchSemanticsNodes().isNotEmpty() }
        // By node, not coordinates: the sheet may still be sliding in.
        compose.onNode(trashLine).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithContentDescription(PtBr.ux.emptyTrash).fetchSemanticsNode() }.isSuccess
        }
    }

    private fun emptyTrash() {
        compose.onNodeWithContentDescription(PtBr.ux.emptyTrash).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(PtBr.confirm).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test fun the_screen_says_so_when_a_delete_fails() = withContainer { container ->
        // A non-empty directory at the path can't be deleted as a file.
        val stuck = File(recordingsDirectoryPath(), "rec-stuck.wav").apply { mkdirs() }
        File(stuck, "inner").writeBytes(ByteArray(8))
        container.addRow("rec-stuck", path = stuck.path)
        container.repository.trashRecording("rec-stuck")
        showTrash(container)
        emptyTrash()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(PtBr.added.deleteForeverFailed).fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(container.repository.getRecording("rec-stuck"), "the recording went but its audio stayed")
    }

    @Test fun the_screen_stays_quiet_when_a_delete_works() = withContainer { container ->
        container.addRow("rec-fine")
        container.repository.trashRecording("rec-fine")
        showTrash(container)
        emptyTrash()
        compose.waitUntil(10_000) { runBlocking { container.repository.getRecording("rec-fine") == null } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(PtBr.added.deleteForeverFailed).fetchSemanticsNodes().isEmpty())
    }
}
