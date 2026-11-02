package dev.velum.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.RecordingJobScheduler
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #86: downloaded audio streams to `<id>.part` and is renamed only when complete. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class PersistDownloadedSessionTest {
    private lateinit var base: File
    private lateinit var container: AppContainer
    private val enqueued = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        base = Files.createTempDirectory("velum-86-persist").toFile()
        installRecordingFilesDir(base.path)
        container = AppContainer(
            InMemorySecureStore(),
            DriverFactory(context),
            modelStoreRoot = ModelStoreDir(File(base, "models").path),
            recordingJobSchedulerFactory = { _, _ ->
                RecordingJobScheduler { id, _, _, _ -> enqueued += id }
            },
        )
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun recordingsDir() = File(base, "recordings")

    private fun session(writer: ((ByteArray) -> Unit) -> Long) = DownloadedSessionAudio(
        sessionId = 1_700_000_000L,
        deviceSerial = "SYNTH-86",
        startedAtEpochSeconds = 1_700_000_000L,
        writeDecryptedAudio = writer,
    )

    @Test
    fun chunked_ogg_is_finalized_by_rename_with_sniffed_extension_and_size() = runBlocking {
        val chunks = listOf("Og".encodeToByteArray(), "gS".encodeToByteArray() + ByteArray(30), ByteArray(4096) { 7 })
        val id = container.persistDownloadedSession(
            session { sink -> chunks.forEach(sink); chunks.sumOf { it.size }.toLong() },
        )
        val files = recordingsDir().listFiles()!!.map { it.name }
        assertEquals(listOf("$id.ogg"), files)
        val rec = container.repository.getRecording(id)!!
        assertEquals(File(recordingsDir(), "$id.ogg").path, rec.local_path)
        assertEquals(chunks.sumOf { it.size }.toLong(), rec.size_bytes)
        assertContentEquals(chunks.reduce { a, b -> a + b }, File(rec.local_path!!).readBytes())
        assertEquals(listOf(id), enqueued)
    }

    @Test
    fun decrypt_failure_storage_error_and_cancellation_leave_no_partial_file_or_row() = runBlocking {
        val failures = listOf<Throwable>(
            IllegalArgumentException("Truncated PLAUD.AI body"),
            IOException("No space left on device"),
            CancellationException("cancelled"),
        )
        for (failure in failures) {
            val thrown = assertFailsWith<Throwable> {
                container.persistDownloadedSession(
                    session { sink ->
                        sink("OggS".encodeToByteArray() + ByteArray(1024))
                        throw failure
                    },
                )
            }
            assertTrue(thrown::class == failure::class, "propagates ${failure::class.simpleName}")
            assertTrue(recordingsDir().listFiles().orEmpty().isEmpty(), "no leftover after ${failure.message}")
        }
        assertTrue(enqueued.isEmpty())
        assertTrue(container.repository.observeRecordings().first().isEmpty())
    }
}
