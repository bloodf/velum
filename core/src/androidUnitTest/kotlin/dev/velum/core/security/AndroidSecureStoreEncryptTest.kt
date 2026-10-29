package dev.velum.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.InvalidAlgorithmParameterException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for AndroidKeyStore randomized-encryption contract:
 * encrypt must not supply a caller IV; the fake rejects that path and counts
 * [FakeAndroidSecureKeyBackend.initEncrypt] so a regression is caught on JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidSecureStoreEncryptTest {
    private lateinit var context: Context
    private lateinit var backend: FakeAndroidSecureKeyBackend
    private lateinit var store: AndroidSecureStore
    private val prefsName = "velum_secure_encrypt_test"
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
            keyAlias = "dev.velum.test_secure_encrypt_aes",
        )
    }

    @Test
    fun putString_uses_provider_iv_and_round_trips_via_read() = runTest {
        store.putString(entryKey, "SYNTH-ANDROID-ENCRYPT-OK")
        assertEquals(1, backend.encryptInitCount, "encrypt must call backend.initEncrypt")
        val result = store.read(entryKey)
        assertIs<SecureRead.Value>(result)
        assertEquals("SYNTH-ANDROID-ENCRYPT-OK", result.value)
    }

    @Test
    fun fake_rejects_caller_provided_iv_like_keystore() = runTest {
        val key = backend.getOrCreateKey(onClearVault = {})
        val ex = assertFailsWith<InvalidAlgorithmParameterException> {
            backend.encryptWithCallerProvidedIv(key, ByteArray(12))
        }
        assertTrue(ex.message!!.contains("Caller-provided IV not permitted"))
    }

    @Test
    fun overwrite_and_remove_keep_using_provider_iv() = runTest {
        store.putString(entryKey, "SYNTH-FIRST")
        store.putString(entryKey, "SYNTH-SECOND")
        assertEquals(2, backend.encryptInitCount)
        val overwritten = store.read(entryKey)
        assertIs<SecureRead.Value>(overwritten)
        assertEquals("SYNTH-SECOND", overwritten.value)

        store.remove(entryKey)
        assertIs<SecureRead.Missing>(store.read(entryKey))
    }
}
