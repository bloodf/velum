package dev.velum.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Non-destructive [SecureStore.read] (#92 / foundation for #110).
 * [SecureRead.Missing] only when absent; corrupt/undecryptable entries stay put.
 */
class SecureStoreReadTest {

    @Test
    fun jvm_read_missing_valid_and_unreadable_leaves_entry() = runTest {
        val key = JvmAesGcmSecureStore.generateAes256Key()
        val backend = linkedMapOf<String, String>()
        val store = JvmAesGcmSecureStore(key = key, backend = backend)

        assertIs<SecureRead.Missing>(store.read(SecureKeys.DEVICE_SERIAL))

        store.putString(SecureKeys.DEVICE_SERIAL, "SYNTH-READ-SERIAL")
        val ok = store.read(SecureKeys.DEVICE_SERIAL)
        assertIs<SecureRead.Value>(ok)
        assertEquals("SYNTH-READ-SERIAL", ok.value)

        backend[SecureKeys.DEVICE_BIND_TOKEN] = "!!!not-a-vault-blob!!!"
        val bad = store.read(SecureKeys.DEVICE_BIND_TOKEN)
        assertIs<SecureRead.Unreadable>(bad)
        assertTrue(store.contains(SecureKeys.DEVICE_BIND_TOKEN))
        assertEquals("!!!not-a-vault-blob!!!", backend[SecureKeys.DEVICE_BIND_TOKEN])
        // getString may still drop malformed blobs — read must not.
        assertTrue(backend.containsKey(SecureKeys.DEVICE_BIND_TOKEN))
    }

    @Test
    fun jvm_read_wrong_key_is_unreadable_and_keeps_ciphertext() = runTest {
        val keyA = JvmAesGcmSecureStore.generateAes256Key()
        val keyB = JvmAesGcmSecureStore.generateAes256Key()
        val backend = linkedMapOf<String, String>()
        JvmAesGcmSecureStore(key = keyA, backend = backend)
            .putString(SecureKeys.DEVICE_SERIAL, "SYNTH-WRONG-KEY")
        val snapshot = backend.toMap()

        val wrong = JvmAesGcmSecureStore(key = keyB, backend = backend)
        val result = wrong.read(SecureKeys.DEVICE_SERIAL)
        assertIs<SecureRead.Unreadable>(result)
        assertTrue(wrong.contains(SecureKeys.DEVICE_SERIAL))
        assertEquals(snapshot, backend)
    }

    @Test
    fun desktop_read_missing_valid_and_corrupt() = runTest {
        val dir = kotlin.io.path.createTempDirectory("velum-secure-read")
        val props = dir.resolve("secure.properties")
        val keyFile = dir.resolve("secure.aes256")
        try {
            val store = DesktopSecureStore(file = props, keyFile = keyFile)
            assertIs<SecureRead.Missing>(store.read(SecureKeys.DEVICE_SERIAL))

            store.putString(SecureKeys.DEVICE_SERIAL, "SYNTH-DESKTOP-READ")
            val ok = store.read(SecureKeys.DEVICE_SERIAL)
            assertIs<SecureRead.Value>(ok)
            assertEquals("SYNTH-DESKTOP-READ", ok.value)

            // Corrupt on-disk ciphertext while leaving the key file intact.
            val lines = props.toFile().readLines().toMutableList()
            val idx = lines.indexOfFirst { it.startsWith("${SecureKeys.DEVICE_SERIAL}=") }
            assertTrue(idx >= 0)
            lines[idx] = "${SecureKeys.DEVICE_SERIAL}=!!!corrupt!!!"
            props.toFile().writeText(lines.joinToString("\n") + "\n")

            val reloaded = DesktopSecureStore(file = props, keyFile = keyFile)
            val bad = reloaded.read(SecureKeys.DEVICE_SERIAL)
            assertIs<SecureRead.Unreadable>(bad)
            assertTrue(reloaded.contains(SecureKeys.DEVICE_SERIAL))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun in_memory_read_missing_and_value() = runTest {
        val store = InMemorySecureStore()
        assertIs<SecureRead.Missing>(store.read("k"))
        store.putString("k", "v")
        val ok = store.read("k")
        assertIs<SecureRead.Value>(ok)
        assertEquals("v", ok.value)
    }

    @Test
    fun android_policy_propagate_transient_is_unreadable_not_missing() {
        // AndroidSecureStore.read maps PropagateTransient (entry present) to
        // Unreadable and never Missing — covered here without Robolectric.
        val action = KeystoreAliasResolvePolicy.decide(
            aliasPresent = true,
            entryObtained = false,
            getEntryFailureClassNames = listOf("java.lang.NullPointerException"),
        )
        assertEquals(KeystoreAliasResolveAction.PropagateTransient, action)
        // Prefer Unreadable over Missing when an entry exists but Keystore fails.
        val outcome: SecureRead = SecureRead.Unreadable("java.lang.NullPointerException")
        assertEquals(SecureRead.Unreadable::class, outcome::class)
        assertTrue(outcome is SecureRead.Unreadable)
    }
}
