package dev.velum.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.core.security.AndroidSecureStore
import dev.velum.core.security.SecureKeys
import dev.velum.core.security.SecureRead
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real AndroidKeyStore round-trip for [AndroidSecureStore]: putString → read Value,
 * overwrite, and delete. Requires a device/emulator (not Robolectric).
 *
 * Selected by composeApp `testInstrumentationRunnerArguments.class` alongside
 * [dev.velum.app.devicewalk.DeviceWalkUiTest], so CI `Emulator device walk` runs
 * it for both debug and minifiedTest (R8) builds.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSecureStoreKeystoreInstrumentedTest {
    private lateinit var store: AndroidSecureStore
    private val prefsName = "velum_secure_keystore_instrumented"
    private val keyAlias = "dev.velum.instrumented_secure_store_aes"
    private val entryKey = SecureKeys.DEVICE_SERIAL

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(prefsName, 0).edit().clear().commit()
        // Wipe any leftover Keystore alias from a prior failed run.
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
        }
        store = AndroidSecureStore(
            context = context,
            prefsName = prefsName,
            keyAlias = keyAlias,
        )
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { store.wipe() }
    }

    @Test
    fun putString_read_overwrite_and_delete_on_real_keystore() = runBlocking {
        store.putString(entryKey, "SYNTH-KEYSTORE-ROUNDTRIP")
        val first = store.read(entryKey)
        assertIs<SecureRead.Value>(first)
        assertEquals("SYNTH-KEYSTORE-ROUNDTRIP", first.value)
        assertTrue(store.contains(entryKey))

        store.putString(entryKey, "SYNTH-KEYSTORE-OVERWRITE")
        val overwritten = store.read(entryKey)
        assertIs<SecureRead.Value>(overwritten)
        assertEquals("SYNTH-KEYSTORE-OVERWRITE", overwritten.value)

        store.remove(entryKey)
        assertFalse(store.contains(entryKey))
        assertIs<SecureRead.Missing>(store.read(entryKey))
    }
}
