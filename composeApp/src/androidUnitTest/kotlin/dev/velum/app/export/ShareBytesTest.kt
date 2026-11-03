package dev.velum.app.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.velum.app.platform.ShareTextHolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ShareBytesTest {
    @Test fun writes_the_file_and_opens_the_share_sheet() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ShareTextHolder.context = app
        val bytes = byteArrayOf(1, 2, 3)

        shareBytes("synthetic export.docx", "application/octet-stream", bytes)

        assertContentEquals(bytes, File(app.cacheDir, "share/synthetic_export.docx").readBytes())
        val chooser = assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = assertNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        @Suppress("DEPRECATION")
        assertNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }
}
