package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** #320: speaker embeddings of processed recordings are stored encrypted only, and go with their recording. */
class VoiceClusterCacheTest {
    private val root = ModelStoreDir(Files.createTempDirectory("velum-voice-cluster-test").toString())
    private val embedding = floatArrayOf(0.25f, -1f, 3f)
    private val cache = root.resolve("voices/cluster-cache")

    @AfterTest fun cleanup() = root.deleteRecursively()

    private fun store(scope: kotlinx.coroutines.test.TestScope) =
        VoiceProfileStore(root, InMemorySecureStore(), ioDispatcher = StandardTestDispatcher(scope.testScheduler))

    @Test fun a_cached_embedding_is_written_encrypted_only() = runTest {
        val store = store(this)
        store.withReady { cacheClusterEmbedding("rec-a", "speaker", embedding) }
        val files = cache.resolve("rec-a").listChildren().map { it.name }
        assertTrue(files.any { it.endsWith(".enc") }, "no encrypted copy: $files")
        assertFalse(files.any { it.endsWith(".f32") }, "a plaintext copy was written: $files")
        assertContentEquals(embedding, store.withReady { cachedClusterEmbedding("rec-a", "speaker") })
    }

    @Test fun plaintext_copies_from_earlier_versions_are_removed() = runTest {
        val store = store(this)
        store.withReady { cacheClusterEmbedding("rec-b", "speaker", embedding) }
        val legacy = cache.resolve("rec-b").resolve("speaker.f32").apply { writeBytes(ByteArray(12) { 1 }) }
        store.purgePlaintextClusterCache()
        assertFalse(legacy.exists(), "a plaintext embedding survived")
        assertContentEquals(embedding, store.withReady { cachedClusterEmbedding("rec-b", "speaker") })
    }

    @Test fun a_recordings_cache_is_deleted_with_it() = runTest {
        val store = store(this)
        store.withReady {
            cacheClusterEmbedding("rec-gone", "speaker", embedding)
            cacheClusterEmbedding("rec-kept", "speaker", embedding)
        }
        store.deleteClusterCache("rec-gone")
        assertFalse(cache.resolve("rec-gone").exists(), "the deleted recording's embeddings survived")
        assertTrue(cache.resolve("rec-kept").exists())
    }

    @Test fun a_cache_delete_cannot_leave_the_cache_directory() = runTest {
        val store = store(this)
        store.withReady { cacheClusterEmbedding("rec-kept", "speaker", embedding) }
        for (bad in listOf("", " ", ".", "..", "../voices", "a/b", "a\\b")) {
            assertFailsWith<IllegalArgumentException>("accepted '$bad'") { store.deleteClusterCache(bad) }
        }
        assertTrue(cache.resolve("rec-kept").exists())
    }

    @Test fun a_plaintext_copy_is_never_read() = runTest {
        val store = store(this)
        store.withReady { cacheClusterEmbedding("rec-c", "speaker", embedding) }
        val dir = cache.resolve("rec-c")
        dir.resolve("speaker.enc").deleteFile()
        dir.resolve("speaker.f32").writeBytes(ByteArray(12))
        assertNull(store.withReady { cachedClusterEmbedding("rec-c", "speaker") })
    }

    @Test fun cached_recording_ids_list_the_cache_directories() = runTest {
        val store = store(this)
        store.withReady {
            cacheClusterEmbedding("rec-1", "speaker", embedding)
            cacheClusterEmbedding("rec-2", "speaker", embedding)
        }
        assertEquals(setOf("rec-1", "rec-2"), store.cachedRecordingIds())
    }
}
