package dev.velum.app.platform

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.app.VelumApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #331 criterion 1 on a device: starts a real microphone recording through the app's own entry point
 * (`startPhoneMicDraft`), reads the notification the system actually holds for the mic service (title, text, channel
 * name) from NotificationManager.activeNotifications, then stops capture. When RECORD_AUDIO is not granted the same
 * entry point returns the user-visible error text, which is reported too.
 * - `notifLang` = `pt-BR`, `en` or `system` sets the in-app language first; `keep` leaves what is stored (that is how
 *   a cold start after `am force-stop` is checked).
 * The app should be in the foreground (`am start`) so the microphone foreground service may start. Reports the result
 * in the `velum.postedNotif` status line. Skipped unless `notifLang` is given.
 *
 * Run: adb -s emulator-5554 shell am instrument -w -e class dev.velum.app.platform.PostedNotificationLanguageDeviceTest \
 *   -e notifLang pt-BR -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class PostedNotificationLanguageDeviceTest {
    @Test fun posted_mic_notification_and_start_error_follow_the_in_app_language() {
        val lang = InstrumentationRegistry.getArguments().getString("notifLang")
        assumeTrue("notifLang not given", lang != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val container = runBlocking { (ctx.applicationContext as VelumApplication).awaitContainer() }
        runBlocking {
            container.settingsRepository.load()
            if (lang != "keep") container.settingsRepository.setAppLanguage(lang!!)
        }
        val stored = container.settingsRepository.preferences.value.appLanguage
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val start = runBlocking { startPhoneMicDraft(container) }
        val line = StringBuilder("inApp=$stored appLocale=${ctx.resources.configuration.locales[0]} result=${start.result::class.simpleName}")
        try {
            when (val r = start.result) {
                is MicStartResult.Started -> {
                    val end = System.currentTimeMillis() + 15_000
                    var posted: Notification? = null
                    var channel = ""
                    while (posted == null && System.currentTimeMillis() < end) {
                        nm.activeNotifications.firstOrNull { it.notification.channelId == MicRecordingService.CHANNEL_ID }?.let {
                            posted = it.notification
                            channel = nm.getNotificationChannel(it.notification.channelId)?.name?.toString().orEmpty()
                        }
                        if (posted == null) Thread.sleep(300)
                    }
                    val n = posted
                    line.append(" title='${n?.extras?.getCharSequence(Notification.EXTRA_TITLE)}' text='${n?.extras?.getCharSequence(Notification.EXTRA_TEXT)}' channel='$channel'")
                    n?.actions?.let { a -> line.append(" actions=").append(a.joinToString("/") { it.title }) }
                }
                is MicStartResult.Failed -> line.append(" message='${r.message}'")
                else -> Unit
            }
        } finally {
            runCatching { runBlocking { container.micCapture.stop() } }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("velum.postedNotif", line.toString()) })
        }
    }
}
