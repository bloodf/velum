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

/** #331, the reverse of [InAppLanguageResourcesTest]: Portuguese in-app on an English device. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class InAppLanguageOnEnglishDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun reset() { AiRuntimeHolder.settings = null }

    private fun withLanguage(tag: String, block: () -> Unit) {
        val settings = SettingsRepository(InMemorySecureStore())
        runBlocking { settings.load(); settings.setAppLanguage(tag) }
        AiRuntimeHolder.settings = settings
        block()
    }

    @Test fun portuguese_in_app_on_an_english_device_gives_portuguese_strings() = withLanguage("pt-BR") {
        assertEquals("Pausar", context.inAppLanguage().getString(R.string.mic_recording_pause))
        assertEquals("A gravação pelo microfone foi cancelada.", context.inAppLanguage().getString(R.string.mic_recording_cancelled))
        assertEquals("Pausar", context.inAppLanguage().getString(R.string.mic_recording_pause))
    }

    @Test fun english_in_app_on_an_english_device_stays_english() = withLanguage("en") {
        assertEquals("Pause", context.inAppLanguage().getString(R.string.mic_recording_pause))
    }

    @Test fun system_and_missing_settings_follow_the_english_device() {
        withLanguage("system") { assertEquals("Pause", context.inAppLanguage().getString(R.string.mic_recording_pause)) }
        AiRuntimeHolder.settings = null
        assertEquals("Pause", context.inAppLanguage().getString(R.string.mic_recording_pause))
    }
}
