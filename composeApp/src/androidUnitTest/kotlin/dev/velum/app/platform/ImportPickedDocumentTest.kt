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
    fun multibyte_display_name_uses_bounded_scratch_name() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "音".repeat(70) + ".wav"
        val uri = Uri.parse("content://unregistered/${android.net.Uri.encode(name)}")
        var picked: String? = null
        importPickedDocument(context, uri, onFailed = { error("copy failed") }, onPicked = { picked = it },
            openStream = { java.io.ByteArrayInputStream(byteArrayOf(1)) })
        val file = File(checkNotNull(picked))
        assertTrue((file.name + ".tmp").toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(name, dev.velum.app.importing.ImportDisplayNames.take(file.path))
        assertEquals("wav", file.extension)
        file.delete()
        Unit
    }

    @Test
    fun providerFailure_callsOnFailed_notOnPicked_andLeavesNoCacheFile() = runBlocking {
        Robolectric.setupContentProvider(ThrowingDocumentProvider::class.java, "velum.test.docs")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var failed = 0
        var picked: String? = null
        var openAttempts = 0
        // Exercise the real throwing provider through the existing stream seam. Robolectric's
        // bound-service shadow does not launch the private Android copy process.
        importPickedDocument(context, uri, onFailed = { failed++ }, onPicked = { picked = it },
            openStream = { openAttempts++; context.contentResolver.openInputStream(uri) })
        assertEquals(1, openAttempts)
        assertEquals(1, failed)
        assertEquals(null, picked)
        val leftovers = File(context.cacheDir, "imports").listFiles().orEmpty()
        assertTrue(leftovers.isEmpty(), "partial cache files: ${leftovers.toList()}")
    }

    @Test
    fun providerFailure_logsTheClassNameOnly() = runBlocking {
        Robolectric.setupContentProvider(ThrowingDocumentProvider::class.java, "velum.test.docs")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        org.robolectric.shadows.ShadowLog.clear()
        importPickedDocument(context, uri, onFailed = {}, onPicked = {}, openStream = { throw SecurityException("secret-file-name.wav revoked") })
        val lines = org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag == "AudioImporter" }.map { it.msg }
        assertTrue(lines.any { it.contains("SecurityException") }, "logs: $lines")
        assertTrue(lines.none { it.contains("secret-file-name") || it.contains("revoked") }, "logs: $lines")
    }

    @Test
    fun cancellationMidCopy_isRethrown_removesThePartialFile_andIsNotAFailure() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.cacheDir, "imports").deleteRecursively()
        var failed = 0
        var picked = 0
        assertFailsWith<CancellationException> {
            runBlocking {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                importPickedDocument(
                    context, uri, onFailed = { failed++ }, onPicked = { picked++ },
                    openStream = {
                        object : java.io.InputStream() {
                            var n = 0
                            override fun read(): Int {
                                if (n == 3) job.cancel()
                                return if (n++ < 10) 1 else -1
                            }
                        }
                    },
                )
            }
        }
        assertEquals(0, failed)
        assertEquals(0, picked)
        assertTrue(File(context.cacheDir, "imports").listFiles().orEmpty().isEmpty(), "partial file left behind")
    }
}
