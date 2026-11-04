package dev.velum.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.saveAppLanguageHint
import dev.velum.app.platform.DownloadProgressNotifier
import dev.velum.app.platform.MicRecordingService
import dev.velum.app.platform.installAppContext
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #348: when settings never loaded, the fatal screen and the first channels still use the saved language. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "en-rUS")
class ColdStartLanguageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun noSettings() {
        AiRuntimeHolder.settings = null
        installAppContext(context)
    }

    @After fun reset() {
        saveAppLanguageHint(context, null)
    }

    private fun <T> offMain(block: () -> T): T {
        var result: Result<T>? = null
        Thread { result = runCatching(block) }.apply { start(); join() }
        return result!!.getOrThrow()
    }

    @Test fun fatal_text_uses_the_saved_language() {
        saveAppLanguageHint(context, "pt-BR")
        assertEquals("O Velum não iniciou. Reinstale ou limpe o armazenamento do app.", offMain { context.fatalStartText() })
    }

    @Test fun fatal_text_shows_one_language_and_follows_the_device_without_a_saved_one() {
        assertEquals("Velum failed to start. Reinstall or clear app storage.", offMain { context.fatalStartText() })
    }

    @Test @Config(qualifiers = "pt-rBR") fun fatal_text_follows_a_portuguese_device_without_a_saved_one() {
        assertEquals("O Velum não iniciou. Reinstale ou limpe o armazenamento do app.", offMain { context.fatalStartText() })
    }

    @Test @Config(qualifiers = "pt-rBR") fun a_saved_english_choice_wins_on_a_portuguese_device() {
        saveAppLanguageHint(context, "en")
        assertEquals("Velum failed to start. Reinstall or clear app storage.", offMain { context.fatalStartText() })
    }

    @Test fun channels_created_off_main_before_settings_load_use_the_saved_language() {
        saveAppLanguageHint(context, "pt-BR")
        offMain {
            MicRecordingService.ensureNotificationChannel(context)
            DownloadProgressNotifier.ensureChannel()
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        assertEquals("Gravação pelo microfone", nm.getNotificationChannel(MicRecordingService.CHANNEL_ID).name)
        // The download channel's name is the same in both languages, so check its description.
        assertEquals("Progresso ao baixar sessões do aparelho ou modelos locais", nm.getNotificationChannel("velum_downloads").description)
    }
}
