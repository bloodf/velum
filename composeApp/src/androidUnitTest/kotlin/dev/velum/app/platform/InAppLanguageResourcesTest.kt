package dev.velum.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.inAppLanguage
import dev.velum.app.R
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #331: notification and mic strings follow the in-app language, not only the device locale. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "pt-rBR")
class InAppLanguageResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun reset() { AiRuntimeHolder.settings = null }

    private fun withLanguage(tag: String, block: () -> Unit) {
        val settings = SettingsRepository(InMemorySecureStore())
        runBlocking { settings.load(); settings.setAppLanguage(tag) }
        AiRuntimeHolder.settings = settings
        block()
    }

    @Test fun english_in_app_on_a_portuguese_device_gives_english_strings() = withLanguage("en") {
        assertEquals("Pause", context.inAppLanguage().getString(R.string.mic_recording_pause))
        assertEquals("Microphone recording was cancelled.", context.inAppLanguage().getString(R.string.mic_recording_cancelled))
    }

    @Test fun portuguese_in_app_gives_portuguese_strings() = withLanguage("pt-BR") {
        assertEquals("Pausar", context.inAppLanguage().getString(R.string.mic_recording_pause))
    }

    @Test fun system_follows_the_device() = withLanguage("system") {
        assertEquals("Pausar", context.inAppLanguage().getString(R.string.mic_recording_pause))
    }

    @Test fun without_settings_the_device_language_is_used() {
        AiRuntimeHolder.settings = null
        assertEquals("Pausar", context.inAppLanguage().getString(R.string.mic_recording_pause))
    }
}
