package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
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

    @Test fun with_the_models_ready_a_lost_key_is_refused_and_a_good_key_is_not() = runBlocking {
        val root = ModelStoreDir(Files.createTempDirectory("velum-naming-ready-models").toString())
        try {
            // Mark the segmentation and embedding models Ready the way the readiness check wants them.
            root.resolve("pyannote-segmentation-3.0").resolve("segmentation.onnx").writeBytes(ByteArray(8) { 1 })
            val embedding = DefaultLocalCatalog.first { it.id == "3dspeaker-eres2net-base" }
            root.resolve(embedding.id).resolve(embedding.archiveFileName).writeBytes(ByteArray(8) { 1 })
            val manager = createLocalModelManager(storeRoot = root)
            assertTrue(manager.isReady("pyannote-segmentation-3.0") && manager.isReady(embedding.id), "fixture models are not Ready")

            val secure = InMemorySecureStore()
            VoiceProfileStore(root, secure).withReady { upsert("Synthetic Old", floatArrayOf(1f, 0f), id = "old") }
            val withKey = LocalDiarizer(manager, VoiceProfileStore(root, secure))
            val lostKey = LocalDiarizer(manager, VoiceProfileStore(root, InMemorySecureStore()))
            assertTrue(withKey.speakerNamingReady("pyannote-segmentation-3.0"))
            assertFalse(lostKey.speakerNamingReady("pyannote-segmentation-3.0"), "a rerun that would overwrite names must be refused")
        } finally {
            root.deleteRecursively()
        }
    }
}
