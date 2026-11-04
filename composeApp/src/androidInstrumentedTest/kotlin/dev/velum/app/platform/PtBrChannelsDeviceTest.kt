package dev.velum.app.platform

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.pipeline.RecordingProcessWorker
import dev.velum.ai.pipeline.inAppLanguage
import dev.velum.app.VelumApplication
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #331/#348 on a device: with the in-app language set to Portuguese on an English device, the three notification
 * channels the app creates carry Portuguese names, and English again when the in-app language goes back to English.
 * Uses the app's own channel code and the live SettingsRepository. Skipped when the device language is not English
 * or when `ptbrChannels` is not given.
 *
 * Run: adb -s emulator-5554 shell am instrument -w -e class dev.velum.app.platform.PtBrChannelsDeviceTest \
 *   -e ptbrChannels true -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class PtBrChannelsDeviceTest {
    @Test fun channel_names_follow_the_in_app_language_on_an_english_device() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("ptbrChannels not given", args.getString("ptbrChannels") == "true")
        assumeTrue("device language is not English", Locale.getDefault().language == "en")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ctx.applicationContext as VelumApplication
        val container = runBlocking { app.awaitContainer() }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val lines = mutableListOf<String>()
        fun names(): Triple<String, String, String> {
            MicRecordingService.ensureNotificationChannel(ctx)
            DownloadProgressNotifier.ensureChannel()
            RecordingProcessWorker.ensureProcessingChannel(ctx)
            fun name(id: String) = nm.getNotificationChannel(id)!!.name.toString()
            return Triple(
                name(MicRecordingService.CHANNEL_ID),
                name(RecordingProcessWorker.PROCESSING_CHANNEL_ID),
                name("velum_downloads"),
            )
        }
        val previous = runBlocking { container.settingsRepository.load(); container.settingsRepository.preferences.value.appLanguage }
        try {
            runBlocking { container.settingsRepository.setAppLanguage("pt-BR") }
            val pt = names()
            lines += "pt-BR: mic='${pt.first}' processing='${pt.second}' downloads='${pt.third}'"
            assertEquals("Gravação pelo microfone", pt.first)
            assertEquals("Processamento de gravação", pt.second)
            assertEquals("Downloads", pt.third) // the same word in both languages
            runBlocking { container.settingsRepository.setAppLanguage("en") }
            val en = names()
            lines += "en: mic='${en.first}' processing='${en.second}' downloads='${en.third}'"
            assertEquals("Microphone recording", en.first)
            assertEquals("Recording processing", en.second)
            assertEquals("Downloads", en.third)
        } finally {
            // Put back what the device had, and re-create the channels so their names follow it again.
            runBlocking { container.settingsRepository.setAppLanguage(previous) }
            names()
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0, Bundle().apply { putString("velum.ptbr331", "device=${Locale.getDefault()} " + lines.joinToString(" | ")) },
            )
        }
    }

    /**
     * The notification and mic-error texts the app builds from Android resources, resolved through the same
     * `inAppLanguage()` the app uses, for Portuguese in-app on an English device. Each text must differ from the
     * English one and share no word of four or more letters with it, apart from the product and platform names
     * Velum and Android. This reads the texts; it does not post them.
     */
    @Test fun notification_and_mic_error_texts_follow_the_in_app_language_on_an_english_device() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("ptbrChannels not given", args.getString("ptbrChannels") == "true")
        assumeTrue("device language is not English", Locale.getDefault().language == "en")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val container = runBlocking { (ctx.applicationContext as VelumApplication).awaitContainer() }
        val ids = linkedMapOf(
            "processing_title" to dev.velum.ai.R.string.velum_processing_notification_title,
            "processing_body" to dev.velum.ai.R.string.velum_processing_notification_body,
            "refused_title" to dev.velum.ai.R.string.velum_processing_refused_title,
            "refused_body" to dev.velum.ai.R.string.velum_processing_refused_body,
            "mic_title" to dev.velum.app.R.string.mic_recording_title,
            "mic_body" to dev.velum.app.R.string.mic_recording_body,
            "mic_paused_body" to dev.velum.app.R.string.mic_recording_paused_body,
            "mic_permission" to dev.velum.app.R.string.mic_recording_permission_required,
            "mic_start_failed" to dev.velum.app.R.string.mic_recording_start_failed,
            "mic_capture_failed" to dev.velum.app.R.string.mic_recording_capture_failed,
            "mic_cancelled" to dev.velum.app.R.string.mic_recording_cancelled,
        )
        val lines = mutableListOf<String>()
        val previous = runBlocking { container.settingsRepository.load(); container.settingsRepository.preferences.value.appLanguage }
        fun words(text: String) = Regex("\\p{L}{4,}").findAll(text.lowercase()).map { it.value }.toSet() - setOf("velum", "android")
        try {
            runBlocking { container.settingsRepository.setAppLanguage("pt-BR") }
            for ((name, id) in ids) {
                val pt = ctx.inAppLanguage().getString(id)
                val en = ctx.getString(id) // device language = English
                lines += "$name: '$pt'"
                org.junit.Assert.assertNotEquals("$name still English", en, pt)
                org.junit.Assert.assertEquals("$name shares English words with the English text", emptySet<String>(), words(pt) intersect words(en))
            }
        } finally {
            runBlocking { container.settingsRepository.setAppLanguage(previous) }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.ptbr331texts", lines.joinToString(" | ")) })
        }
    }
}
