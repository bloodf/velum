package dev.velum.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #329: which shares are accepted, that the copy cleans up, and that offering never blocks the caller. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IncomingShareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val own = "dev.velum.app.fileprovider"
    private fun accept(action: String?, uri: String?) = IncomingShare.acceptedUri(action, uri?.let(Uri::parse), own)

    @Test fun only_send_and_view_are_accepted() {
        assertNotNull(accept(Intent.ACTION_SEND, "content://media/1.mp3"))
        assertNotNull(accept(Intent.ACTION_VIEW, "content://media/1.mp3"))
        assertNull(accept(Intent.ACTION_MAIN, "content://media/1.mp3"))
        assertNull(accept(Intent.ACTION_EDIT, "content://media/1.mp3"))
        assertNull(accept(null, "content://media/1.mp3"))
    }

    @Test fun file_and_other_schemes_are_rejected() {
        for (u in listOf("file:///data/data/dev.velum.app/databases/velum.db", "http://x/a.mp3", "https://x/a.mp3", "android.resource://x/1", null)) {
            assertNull(accept(Intent.ACTION_SEND, u), u.toString())
        }
        assertNotNull(accept(Intent.ACTION_SEND, "CONTENT://media/1.mp3"))
    }

    @Test fun the_apps_own_provider_is_rejected() {
        assertNull(accept(Intent.ACTION_VIEW, "content://$own/files/secret"))
        assertNull(accept(Intent.ACTION_VIEW, "content://${own.uppercase()}/files/secret"))
    }

    @Test fun a_failed_copy_leaves_no_file_and_returns_null() {
        File(context.cacheDir, "imports").deleteRecursively()
        val u = Uri.parse("content://test.provider/boom.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, object : InputStream() { override fun read(): Int = throw IOException("gone") })
        assertNull(IncomingShare.copyShare(context, u))
        assertEquals(emptyList(), File(context.cacheDir, "imports").listFiles().orEmpty().toList())
    }

    @Test fun offering_a_share_returns_before_the_copy_and_the_path_arrives_later() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val u = Uri.parse("content://test.provider/slow.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, object : InputStream() {
            private var n = 0
            override fun read(): Int { if (n == 0) gate.await(); return if (n++ < 10) 1 else -1 }
        })
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, u)
        IncomingShare.offerFromIntent(context, intent) // would hang forever here if the copy ran on the caller's thread
        gate.countDown()
        val path = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { IncomingShare.next() } }
        assertEquals(10L, File(assertNotNull(path)).length())
    }
}
