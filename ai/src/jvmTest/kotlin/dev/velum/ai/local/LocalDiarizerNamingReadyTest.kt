package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The preflight must not refuse a rerun that would not name anyone anyway (models not downloaded). */
class LocalDiarizerNamingReadyTest {
    @Test fun without_the_diarization_models_a_lost_key_is_not_a_reason_to_refuse() = runBlocking {
        val root = ModelStoreDir(Files.createTempDirectory("velum-naming-ready").toString())
        try {
            // Encrypted data whose key is gone: the store itself reports naming unavailable.
            VoiceProfileStore(root, InMemorySecureStore()).withReady { upsert("Synthetic Old", floatArrayOf(1f, 0f), id = "old") }
            val lostKey = VoiceProfileStore(root, InMemorySecureStore())
            assertTrue(!lostKey.speakerNamingReady())
            val diarizer = LocalDiarizer(createLocalModelManager(storeRoot = root), lostKey)
            assertTrue(diarizer.speakerNamingReady("pyannote-segmentation-3.0"), "refused a rerun that would not have run the diarizer")
        } finally {
            root.deleteRecursively()
        }
    }
}
