package dev.velum.app.importing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.AudioNormalizer
import dev.velum.app.platform.NormalizedAudio
import dev.velum.app.platform.sha256File
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #327: a file whose recording is gone can be imported again, instead of pointing at a missing recording. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class ReimportAfterDeleteTest {
    private fun withPipeline(block: suspend (AppContainer, AudioImportPipeline, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-reimport").toString())
        // No processing: it is not under test and would outlive the database.
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root,
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> } },
        )
        val dir = File(context.cacheDir, "reimport-fixtures").apply { deleteRecursively(); mkdirs() }
        try {
            runBlocking { block(container, AudioImportPipeline(container), silentWav(File(dir, "synthetic.wav"))) }
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    @Test fun a_file_can_be_imported_again_after_its_recording_was_deleted() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        container.repository.deleteRecordingHard(first.recordingId)
        val again = assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        assertNotEquals(first.recordingId, again.recordingId)
    }

    @Test fun a_file_can_be_imported_again_after_a_wipe() = withPipeline { container, pipeline, wav ->
        assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        container.repository.wipeAllUserData()
        assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
    }

    @Test fun a_watched_folder_does_not_bring_back_a_deleted_recording() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        assertIs<ImportResult.Deleted>(pipeline.importFile(wav.path, source = "watched"))
        assertEquals(0, container.repository.storageUsage().recordingCount, "the deleted recording came back")
    }

    @Test fun a_manual_import_of_a_deleted_file_works_and_clears_its_tombstone() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        val again = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "import"))
        // The file is live again, so the watched folder sees it as already imported.
        assertEquals(again.recordingId, assertIs<ImportResult.Duplicate>(pipeline.importFile(wav.path, source = "watched")).existingRecordingId)
    }

    @Test fun an_edited_watched_file_is_a_new_recording() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        silentWav(wav, sample = 7)
        assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
    }

    @Test fun the_same_audio_in_another_container_stays_deleted() = withPipeline { container, pipeline, wav ->
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        // Same samples, different file bytes: an extra chunk before the audio.
        val retagged = silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true)
        assertNotEquals(sha256File(wav.path), sha256File(retagged.path))
        assertIs<ImportResult.Deleted>(pipeline.importFile(retagged.path, source = "watched"))
    }

    /** Real normalizing, counted, keeping the outputs. */
    private class CountingNormalize {
        val outputs = mutableListOf<String>()
        val call: suspend (String) -> NormalizedAudio = { path ->
            AudioNormalizer().normalizeToWav(path).also { outputs += it.wavPath }
        }
    }

    @Test fun a_retagged_copy_of_a_deleted_recording_is_only_decoded_once() = withPipeline { container, _, wav ->
        val normalize = CountingNormalize()
        val pipeline = AudioImportPipeline(container, normalize = normalize.call)
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path, source = "watched"))
        container.repository.deleteRecordingHard(first.recordingId)
        val retagged = silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true)
        assertIs<ImportResult.Deleted>(pipeline.importFile(retagged.path, source = "watched"))
        assertEquals(2, normalize.outputs.size)
        assertFalse(File(normalize.outputs[1]).exists(), "decoding the copy recreated audio the user deleted")

        assertIs<ImportResult.Deleted>(pipeline.importFile(retagged.path, source = "watched"))
        assertEquals(2, normalize.outputs.size, "every sync decodes the deleted copy again")
    }

    @Test fun a_retagged_copy_of_a_live_recording_is_a_duplicate_and_leaves_no_second_file() = withPipeline { container, _, wav ->
        val normalize = CountingNormalize()
        val pipeline = AudioImportPipeline(container, normalize = normalize.call)
        val first = assertIs<ImportResult.Created>(pipeline.importFile(wav.path))
        val retagged = silentWav(File(wav.parentFile, "retagged.wav"), extraChunk = true)

        val duplicate = assertIs<ImportResult.Duplicate>(pipeline.importFile(retagged.path))
        assertEquals(first.recordingId, duplicate.existingRecordingId)
        assertEquals(first.recordingId, container.repository.getMeta("import_sha256_${sha256File(retagged.path)}"))
        assertTrue(File(first.wavPath).exists(), "the original recording lost its audio")
        assertFalse(File(normalize.outputs[1]).exists(), "the duplicate's normalized copy was left behind")

        assertIs<ImportResult.Duplicate>(pipeline.importFile(retagged.path))
        assertEquals(2, normalize.outputs.size, "a known copy was decoded again")
    }

    private fun silentWav(file: File, sampleRate: Int = 16_000, durationMs: Int = 200, sample: Short = 0, extraChunk: Boolean = false): File {
        val samples = sampleRate * durationMs / 1000
        val dataSize = samples * 2
        val extra = if (extraChunk) 8 + 4 else 0
        val buffer = ByteBuffer.allocate(44 + extra + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + extra + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16)
        if (extraChunk) buffer.put("LIST".toByteArray()).putInt(4).put("INFO".toByteArray())
        buffer.put("data".toByteArray()).putInt(dataSize)
        repeat(samples) { buffer.putShort(sample) }
        file.writeBytes(buffer.array())
        return file
    }
}
