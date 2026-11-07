package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class VoiceProfileWipeTest {
    @Test fun wipe_deletes_the_cluster_cache_too() = runTest {
        val root = ModelStoreDir(Files.createTempDirectory("velum-voice-wipe-test").toString())
        val store = VoiceProfileStore(root, InMemorySecureStore(), ioDispatcher = StandardTestDispatcher(testScheduler))
        store.withReady { cacheClusterEmbedding("synthetic-recording", "speaker", floatArrayOf(1f, 0f, 0f)) }
        val cache = root.resolve("voices/cluster-cache")
        assertTrue(cache.exists())
        store.wipeAll()
        assertFalse(cache.exists(), "voice embeddings from processed recordings survived the wipe")
        root.deleteRecursively()
    }
}
