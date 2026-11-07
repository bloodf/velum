package dev.velum.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.forgetCachedAppLanguageHint
import dev.velum.ai.pipeline.inAppLanguage
import dev.velum.ai.pipeline.keepAppLanguageHint
import dev.velum.ai.pipeline.saveAppLanguageHint
import dev.velum.app.R
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test fun a_cold_process_job_uses_the_saved_language_before_the_runtime_is_installed() {
        AiRuntimeHolder.settings = null // no AiRuntimeHolder.install yet
        saveAppLanguageHint(context, "pt-BR")
        forgetCachedAppLanguageHint() // a new process: the hint is only on disk
        try {
            // On the main thread (Application.onCreate) nothing is read from disk: the device language stays.
            assertEquals("Pause", context.inAppLanguage().getString(R.string.mic_recording_pause))
            // Off the main thread, as a WorkManager job is.
            var text = ""
            val t = Thread { text = context.inAppLanguage().getString(R.string.mic_recording_pause) }
            t.start(); t.join()
            assertEquals("Pausar", text)
            // Read once, it is in memory, so the main thread can use it too.
            assertEquals("Pausar", context.inAppLanguage().getString(R.string.mic_recording_pause))
        } finally {
            saveAppLanguageHint(context, null)
        }
    }

    private fun offMain(block: () -> String): String {
        var out = ""
        val t = Thread { out = block() }
        t.start(); t.join()
        return out
    }

    @Test fun an_installed_but_not_loaded_repository_does_not_hide_the_saved_language() {
        saveAppLanguageHint(context, "pt-BR")
        try {
            // Installed (non-null) yet load() has not run: its preferences are the "system" defaults.
            AiRuntimeHolder.settings = SettingsRepository(InMemorySecureStore())
            assertEquals("Pausar", offMain { context.inAppLanguage().getString(R.string.mic_recording_pause) })
        } finally {
            saveAppLanguageHint(context, null)
        }
    }

    @Test fun a_loaded_repository_wins_over_the_hint() = withLanguage("en") {
        saveAppLanguageHint(context, "pt-BR")
        try {
            assertEquals("Pause", offMain { context.inAppLanguage().getString(R.string.mic_recording_pause) })
        } finally {
            saveAppLanguageHint(context, null)
        }
    }

    @Test fun the_hint_is_written_only_after_the_settings_loaded() = runBlocking {
        saveAppLanguageHint(context, "pt-BR")
        val prefs = context.getSharedPreferences("velum_app_language_hint", Context.MODE_PRIVATE)
        val settings = SettingsRepository(InMemorySecureStore())
        val job = launch(Dispatchers.Default) { keepAppLanguageHint(context, settings) }
        try {
            delay(300) // the collector had every chance to write the default
            assertEquals("pt-BR", prefs.getString("app_language", null), "default must not overwrite the hint before load")
            settings.load()
            settings.setAppLanguage("en")
            withTimeout(5_000) { while (prefs.getString("app_language", null) != "en") delay(20) }
        } finally {
            job.cancelAndJoin()
            saveAppLanguageHint(context, null)
        }
    }
}
