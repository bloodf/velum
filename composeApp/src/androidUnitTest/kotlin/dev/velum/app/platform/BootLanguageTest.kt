package dev.velum.app.platform

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.BootLanguage
import dev.velum.ai.pipeline.RecordingProcessWorker
import dev.velum.ai.pipeline.inAppLanguage
import dev.velum.app.R
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #348: before the settings vault opens, channels and the fatal screen use the boot copy of the in-app language. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "en")
class BootLanguageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    @Before fun noSettings() {
        AiRuntimeHolder.settings = null
        installAppContext(context)
    }

    @After fun reset() {
        AiRuntimeHolder.settings = null
        context.getSharedPreferences("velum_boot", Context.MODE_PRIVATE).edit().clear().commit()
        BootLanguage.load(context)
    }

    @Test fun the_copy_survives_a_process_restart_as_the_only_key() {
        BootLanguage.save(context, "pt-BR")
        assertEquals("pt-BR", BootLanguage.load(context))
        assertEquals(setOf("app_language"), context.getSharedPreferences("velum_boot", Context.MODE_PRIVATE).all.keys)
    }

    @Test fun a_fresh_install_has_no_copy_and_follows_the_device() {
        assertNull(BootLanguage.load(context))
        assertEquals("Velum failed to start. Reinstall or clear app storage.", context.inAppLanguage().getString(R.string.fatal_start_failed))
    }

    @Test fun channels_created_before_settings_load_use_the_copy() {
        BootLanguage.save(context, "pt-BR")
        BootLanguage.load(context)
        MicRecordingService.ensureNotificationChannel(context)
        DownloadProgressNotifier.ensureChannel()
        RecordingProcessWorker.ensureProcessingChannel(context)
        assertEquals("Gravação pelo microfone", nm.getNotificationChannel(MicRecordingService.CHANNEL_ID).name)
        val pt = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("pt-BR")) },
        )
        assertEquals(pt.getString(R.string.download_channel_name), nm.getNotificationChannel("velum_downloads").name)
        assertEquals(
            pt.getString(dev.velum.ai.R.string.velum_processing_channel_name),
            nm.getNotificationChannel(RecordingProcessWorker.PROCESSING_CHANNEL_ID).name,
        )
    }

    @Test fun the_fatal_screen_uses_the_copy() {
        BootLanguage.save(context, "pt-BR")
        assertEquals("O Velum não iniciou. Reinstale ou limpe o armazenamento do app.", context.inAppLanguage().getString(R.string.fatal_start_failed))
    }

    @Test fun loaded_settings_win_over_the_copy() {
        BootLanguage.save(context, "pt-BR")
        val settings = SettingsRepository(InMemorySecureStore())
        runBlocking { settings.load(); settings.setAppLanguage("en") }
        AiRuntimeHolder.settings = settings
        assertEquals("Microphone recording", context.inAppLanguage().getString(R.string.mic_recording_channel_name))
    }

    @Test fun wipe_resets_the_copy_to_system() {
        BootLanguage.save(context, "pt-BR")
        BootLanguage.clear(context)
        assertEquals("system", BootLanguage.load(context))
        assertEquals("Microphone recording", context.inAppLanguage().getString(R.string.mic_recording_channel_name))
    }
}
