package dev.velum.app

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.RecordingProcessWorker
import dev.velum.ai.pipeline.forgetCachedAppLanguageHint
import dev.velum.ai.pipeline.saveAppLanguageHint
import dev.velum.app.platform.MicRecordingService
import dev.velum.app.platform.installAppContext
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #348: when settings have not loaded, the fatal screen and the first channels still use the saved language. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "en-rUS")
class ColdStartLanguageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fatalEn = "Velum failed to start. Reinstall or clear app storage."
    private val fatalPt = "O Velum não iniciou. Reinstale ou limpe o armazenamento do app."

    @Before fun noSettings() {
        AiRuntimeHolder.settings = null
        installAppContext(context)
        forgetCachedAppLanguageHint()
    }

    @After fun reset() {
        AiRuntimeHolder.settings = null
        saveAppLanguageHint(context, null)
        forgetCachedAppLanguageHint()
    }

    private fun <T> offMain(block: () -> T): T {
        var result: Result<T>? = null
        Thread { result = runCatching(block) }.apply { start(); join() }
        return result!!.getOrThrow()
    }

    private fun channelNames(): List<String> {
        val nm = context.getSystemService(NotificationManager::class.java)
        return listOf(
            nm.getNotificationChannel(MicRecordingService.CHANNEL_ID).name.toString(),
            // The download channel's name is the same in both languages, so check its description.
            nm.getNotificationChannel("velum_downloads").description,
            nm.getNotificationChannel(RecordingProcessWorker.PROCESSING_CHANNEL_ID).name.toString(),
        )
    }

    private val channelsEn = listOf("Microphone recording", "Progress while Velum downloads device sessions or on-device models", "Recording processing")
    private val channelsPt = listOf("Gravação pelo microfone", "Progresso ao baixar sessões do aparelho ou modelos locais", "Processamento de gravação")

    @Test fun fatal_text_uses_the_saved_language() {
        saveAppLanguageHint(context, "pt-BR")
        forgetCachedAppLanguageHint()
        assertEquals(fatalPt, offMain { context.fatalStartText() })
    }

    @Test fun fatal_text_shows_one_language_and_follows_the_device_without_a_saved_one() {
        assertEquals(fatalEn, offMain { context.fatalStartText() })
    }

    @Test @Config(qualifiers = "pt-rBR") fun fatal_text_follows_a_portuguese_device_without_a_saved_one() {
        assertEquals(fatalPt, offMain { context.fatalStartText() })
    }

    @Test @Config(qualifiers = "pt-rPT") fun another_portuguese_device_gets_the_portuguese_text() {
        assertEquals(fatalPt, offMain { context.fatalStartText() })
    }

    @Test @Config(qualifiers = "pt-rBR") fun a_saved_english_choice_wins_on_a_portuguese_device() {
        saveAppLanguageHint(context, "en")
        forgetCachedAppLanguageHint()
        assertEquals(fatalEn, offMain { context.fatalStartText() })
    }

    @Test fun fatal_text_falls_back_to_the_device_when_the_saved_language_cannot_be_read() {
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = error("synthetic")
        }
        assertEquals(fatalEn, offMain { failing.fatalStartText() })
    }

    @Test fun channels_first_created_off_main_use_the_saved_language() {
        saveAppLanguageHint(context, "pt-BR")
        forgetCachedAppLanguageHint()
        offMain { publishNotificationChannels(context) }
        assertEquals(channelsPt, channelNames())
    }

    @Test fun a_channel_created_on_main_uses_a_hint_already_read_off_main() {
        saveAppLanguageHint(context, "pt-BR")
        forgetCachedAppLanguageHint()
        offMain { context.fatalStartText() }
        publishNotificationChannels(context)
        assertEquals(channelsPt, channelNames())
    }

    @Test fun a_channel_created_on_main_before_any_read_is_corrected_once_settings_load() {
        saveAppLanguageHint(context, "pt-BR")
        forgetCachedAppLanguageHint()
        publishNotificationChannels(context)
        assertEquals(channelsEn, channelNames(), "on main the disk is not read, so the device's language is all there is")
        val settings = SettingsRepository(InMemorySecureStore())
        runBlocking { settings.load(); settings.setAppLanguage("pt-BR") }
        AiRuntimeHolder.settings = settings
        publishNotificationChannels(context)
        assertEquals(channelsPt, channelNames())
    }

    @Test fun a_channel_failure_does_not_escape() {
        val failing = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any = error("synthetic")
        }
        publishNotificationChannels(failing)
    }
}
