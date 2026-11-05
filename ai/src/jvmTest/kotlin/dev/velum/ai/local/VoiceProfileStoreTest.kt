package dev.velum.ai.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceProfileStoreTest {

    private fun tempStore(): VoiceProfileStore {
        val dir = File.createTempFile("velum-vp", "").also {
            it.delete()
            it.mkdirs()
        }
        return VoiceProfileStore(
            root = ModelStoreDir(dir.absolutePath),
            cipherOverride = createProfileCipher(ByteArray(32) { 9 }),
        )
    }

    @Test
    fun match_respectsThreshold() {
        val store = tempStore()
        store.upsert("Alice", floatArrayOf(1f, 0f, 0f))
        assertNull(store.match(floatArrayOf(0f, 1f, 0f), threshold = 0.9f))
        val hit = store.match(floatArrayOf(0.99f, 0.01f, 0f), threshold = 0.5f)
        assertNotNull(hit)
        assertEquals("Alice", hit.profile.displayName)
        assertTrue(hit.confidence >= 0.5f)
    }

    @Test
    fun refine_averagesSamples() {
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
    fun delete_and_wipe() {
        val store = tempStore()
        store.upsert("A", floatArrayOf(1f, 0f))
        store.upsert("B", floatArrayOf(0f, 1f))
        store.delete("a")
        assertEquals(1, store.listProfiles().size)
        store.wipeAll()
        assertTrue(store.listProfiles().isEmpty())
    }

    @Test
    fun importEncrypted_rejectsNonBackupTextAsInvalid() {
        val store = tempStore()
        for (text in listOf("", "not json", "[]", """{"version":1,"profiles":[{"id":"a"}]}""")) {
            assertFailsWith<InvalidVoiceProfileBackupException>(text) { store.importEncrypted(text) }
        }
        assertTrue(store.listProfiles().isEmpty())
    }

    @Test
    fun importEncrypted_badBase64IsInvalid_notAnUnhandledError() {
        val store = tempStore()
        store.upsert("A", floatArrayOf(1f, 0f))
        val broken = store.exportEncrypted().replace(Regex("\"embeddingB64\": \"[^\"]*\""), "\"embeddingB64\": \"***\"")
        assertFailsWith<InvalidVoiceProfileBackupException> { store.importEncrypted(broken) }
    }

    @Test
    fun importEncrypted_badSecondEntry_writesNothing() {
        val store = tempStore()
        store.upsert("A", floatArrayOf(1f, 0f))
        store.upsert("B", floatArrayOf(0f, 1f))
        val export = store.exportEncrypted()
        store.wipeAll()
        // Corrupt only the second entry's embedding.
        val marker = "\"embeddingB64\": \""
        val second = export.indexOf(marker, export.indexOf(marker) + 1) + marker.length
        val broken = export.substring(0, second) + "***" + export.substring(second)
        assertFailsWith<InvalidVoiceProfileBackupException> { store.importEncrypted(broken) }
        assertTrue(store.listProfiles().isEmpty(), "entry 1 must not be saved when entry 2 is bad")
    }

    @Test
    fun importEncrypted_invalidSecondId_isMalformedAndWritesNothing() {
        val dir = File.createTempFile("velum-vp-invalid-id", "").also { it.delete(); it.mkdirs() }
        try {
            val store = VoiceProfileStore(
                root = ModelStoreDir(dir.absolutePath),
                cipherOverride = createProfileCipher(ByteArray(32) { 9 }),
            )
            store.upsert("A", floatArrayOf(1f, 0f))
            store.upsert("B", floatArrayOf(0f, 1f))
            val export = store.exportEncrypted()
            val broken = export.replace("\"id\": \"b\"", "\"id\": \"../bad\"")
            assertTrue(broken != export)
            store.wipeAll()
            val before = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path to it.readBytes().toList() }.toList()

            assertFailsWith<InvalidVoiceProfileBackupException> { store.importEncrypted(broken) }
            assertEquals(before, dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path to it.readBytes().toList() }.toList())
            assertTrue(store.listProfiles().isEmpty())
            assertEquals(2, store.importEncrypted(export))
            assertEquals(listOf("a", "b"), store.listProfiles().map { it.id })
        } finally {
            assertTrue(dir.deleteRecursively(), "owned voice-profile fixture must be removed")
        }
    }

    @Test
    fun exportImport_roundTrip() {
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
    fun merge_combinesSamples() {
        val store = tempStore()
        store.upsert("Left", floatArrayOf(1f, 0f, 0f), id = "left")
        store.upsert("Right", floatArrayOf(0f, 1f, 0f), id = "right")
        val merged = store.merge("left", "right")
        assertNotNull(merged)
        assertNull(store.get("right"))
        assertEquals(2, merged.sampleCount)
    }

    @Test
    fun encryption_notPlaintextOnDisk() {
        val dir = File.createTempFile("velum-vp-enc", "").also { it.delete(); it.mkdirs() }
        val store = VoiceProfileStore(
            root = ModelStoreDir(dir.absolutePath),
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
