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
import kotlinx.coroutines.launch
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

    // The channel is process-wide: take what this class's own tests queued so the next test starts empty.
    @org.junit.After fun drain() = kotlinx.coroutines.runBlocking { while (kotlinx.coroutines.withTimeoutOrNull(300) { IncomingShare.next() } != null) Unit }
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

    @Test fun a_user_id_or_encoded_prefix_does_not_hide_the_apps_own_provider() {
        assertNull(accept(Intent.ACTION_VIEW, "content://0@$own/share/x.docx"))
        assertNull(accept(Intent.ACTION_VIEW, "content://0%40$own/share/x.docx"))
        assertNull(accept(Intent.ACTION_VIEW, "content://10@${own.uppercase()}/share/x.docx"))
        assertNotNull(accept(Intent.ACTION_VIEW, "content://0@media/1.mp3")) // another provider with a prefix is fine
    }

    @Test fun the_apps_own_provider_is_rejected() {
        assertNull(accept(Intent.ACTION_VIEW, "content://$own/files/secret"))
        assertNull(accept(Intent.ACTION_VIEW, "content://${own.uppercase()}/files/secret"))
    }

    @Test fun multibyte_display_name_uses_bounded_scratch_name() {
        val name = "音".repeat(70) + ".wav"
        val uri = Uri.parse("content://test.provider/${Uri.encode(name)}")
        shadowOf(context.contentResolver).registerInputStream(uri, java.io.ByteArrayInputStream(byteArrayOf(1)))
        val file = File(assertNotNull(IncomingShare.copyShare(context, uri)))
        kotlin.test.assertTrue((file.name + ".tmp").toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(name, dev.velum.app.importing.ImportDisplayNames.take(file.path))
        assertEquals("wav", file.extension)
        IncomingShare.markShareHandled(file.path)
        file.delete()
    }

    @Test fun a_failed_copy_leaves_no_file_and_returns_null() {
        File(context.cacheDir, "imports").deleteRecursively()
        val u = Uri.parse("content://test.provider/boom.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, object : InputStream() { override fun read(): Int = throw IOException("gone") })
        assertNull(IncomingShare.copyShare(context, u))
        assertEquals(emptyList(), File(context.cacheDir, "imports").listFiles().orEmpty().toList())
    }

    @Test(timeout = 30_000) fun offering_a_share_returns_before_the_copy_and_the_path_arrives_later() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val u = Uri.parse("content://test.provider/slow.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, object : InputStream() {
            private var n = 0
            override fun read(): Int { if (n == 0) gate.await(); return if (n++ < 10) 1 else -1 }
        })
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, u)
        IncomingShare.offerFromIntent(context, intent) // would hang forever here if the copy ran on the caller's thread
        gate.countDown()
        val shared = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { IncomingShare.next() } }
        assertEquals(10L, File((shared as SharedFile.Ready).path).length())
    }

    @Test fun a_failed_offered_share_is_reported_not_dropped() {
        val u = Uri.parse("content://test.provider/fail.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, object : InputStream() { override fun read(): Int = throw IOException("gone") })
        IncomingShare.offerFromIntent(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, u))
        val shared = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { IncomingShare.next() } }
        assertEquals(SharedFile.CopyFailed, shared)
    }

    private fun readableShare(name: String): Intent {
        val u = Uri.parse("content://test.provider/$name.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, java.io.ByteArrayInputStream(ByteArray(10) { 1 }))
        return Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, u)
    }

    @Test fun a_recreated_activity_does_not_offer_the_original_share_again() {
        val intent = readableShare("rotate")
        IncomingShare.offerOnCreate(context, intent, android.os.Bundle()) // rotation: savedInstanceState present
        val none = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(1_500) { IncomingShare.next() } }
        assertNull(none, "rotation must not re-import the share")
        IncomingShare.offerOnCreate(context, intent, null) // a fresh launch does
        val shared = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { IncomingShare.next() } }
        assertEquals(10L, File((shared as SharedFile.Ready).path).length())
    }

    private fun nothingArrives() = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(1_500) { IncomingShare.next() } }

    @Test fun offering_applies_the_uri_rules_not_just_acceptedUri() {
        // Readable streams behind rejected URIs: if the rules were skipped these would be copied and queued.
        val ownUri = Uri.parse("content://0@$own/share/x.mp3")
        shadowOf(context.contentResolver).registerInputStream(ownUri, java.io.ByteArrayInputStream(ByteArray(10) { 1 }))
        IncomingShare.offerFromIntent(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, ownUri))
        val file = Uri.parse("file:///data/data/dev.velum.app/databases/velum.db")
        IncomingShare.offerFromIntent(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, file))
        IncomingShare.offerFromIntent(context, Intent(Intent.ACTION_EDIT).setData(Uri.parse("content://test.provider/e.mp3")))
        assertNull(nothingArrives())
    }

    @Test fun a_view_intent_is_offered_from_its_data_uri() {
        val u = Uri.parse("content://test.provider/view.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, java.io.ByteArrayInputStream(ByteArray(10) { 1 }))
        IncomingShare.offerFromIntent(context, Intent(Intent.ACTION_VIEW).setData(u))
        val shared = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { IncomingShare.next() } }
        assertEquals(10L, File((shared as SharedFile.Ready).path).length())
    }

    @Test fun reopening_from_recents_is_not_a_new_share() {
        val intent = readableShare("history").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        IncomingShare.offerOnCreate(context, intent, null)
        assertNull(nothingArrives())
    }

    @Test fun a_hostile_display_name_is_capped_and_cleaned() {
        assertEquals("abc", dev.velum.app.importing.ImportTitles.fromDisplayName("a\u0000b\nc.mp3"))
        assertEquals(dev.velum.app.importing.ImportTitles.MAX_TITLE_CHARS, dev.velum.app.importing.ImportTitles.fromDisplayName("x".repeat(5_000) + ".mp3")?.length)
    }

    @Test fun an_alias_authority_of_this_package_is_rejected_by_the_provider_lookup() {
        val alias = "dev.velum.app.someotherprovider"
        shadowOf(context.packageManager).addOrUpdateProvider(
            android.content.pm.ProviderInfo().apply { authority = alias; name = "x.Other"; packageName = context.packageName },
        )
        val u = Uri.parse("content://$alias/f/x.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, java.io.ByteArrayInputStream(ByteArray(10) { 1 }))
        IncomingShare.offerFromIntent(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, u))
        assertNull(nothingArrives(), "a provider of this package must not be importable, whatever its authority")
    }

    @Test fun a_hostile_provider_name_is_cleaned_before_it_is_stored() {
        assertEquals("recgnp.mp3", IncomingShare.cleanName("rec\u202Eg\u0000n\u200Bp.mp3"))
        assertEquals(120, IncomingShare.cleanName("x".repeat(5_000))?.length)
        assertNull(IncomingShare.cleanName("\u202E\u0000"))
        assertNull(IncomingShare.cleanName(null))
    }

    @Test fun a_share_taken_by_a_receiver_cancelled_at_that_moment_is_requeued_not_lost() {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val job = scope.launch { IncomingShare.next() }
        dispatcher.scheduler.runCurrent() // the receiver is now suspended in next()
        IncomingShare.enqueue(SharedFile.Ready("/tmp/requeued"))
        job.cancel() // cancelled before it could run with the element
        dispatcher.scheduler.advanceUntilIdle()
        val again = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(5_000) { IncomingShare.next() } }
        assertEquals(SharedFile.Ready("/tmp/requeued"), again)
    }
}
