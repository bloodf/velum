package dev.velum.app.di

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.core.security.AndroidSecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import dev.velum.core.security.SecureKeys
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Malformed synthetic preferences fail before any AndroidKeyStore access is attempted. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class VoiceKeyPreservationTest {
    @Test
    fun corrupt_voice_key_ciphertext_is_reported_and_the_vault_is_preserved(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "voice-preservation-synthetic-test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val corrupt = "AA==" // Payload contains an invalid zero-length IV.
        prefs.edit().clear().putString(SecureKeys.VOICE_PROFILE_AES_KEY, corrupt)
            .putString("synthetic-other-entry", "synthetic-sentinel").commit()
        val before = prefs.all.toMap()
        val store = AndroidSecureStore(context, name, "voice-preservation-synthetic-unused-alias")
        repeat(2) {
            assertFailsWith<SecureStoreUnavailableException> { store.getString(SecureKeys.VOICE_PROFILE_AES_KEY) }
            assertEquals(before, prefs.all)
        }
        prefs.edit().clear().commit()
    }

    @Test
    fun corrupt_non_voice_entry_is_reported_without_removing_it_or_its_sibling(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "voice-preservation-disposable-synthetic-test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().putString("synthetic-disposable-entry", "AA==")
            .putString("synthetic-sentinel", "unchanged").commit()
        val store = AndroidSecureStore(context, name, "voice-preservation-synthetic-unused-alias")
        assertFailsWith<SecureStoreUnavailableException> { store.getString("synthetic-disposable-entry") }
        assertEquals("AA==", prefs.getString("synthetic-disposable-entry", null))
        assertEquals("unchanged", prefs.getString("synthetic-sentinel", null))
        prefs.edit().clear().commit()
    }
}
