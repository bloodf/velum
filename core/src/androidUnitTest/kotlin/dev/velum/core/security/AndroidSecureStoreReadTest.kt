package dev.velum.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [AndroidSecureStore.read] with an injected fake
 * [AndroidSecureKeyBackend] (androidUnitTest only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidSecureStoreReadTest {
    private lateinit var context: Context
    private lateinit var backend: FakeAndroidSecureKeyBackend
    private lateinit var store: AndroidSecureStore
    private val prefsName = "velum_secure_read_test"
    private val entryKey = SecureKeys.DEVICE_SERIAL

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        backend = FakeAndroidSecureKeyBackend()
        store = AndroidSecureStore(
            context = context,
            keyBackend = backend,
            prefsName = prefsName,
            keyAlias = "dev.velum.test_secure_store_aes",
        )
    }

    @Test
    fun read_absent_entry_is_missing() = runTest {
        assertIs<SecureRead.Missing>(store.read(entryKey))
        assertFalse(store.contains(entryKey))
    }

    @Test
    fun read_valid_entry_is_value() = runTest {
        store.putString(entryKey, "SYNTH-ANDROID-READ-OK")
        val result = store.read(entryKey)
        assertIs<SecureRead.Value>(result)
        assertEquals("SYNTH-ANDROID-READ-OK", result.value)
        assertTrue(backend.containsAlias())
    }

    @Test
    fun transient_peek_failure_is_unreadable_leaves_prefs_and_alias() = runTest {
        store.putString(entryKey, "SYNTH-ANDROID-TRANSIENT")
        val mintsBefore = backend.mintCount
        val prefsSnapshot = prefsCopy()

        backend.peekFailure = NullPointerException("invalid null input")

        val result = store.read(entryKey)
        assertIs<SecureRead.Unreadable>(result)
        assertEquals(NullPointerException::class.java.name, result.errorClassName)
        assertTrue(store.contains(entryKey))
        assertTrue(backend.containsAlias())
        assertEquals(prefsSnapshot, prefsCopy())
        assertEquals(mintsBefore, backend.mintCount, "transient failure must not mint a key")
    }

    @Test
    fun permanent_peek_failure_is_unreadable_leaves_prefs_and_alias() = runTest {
        store.putString(entryKey, "SYNTH-ANDROID-PERMANENT")
        val mintsBefore = backend.mintCount
        val prefsSnapshot = prefsCopy()

        backend.peekFailure = KeyPermanentlyInvalidatedException()

        val result = store.read(entryKey)
        assertIs<SecureRead.Unreadable>(result)
        assertEquals(KeyPermanentlyInvalidatedException::class.java.name, result.errorClassName)
        // read() must never clear prefs or the alias, even on permanent invalidation.
        assertTrue(store.contains(entryKey))
        assertTrue(backend.containsAlias())
        assertEquals(prefsSnapshot, prefsCopy())
        assertEquals(mintsBefore, backend.mintCount)
    }

    @Test
    fun crypto_decrypt_failure_is_unreadable_leaves_prefs_and_alias() = runTest {
        store.putString(entryKey, "SYNTH-ANDROID-CRYPTO")
        val mintsBefore = backend.mintCount
        // Corrupt ciphertext in place — structurally valid Base64 but wrong payload.
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(entryKey, "not-a-valid-vault-blob!!!").commit()
        assertTrue(store.contains(entryKey))

        val result = store.read(entryKey)
        assertIs<SecureRead.Unreadable>(result)
        assertTrue(store.contains(entryKey))
        assertTrue(backend.containsAlias())
        assertEquals("not-a-valid-vault-blob!!!", prefs.getString(entryKey, null))
        assertEquals(mintsBefore, backend.mintCount)
    }

    @Test
    fun after_transient_failure_successful_read_returns_original_without_remint() = runTest {
        store.putString(entryKey, "SYNTH-ANDROID-RECOVER")
        val mintsAfterPut = backend.mintCount

        backend.peekFailure = NullPointerException("invalid null input")
        assertIs<SecureRead.Unreadable>(store.read(entryKey))
        assertEquals(mintsAfterPut, backend.mintCount)

        backend.peekFailure = null
        val recovered = store.read(entryKey)
        assertIs<SecureRead.Value>(recovered)
        assertEquals("SYNTH-ANDROID-RECOVER", recovered.value)
        assertEquals(mintsAfterPut, backend.mintCount, "no new key after transient failure")
        assertTrue(backend.containsAlias())
    }

    private fun prefsCopy(): Map<String, *> =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.toMap()
}
