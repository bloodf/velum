package dev.velum.app.platform

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ShareTextTest {
    @Test fun application_context_launches_chooser_with_text_payload() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val previousContext = ShareTextHolder.context
        try {
            ShareTextHolder.context = app.applicationContext
            val title = "Synthetic summary \u2014 caf\u00e9"
            val text = "First line\nSecond line with synthetic text."

            shareText(title, text)

            assertSame(app, ShareTextHolder.context)
            val chooser = assertNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            assertEquals(title, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
            @Suppress("DEPRECATION")
            val send = assertNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(title, send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(0, send.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            ShareTextHolder.context = previousContext
        }
    }
}
