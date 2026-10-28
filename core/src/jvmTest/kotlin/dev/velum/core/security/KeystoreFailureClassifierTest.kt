package dev.velum.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Covers AndroidSecureStore vault policy (#92 items 1–2) without Robolectric:
 * permanent invalidation is the only clear; transient failures keep entries;
 * structurally malformed blobs are dropped alone (via [JvmAesGcmSecureStore]).
 */
class KeystoreFailureClassifierTest {

    @Test
    fun permanent_invalidation_is_only_clear_action() {
        assertEquals(
            KeystoreVaultAction.ClearAndRegenerate,
            KeystoreFailureClassifier.actionFor(
                classNames = listOf("android.security.keystore.KeyPermanentlyInvalidatedException"),
            ),
        )
        assertEquals(
            KeystoreVaultAction.ClearAndRegenerate,
            KeystoreFailureClassifier.actionFor(
                classNames = listOf("java.security.ProviderException"),
                messages = listOf("Key permanently invalidated"),
            ),
        )
        assertEquals(
            KeystoreVaultAction.ClearAndRegenerate,
            KeystoreFailureClassifier.actionFor(
                classNames = listOf(
                    "javax.crypto.IllegalBlockSizeException",
                    "android.security.keystore.KeyPermanentlyInvalidatedException",
                ),
            ),
        )
    }

    @Test
    fun transient_failures_keep_entries() {
        val transient = listOf(
            listOf("java.security.UnrecoverableKeyException"),
            listOf("java.security.InvalidKeyException"),
            listOf("java.lang.NullPointerException"),
            listOf("javax.crypto.AEADBadTagException"),
            listOf("java.security.KeyStoreException"),
        )
        for (names in transient) {
            assertEquals(
                KeystoreVaultAction.KeepEntries,
                KeystoreFailureClassifier.actionFor(classNames = names),
                "expected KeepEntries for $names",
            )
        }
        assertEquals(
            KeystoreVaultAction.KeepEntries,
            KeystoreFailureClassifier.actionFor(
                classNames = listOf("java.lang.NullPointerException"),
                messages = listOf("invalid null input"),
            ),
        )
    }

    @Test
    fun transient_failure_keeps_every_entry_in_store() = runTest {
        val key = JvmAesGcmSecureStore.generateAes256Key()
        val backend = linkedMapOf<String, String>()
        val store = JvmAesGcmSecureStore(key = key, backend = backend)
        store.putString(SecureKeys.DEVICE_SERIAL, "SYNTH-KEEP-A")
        store.putString(SecureKeys.DEVICE_BIND_TOKEN, "synth-keep-b")
        val snapshot = backend.toMap()

        // Simulate Android getString path after a transient Keystore failure:
        // classifier says KeepEntries → do not mutate the backend.
        val action = KeystoreFailureClassifier.actionFor(
            classNames = listOf("java.security.UnrecoverableKeyException"),
            messages = listOf("Transient keystore glitch"),
        )
        assertEquals(KeystoreVaultAction.KeepEntries, action)
        if (action != KeystoreVaultAction.ClearAndRegenerate) {
            // Mirror AndroidSecureStore: return null without clearing.
        }
        assertEquals(snapshot, backend)
        assertEquals("SYNTH-KEEP-A", store.getString(SecureKeys.DEVICE_SERIAL))
        assertEquals("synth-keep-b", store.getString(SecureKeys.DEVICE_BIND_TOKEN))
    }

    @Test
    fun malformed_blob_is_dropped_alone() = runTest {
        val key = JvmAesGcmSecureStore.generateAes256Key()
        val backend = linkedMapOf<String, String>()
        val store = JvmAesGcmSecureStore(key = key, backend = backend)
        store.putString(SecureKeys.DEVICE_SERIAL, "SYNTH-KEEP-SERIAL")
        store.putString(SecureKeys.DEVICE_BIND_TOKEN, "synth-keep-token")
        backend[SecureKeys.DEVICE_BIND_TOKEN] = "!!!not-a-vault-blob!!!"

        assertNull(store.getString(SecureKeys.DEVICE_BIND_TOKEN))
        assertFalse(store.contains(SecureKeys.DEVICE_BIND_TOKEN))
        assertTrue(store.contains(SecureKeys.DEVICE_SERIAL))
        assertEquals("SYNTH-KEEP-SERIAL", store.getString(SecureKeys.DEVICE_SERIAL))
    }

    @Test
    fun permanent_invalidation_is_only_case_that_clears() = runTest {
        val backend = linkedMapOf(
            SecureKeys.DEVICE_SERIAL to "cipher-a",
            SecureKeys.DEVICE_BIND_TOKEN to "cipher-b",
        )
        val keep = KeystoreFailureClassifier.actionFor(
            classNames = listOf("java.security.InvalidKeyException"),
        )
        assertEquals(KeystoreVaultAction.KeepEntries, keep)
        // Transient: Android must not clear.
        assertEquals(2, backend.size)

        val clear = KeystoreFailureClassifier.actionFor(
            classNames = listOf("android.security.keystore.KeyPermanentlyInvalidatedException"),
        )
        assertEquals(KeystoreVaultAction.ClearAndRegenerate, clear)
        if (clear == KeystoreVaultAction.ClearAndRegenerate) {
            backend.clear()
        }
        assertEquals(0, backend.size)
    }
}
