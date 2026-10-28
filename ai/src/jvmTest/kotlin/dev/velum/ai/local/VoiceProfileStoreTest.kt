package dev.velum.ai.local

import dev.velum.core.security.InMemorySecureStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class VoiceProfileStoreTest {

    private fun tempStore(): VoiceProfileStore {
        val dir = File.createTempFile("velum-vp", "").also {
            it.delete()
            it.mkdirs()
        }
        return VoiceProfileStore(
            root = ModelStoreDir(dir.absolutePath),
            secureStore = InMemorySecureStore(),
            cipherOverride = createProfileCipher(ByteArray(32) { 9 }),
        )
    }

    @Test
    fun match_respectsThreshold() = runBlocking {
        val store = tempStore()
        store.upsert("Alice", floatArrayOf(1f, 0f, 0f))
        assertNull(store.match(floatArrayOf(0f, 1f, 0f), threshold = 0.9f))
        val hit = store.match(floatArrayOf(0.99f, 0.01f, 0f), threshold = 0.5f)
        assertNotNull(hit)
        assertEquals("Alice", hit.profile.displayName)
        assertTrue(hit.confidence >= 0.5f)
    }

    @Test
    fun refine_averagesSamples() = runBlocking {
        val store = tempStore()
        store.upsert("Bob", floatArrayOf(1f, 0f, 0f))
        store.refine("bob", floatArrayOf(0f, 1f, 0f))
        val bob = store.get("bob")
        assertNotNull(bob)
        assertEquals(2, bob.sampleCount)
        assertEquals(0.5f, bob.embedding[0], 0.001f)
        assertEquals(0.5f, bob.embedding[1], 0.001f)
    }

    @Test
    fun delete_and_wipe() = runBlocking {
        val store = tempStore()
        store.upsert("A", floatArrayOf(1f, 0f))
        store.upsert("B", floatArrayOf(0f, 1f))
        store.delete("a")
        assertEquals(1, store.listProfiles().size)
        store.wipeAll()
        assertTrue(store.listProfiles().isEmpty())
    }

    @Test
    fun exportImport_roundTrip() = runBlocking {
        val store = tempStore()
        store.upsert(
            displayName = "Cara",
            embedding = floatArrayOf(0.2f, 0.4f, 0.6f),
            role = "PM",
            company = "Velum",
            email = "c@example.com",
            notes = "lead",
        )
        val json = store.exportEncrypted()
        store.wipeAll()
        assertTrue(store.listProfiles().isEmpty())
        assertEquals(1, store.importEncrypted(json))
        val p = store.listProfiles().single()
        assertEquals("Cara", p.displayName)
        assertEquals("PM", p.role)
        assertEquals("Velum", p.company)
        assertEquals("c@example.com", p.email)
    }

    @Test
    fun merge_combinesSamples() = runBlocking {
        val store = tempStore()
        store.upsert("Left", floatArrayOf(1f, 0f, 0f), id = "left")
        store.upsert("Right", floatArrayOf(0f, 1f, 0f), id = "right")
        val merged = store.merge("left", "right")
        assertNotNull(merged)
        assertNull(store.get("right"))
        assertEquals(2, merged.sampleCount)
    }

    @Test
    fun encryption_notPlaintextOnDisk() = runBlocking {
        val dir = File.createTempFile("velum-vp-enc", "").also { it.delete(); it.mkdirs() }
        val store = VoiceProfileStore(
            root = ModelStoreDir(dir.absolutePath),
            secureStore = InMemorySecureStore(),
            cipherOverride = createProfileCipher(ByteArray(32) { 3 }),
        )
        store.upsert("Secret", floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        val enc = File(dir, "voices/profiles/secret.enc")
        assertTrue(enc.exists())
        val bytes = enc.readBytes()
        // Plain f32 for 0.1f starts with distinctive little-endian pattern; ciphertext must differ.
        val plain = VoiceProfileStore.floatsToBytes(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        assertTrue(!bytes.contentEquals(plain))
        assertNull(File(dir, "voices/profiles/secret.f32").takeIf { it.exists() })
    }
}
