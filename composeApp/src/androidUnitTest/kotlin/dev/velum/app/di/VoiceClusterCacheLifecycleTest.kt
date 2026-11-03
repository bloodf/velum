package dev.velum.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #320 through the app: a permanent delete takes the recording's speaker embeddings, and startup purges plaintext ones. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class VoiceClusterCacheLifecycleTest {
    private fun withContainer(root: ModelStoreDir, block: suspend (AppContainer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        try {
            runBlocking { block(container) }
        } finally {
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test fun deleting_a_recording_forever_deletes_its_speaker_embeddings() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-cluster-delete").toString())
        withContainer(root) { container ->
            container.repository.insertRecording(
                id = "rec-forever", sessionId = null, folderId = null, title = "Synthetic", source = "phone",
                deviceSerial = null, startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
                transcript = null, summaryMarkdown = null, status = "ready",
            )
            container.voiceProfileStore.withReady { cacheClusterEmbedding("rec-forever", "speaker", floatArrayOf(1f, 0f)) }
            val cache = root.resolve("voices/cluster-cache/rec-forever")
            assertTrue(cache.exists())
            container.deleteRecordingForever("rec-forever")
            assertFalse(cache.exists(), "the deleted recording's speaker embeddings survived")
        }
    }

    /**
     * The recording exists (a first container wrote its row), so the orphan sweep must keep its directory and
     * only the purge removes the plaintext copy; the encrypted copy next to it survives.
     */
    @Test fun startup_removes_plaintext_speaker_embeddings_but_keeps_encrypted_ones_of_live_recordings() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-cluster-purge").toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val first = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            first.repository.insertRecording(
                id = "rec-old", sessionId = null, folderId = null, title = "Synthetic", source = "phone",
                deviceSerial = null, startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
                transcript = null, summaryMarkdown = null, status = "ready",
            )
        }
        first.databaseHandle.driver.close()
        val dir = root.resolve("voices/cluster-cache/rec-old")
        dir.createDirectories()
        val plaintext = dir.resolve("speaker.f32").apply { writeBytes(ByteArray(8) { 1 }) }
        val encrypted = dir.resolve("speaker.enc").apply { writeBytes(ByteArray(8) { 2 }) }
        val second = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        try {
            runBlocking { withTimeout(10_000) { while (plaintext.exists()) delay(20) } }
            // The sweep runs right after the purge; give it the time it would need to wrongly delete the directory.
            runBlocking { delay(500) }
            assertTrue(encrypted.exists(), "the live recording's encrypted embedding was deleted")
        } finally {
            second.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test fun startup_removes_speaker_embeddings_of_recordings_that_no_longer_exist() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-cluster-orphans").toString())
        val orphan = root.resolve("voices/cluster-cache/rec-deleted-during-diarization")
        orphan.createDirectories()
        orphan.resolve("speaker.enc").writeBytes(ByteArray(8) { 1 })
        withContainer(root) {
            withTimeout(10_000) { while (orphan.exists()) delay(20) }
        }
    }

    @Test fun wipe_all_data_deletes_every_recordings_speaker_embeddings() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-cluster-wipe").toString())
        withContainer(root) { container ->
            container.voiceProfileStore.withReady {
                cacheClusterEmbedding("rec-1", "speaker", floatArrayOf(1f, 0f))
                cacheClusterEmbedding("rec-2", "speaker", floatArrayOf(0f, 1f))
            }
            val cache = root.resolve("voices/cluster-cache")
            assertTrue(cache.exists())
            container.wipeAllData()
            assertFalse(cache.exists(), "speaker embeddings survived the wipe")
        }
    }
}
