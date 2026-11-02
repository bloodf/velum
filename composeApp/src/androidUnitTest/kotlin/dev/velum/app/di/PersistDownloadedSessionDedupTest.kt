package dev.velum.app.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.streamToFileAtomically
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #113: re-downloading a device session reuses its library row. Synthetic serial/audio only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PersistDownloadedSessionDedupTest {
    @Test fun same_session_twice_gives_one_row_and_one_file_with_same_id() = fixture {
        val first = container.persistDownloadedSession(session(ogg(1)))
        val second = container.persistDownloadedSession(session(ogg(2)))
        assertEquals(first, second)
        assertEquals(1, container.repository.getAllRecordings().size)
        assertEquals(listOf("$first.ogg"), audioFiles())
        assertContentEquals(ogg(2), File(row(first).local_path!!).readBytes())
    }

    @Test fun redownload_keeps_user_edits_and_does_not_reprocess_finished_rows() = fixture {
        val id = container.persistDownloadedSession(session(ogg(1)))
        container.repository.updateRecordingTitle(id, "Synthetic edited title")
        container.repository.updateTranscript(id, "Synthetic transcript")
        container.repository.updateSummary(id, "Synthetic notes")
        container.repository.updateStatus(id, "ready")
        val enqueuedBefore = enqueued
        container.persistDownloadedSession(session(ogg(2)))
        val r = row(id)
        assertEquals("Synthetic edited title", r.title)
        assertEquals("Synthetic transcript", r.transcript)
        assertEquals("Synthetic notes", r.summary_markdown)
        assertEquals("ready", r.status)
        assertEquals(enqueuedBefore, enqueued)
    }

    @Test fun redownload_of_unprocessed_row_requeues_it_and_restores_from_trash() = fixture {
        val id = container.persistDownloadedSession(session(ogg(1)))
        container.repository.updateStatus(id, "failed")
        container.repository.trashRecording(id)
        container.persistDownloadedSession(session(ogg(2)))
        assertEquals("queued", row(id).status)
        assertEquals(0, row(id).trashed)
        assertEquals(2, enqueued, "unprocessed row must be re-enqueued once")
    }

    @Test fun failed_redownload_leaves_existing_row_and_audio_untouched() = fixture {
        val id = container.persistDownloadedSession(session(ogg(1)))
        val before = row(id)
        assertFailsWith<IOException> {
            container.persistDownloadedSession(
                DownloadedSessionAudio(1_700_000_000, "SYNTH-SN-1", 1_700_000_000) { sink ->
                    sink(ogg(2)) // part of the new audio streams before the failure
                    throw IOException("synthetic write failure")
                },
            )
        }
        assertEquals(before, row(id))
        assertContentEquals(ogg(1), File(before.local_path!!).readBytes())
        assertEquals(listOf("$id.ogg"), audioFiles())
    }

    @Test fun concurrent_redownloads_of_same_session_never_create_a_second_row() = fixture {
        val ids = coroutineScope { (1..6).map { n -> async { container.persistDownloadedSession(session(ogg(n))) } }.awaitAll() }
        assertEquals(1, ids.toSet().size)
        assertEquals(1, container.repository.getAllRecordings().size)
        assertEquals(1, audioFiles().size)
    }

    @Test fun format_change_moves_row_to_new_file_and_removes_only_its_old_file() = fixture {
        val id = container.persistDownloadedSession(session(ogg(1)))
        val unrelated = File(dir(), "rec-unrelated.ogg").apply { writeBytes(ogg(9)) }
        container.persistDownloadedSession(session(wav()))
        assertEquals("${dir()}/$id.wav", row(id).local_path)
        assertEquals(listOf("$id.wav", "rec-unrelated.ogg"), audioFiles())
        assertTrue(unrelated.isFile)
    }

    @Test fun different_session_or_serial_is_a_different_recording() = fixture {
        val a = container.persistDownloadedSession(session(ogg(1)))
        val b = container.persistDownloadedSession(session(ogg(1), sessionId = 1_700_000_100))
        val c = container.persistDownloadedSession(session(ogg(1), serial = "SYNTH-SN-OTHER"))
        assertEquals(3, setOf(a, b, c).size)
    }

    @Test fun stream_write_failure_keeps_previous_file() = fixture {
        File(dir()).mkdirs() // The path lookup is disk-free; write sites create the directory.
        val target = File(dir(), "rec-atomic.ogg").apply { writeBytes(ogg(1)) }
        // A non-empty directory at the part path makes the streamed write fail.
        val part = File(dir(), "rec-atomic.part").apply { mkdirs(); File(this, "x").writeText("x") }
        assertFailsWith<Exception> { streamToFileAtomically(part.path, 12, { target.path }) { it(ogg(2)) } }
        assertContentEquals(ogg(1), target.readBytes())
    }

    @Test fun atomic_move_failure_keeps_destination_and_removes_part() = fixture {
        // A non-empty directory at the destination makes the atomic move fail after the part is written.
        val target = File(dir(), "rec-move.ogg").apply { mkdirs(); File(this, "keep").writeText("x") }
        val part = File(dir(), "rec-move.part")
        assertFailsWith<Exception> { streamToFileAtomically(part.path, 12, { target.path }) { it(ogg(1)) } }
        assertTrue(File(target, "keep").isFile)
        assertFalse(part.exists())
    }

    @Test fun unknown_serial_never_merges_sessions_from_different_devices() = fixture {
        val a = container.persistDownloadedSession(session(ogg(1), serial = null))
        val b = container.persistDownloadedSession(session(ogg(2), serial = " "))
        assertTrue(a != b)
        assertContentEquals(ogg(1), File(row(a).local_path!!).readBytes())
        assertEquals(null, row(b).device_serial)
    }

    @Test fun library_session_ids_mark_only_this_devices_sessions() = fixture {
        container.persistDownloadedSession(session(ogg(1)))
        assertEquals(setOf(1_700_000_000L), container.repository.librarySessionIds(listOf(1_700_000_000, 5), "SYNTH-SN-1"))
        assertEquals(emptySet(), container.repository.librarySessionIds(listOf(1_700_000_000), "SYNTH-SN-OTHER"))
    }

    @Test fun seeded_autoflows_enabled_still_give_one_row_and_file() = fixture(disableAutoflows = false) {
        val first = container.persistDownloadedSession(session(ogg(1)))
        val second = container.persistDownloadedSession(session(ogg(2)))
        assertEquals(first, second)
        assertEquals(1, container.repository.getAllRecordings().count { it.session_id == 1_700_000_000L })
        assertEquals(listOf("$first.ogg"), audioFiles())
    }

    private fun ogg(n: Int) = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(), n.toByte())
    private fun wav() = "RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray()
    private fun session(bytes: ByteArray, sessionId: Long = 1_700_000_000, serial: String? = "SYNTH-SN-1") =
        DownloadedSessionAudio(sessionId, serial, sessionId) { sink -> sink(bytes); bytes.size.toLong() }

    private fun fixture(disableAutoflows: Boolean = true, block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(20_000) {
            val context = ApplicationProvider.getApplicationContext<Application>(); context.deleteDatabase("velum.db")
            val f = Fixture(context)
            // Isolate persist from the async AutoFlow pass (seeded rules would re-queue rows on their own).
            if (disableAutoflows) f.container.repository.listAutoflows().forEach { f.container.repository.setAutoflowEnabled(it.id, false) }
            try { f.block() } finally { f.container.databaseHandle.driver.close(); f.root.deleteRecursively() }
        }
    }

    private class Fixture(context: Application) {
        val root: File = Files.createTempDirectory("velum-dedup-synthetic").toFile().also { installRecordingFilesDir(it.path) }
        var enqueued = 0
        val container = AppContainer(
            InMemorySecureStore(), DriverFactory(context), modelStoreRoot = ModelStoreDir(File(root, "models").path),
            recordingJobSchedulerFactory = { _, _ -> RecordingJobScheduler { _, _, _, _ -> enqueued++ } },
        )
        fun dir() = recordingsDirectoryPath()
        fun audioFiles() = File(dir()).listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
        suspend fun row(id: String) = container.repository.getRecording(id)!!
    }
}
