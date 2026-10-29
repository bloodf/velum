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
 * [AndroidSecureStore] encrypt must not supply a caller IV; the fake backend
 * rejects that on [AndroidSecureKeyBackend.initEncrypt] (the path the store
 * calls), so a regression is caught on JVM without a standalone probe.
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
    fun store_encrypt_path_rejects_caller_provided_iv() = runTest {
        val ex = assertFailsWith<InvalidAlgorithmParameterException> {
            store.encryptWithCallerIvForTest(
                plaintext = "SYNTH-CALLER-IV".toByteArray(Charsets.UTF_8),
                callerIv = ByteArray(12),
            )
        }
        assertTrue(ex.message!!.contains("Caller-provided IV not permitted"))
        assertEquals(0, backend.encryptInitCount, "rejected caller IV must not count as encrypt")
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
