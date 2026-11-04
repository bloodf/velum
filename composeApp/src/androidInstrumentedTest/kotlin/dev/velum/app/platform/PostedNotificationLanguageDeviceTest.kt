package dev.velum.app.platform

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.app.MainActivity
import dev.velum.app.R
import dev.velum.app.VelumApplication
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #331 criterion 1 on a device: starts a real microphone recording through the app's own entry point
 * (`startPhoneMicDraft`) while MainActivity is in the foreground (so the microphone foreground service may start),
 * reads the notification the system actually holds for the mic service, and provokes a user-visible mic error by
 * starting a second capture while one is running. The expectations are derived independently of the code under
 * test: the strings come from a `createConfigurationContext` built for the language the app has stored (not through
 * `inAppLanguage()`, and not from the notification). Asserted: a notification exists; its title, text, channel
 * name and action titles equal the expected strings; the second start fails with the expected error text; and the
 * two languages' expected strings differ (otherwise the comparison proves nothing).
 * - `notifLang` = `pt-BR`, `en` or `system` sets the in-app language first; `keep` leaves what is stored (a cold start
 *   after `am force-stop` is checked with `keep`) and REQUIRES `notifExpect` = the language an earlier step stored: the
 *   test fails if the store holds anything else, so an empty store cannot pass as "kept".
 * - The language is left as set so the next step can read it back; only a step with `notifRestore=true` puts the
 *   language that was stored when that step started back.
 * - If the first start does not report Started (for example the foreground service was refused because the app was in
 *   the background), the test FAILS with that fact; it is never recorded as a language result.
 * Reports the case in the `velum.postedNotif` status line. Skipped unless `notifLang` is given.
 *
 * Run: adb -s emulator-5554 shell am instrument -w -e class dev.velum.app.platform.PostedNotificationLanguageDeviceTest \\
 *   -e notifLang pt-BR -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class PostedNotificationLanguageDeviceTest {
    @Test fun posted_mic_notification_and_start_error_follow_the_in_app_language() {
        val lang = InstrumentationRegistry.getArguments().getString("notifLang")
        assumeTrue("notifLang not given", lang != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val container = runBlocking { (ctx.applicationContext as VelumApplication).awaitContainer() }
        val previous = runBlocking { container.settingsRepository.load(); container.settingsRepository.preferences.value.appLanguage }
        val report = StringBuilder()
        var draftId: String? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            if (lang != "keep") runBlocking { container.settingsRepository.setAppLanguage(lang!!) }
            val stored = container.settingsRepository.preferences.value.appLanguage
            if (lang == "keep") {
                val want = InstrumentationRegistry.getArguments().getString("notifExpect")
                assertNotNull("notifLang=keep needs notifExpect (the language the earlier step stored)", want)
                assertEquals("the stored in-app language did not survive the restart", want, stored)
            }
            fun stringsFor(tag: String): (Int) -> String {
                val c = if (tag == "system") ctx else ctx.createConfigurationContext(
                    Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
                )
                return { id -> c.getString(id) }
            }
            val expected = stringsFor(stored)
            // The comparison must be able to fail: the two languages differ for every string used below.
            val ids = listOf(
                R.string.mic_recording_title, R.string.mic_recording_body, R.string.mic_recording_channel_name,
                R.string.mic_recording_pause, R.string.mic_recording_stop, R.string.mic_recording_start_failed,
            )
            for (id in ids) assertNotEquals("pt-BR and en must differ for ${ctx.resources.getResourceEntryName(id)}", stringsFor("en")(id), stringsFor("pt-BR")(id))
            report.append("inApp=$stored deviceLocale=${ctx.resources.configuration.locales[0]} appLocales=${ctx.resources.configuration.locales}")

            val nm = ctx.getSystemService(NotificationManager::class.java)
            val start = runBlocking { startPhoneMicDraft(container) }
            draftId = start.recordingId
            val started = start.result
            assertTrue(
                "mic start was ${started::class.simpleName}" + (if (started is MicStartResult.Failed) " '${started.message}'" else "") +
                    ": not a language result (foreground service refused or no permission)",
                started is MicStartResult.Started,
            )
            val end = System.currentTimeMillis() + 15_000
            var posted: Notification? = null
            while (posted == null && System.currentTimeMillis() < end) {
                posted = nm.activeNotifications.firstOrNull { it.notification.channelId == MicRecordingService.CHANNEL_ID }?.notification
                if (posted == null) Thread.sleep(300)
            }
            assertNotNull("no mic notification was posted within 15 s", posted)
            val title = posted!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val channel = nm.getNotificationChannel(MicRecordingService.CHANNEL_ID)?.name?.toString()
            val actions = posted.actions.orEmpty().map { it.title.toString() }
            report.append(" title='$title' text='$text' channel='$channel' actions=$actions")
            assertEquals("title", expected(R.string.mic_recording_title, expected), title)
            assertEquals("text", expected(R.string.mic_recording_body, expected), text)
            assertEquals("channel name", expected(R.string.mic_recording_channel_name, expected), channel)
            assertEquals("action titles", listOf(expected(R.string.mic_recording_pause, expected), expected(R.string.mic_recording_stop, expected)), actions)

            // A user-visible error without any hack: a second capture while one is running is refused with a message.
            val second = runBlocking { container.micCapture.start(ctx.cacheDir.absolutePath + "/second-capture.wav", MicInputSource.Mic) }
            assertTrue("second start was ${second::class.simpleName}, expected Failed", second is MicStartResult.Failed)
            val message = (second as MicStartResult.Failed).message
            report.append(" error='$message'")
            assertEquals("error text", expected(R.string.mic_recording_start_failed, expected), message)
        } finally {
            runCatching { runBlocking { container.micCapture.stop() } }
            // Clean the draft up through the app's own delete path, then put the language back.
            draftId?.let { id -> runCatching { runBlocking { container.deleteRecordingForever(id) } } }
            if (InstrumentationRegistry.getArguments().getString("notifRestore") == "true") {
                runCatching { runBlocking { container.settingsRepository.setAppLanguage(previous) } }
            }
            runCatching { scenario.close() }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.postedNotif", report.toString()) })
        }
    }

    private fun expected(id: Int, strings: (Int) -> String) = strings(id)
}
