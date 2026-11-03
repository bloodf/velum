package dev.velum.app.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** A provider whose stream throws, like an offline Drive or a revoked grant. */
class ThrowingDocumentProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = throw SecurityException("revoked")
    override fun getType(u: Uri): String? = "audio/wav"
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    override fun openFile(u: Uri, mode: String): ParcelFileDescriptor = throw SecurityException("revoked")
}

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class ImportPickedDocumentTest {
    private val uri = Uri.parse("content://velum.test.docs/doc/1")

    @Test
    fun providerFailure_callsOnFailed_notOnPicked_andLeavesNoCacheFile() = runBlocking {
        Robolectric.setupContentProvider(ThrowingDocumentProvider::class.java, "velum.test.docs")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var failed = 0
        var picked: String? = null
        importPickedDocument(context, uri, onFailed = { failed++ }, onPicked = { picked = it })
        assertEquals(1, failed)
        assertEquals(null, picked)
        val leftovers = File(context.cacheDir, "imports").listFiles().orEmpty()
        assertTrue(leftovers.isEmpty(), "partial cache files: ${leftovers.toList()}")
    }

    @Test
    fun cancellation_isRethrown_notReportedAsFailure() {
        Robolectric.setupContentProvider(ThrowingDocumentProvider::class.java, "velum.test.docs")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var failed = 0
        assertFailsWith<CancellationException> {
            runBlocking {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                importPickedDocument(context, uri, onFailed = { failed++ }, onPicked = {})
            }
        }
        assertEquals(0, failed)
    }
}
