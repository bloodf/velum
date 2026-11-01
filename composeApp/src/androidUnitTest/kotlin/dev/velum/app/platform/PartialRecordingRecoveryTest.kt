package dev.velum.app.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PartialRecordingRecoveryTest {
    @Test fun conflicting_valid_files_do_not_change_metadata_or_queue_either_version() = fixture {
        val target = wave("$id.wav", 1); val partial = wave("$id.wav.partial", 2)
        val original = snapshot(); var queued = 0
        val report = recoverPartialMicRecordings(root.path, container.repository, onRecovered = { queued++ })
        assertIs<WavRecoveryResult.Conflict>(report.deferred.single()); assertEquals(0, queued)
        assertEquals("recording", row().status); assertEquals(null, row().local_path)
        assertSnapshot(original); assertTrue(target.exists()); assertTrue(partial.exists())
    }
    @Test fun invalid_partial_with_a_valid_final_is_reported_without_selecting_or_overwriting_audio() = fixture {
        wave("$id.wav", 1); File(root, "$id.wav.partial").writeBytes(byteArrayOf(1, 2, 3))
        val original = snapshot(); var queued = 0
        val report = recoverPartialMicRecordings(root.path, container.repository, onRecovered = { queued++ })
        assertIs<WavRecoveryResult.Invalid>(report.deferred.single()); assertEquals(0, queued)
        assertEquals("recording", row().status); assertSnapshot(original)
    }
    @Test fun valid_partial_is_published_once_original_is_retained_and_repeat_does_not_queue_again() = fixture {
        val partial = wave("$id.wav.partial", 1); val bytes = partial.readBytes(); var queued = 0
        val first = recoverPartialMicRecordings(root.path, container.repository, onRecovered = { queued++ })
        assertEquals(listOf(id), first.recoveredIds); assertEquals("queued", row().status)
        assertEquals(File(root, "$id.wav").path, row().local_path); assertContentEquals(bytes, partial.readBytes())
        val original = snapshot()
        val second = recoverPartialMicRecordings(root.path, container.repository, onRecovered = { queued++ })
        assertEquals(1, queued); assertEquals(1, second.skippedCompleted); assertTrue(second.retainedPartials.contains(partial.path))
        assertSnapshot(original)
    }
    @Test fun completed_or_failed_recordings_are_never_rewritten_even_with_partial_collisions() = fixture {
        for (status in listOf("ready", "failed")) {
            container.repository.updateStatus(id, status)
            wave("$id.wav", 1); wave("$id.wav.partial", 2)
            val original = snapshot(); var queued = 0
            val report = recoverPartialMicRecordings(root.path, container.repository, onRecovered = { queued++ })
            assertEquals(1, report.skippedCompleted); assertEquals(0, queued)
            assertEquals(status, row().status); assertSnapshot(original)
        }
    }
    @Test fun active_capture_is_skipped_without_touching_its_header_or_queueing() = fixture {
        val target = wave("$id.wav", 1); val original = snapshot(); var queued = 0
        val report = recoverPartialMicRecordings(root.path, container.repository,
            onRecovered = { queued++ }, activePath = { "/synthetic/alternate-path/$id.wav" })
        assertEquals(1, report.skippedActive); assertEquals(0, queued)
        assertEquals("recording", row().status); assertSnapshot(original); assertTrue(target.exists())
    }
    @Test fun mixed_case_partial_is_mapped_to_the_correct_row_without_losing_original_audio() = fixture {
        val partial = wave("$id.WaV.PaRtIaL", 2); val original = partial.readBytes(); var queued = 0
        val report = recoverPartialMicRecordings(root.path, container.repository, onRecovered = { queued++ })
        assertEquals(listOf(id), report.recoveredIds); assertEquals(1, queued)
        assertEquals(File(root, "$id.WaV").path, row().local_path); assertEquals("queued", row().status)
        assertContentEquals(original, partial.readBytes())
    }
    @Test fun unrelated_recordings_are_not_modified_even_if_their_status_is_recording() = fixture {
        val other = container.createLocalRecording(source = "import", status = "recording")
        wave("$other.wav", 3); val original = snapshot(); var queued = 0
        val report = recoverPartialMicRecordings(root.path, container.repository, onRecovered = { queued++ })
        assertEquals(1, report.skippedUnrelated); assertEquals(0, queued); assertSnapshot(original)
        assertEquals("recording", container.repository.observeRecording(other).first()!!.status)
    }
    @Test fun mixed_case_names_parse_the_same_recording_id_and_missing_directory_is_noop() = fixture {
        assertEquals(id, recordingIdFromWavName("$id.WaV.PaRtIaL"))
        var queued = 0
        assertEquals(PartialMicRecoveryReport(), recoverPartialMicRecordings(File(root, "absent").path, container.repository, { queued++ }))
        assertEquals(0, queued)
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>(); context.deleteDatabase("velum.db")
        val f = Fixture(context)
        try { f.id = f.container.createLocalRecording(status = "recording"); f.block() }
        finally { f.container.databaseHandle.driver.close(); f.root.deleteRecursively() }
    }
    private class Fixture(context: Application) {
        val root = Files.createTempDirectory("velum-startup-wav-test").toFile()
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context),
            modelStoreRoot = ModelStoreDir(File(root, "models").path),
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> error("Recovery must use explicit test callback") } })
        lateinit var id: String
        suspend fun row() = container.repository.observeRecording(id).first()!!
        fun wave(name: String, sample: Byte): File = File(root, name).also { f -> WavWriter(f.path).apply { writePcm(ByteArray(3200) { sample }) }.finish() }
        fun snapshot() = root.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }
        fun assertSnapshot(expected: Map<String, ByteArray>) { val actual = snapshot(); assertEquals(expected.keys, actual.keys); expected.forEach { (p, bytes) -> assertContentEquals(bytes, actual.getValue(p)) } }
    }
}
