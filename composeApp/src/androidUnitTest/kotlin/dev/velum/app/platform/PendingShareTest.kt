package dev.velum.app.platform

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #405: a share copied by a process that died before importing it is offered again once, and not forever. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PendingShareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_000_000_000_000L

    @Before fun clean() {
        context.getSharedPreferences("velum_pending_shares", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "imports").deleteRecursively()
        installAppContext(context)
        drain()
    }

    private fun drain() = runBlocking { while (withTimeoutOrNull(300) { IncomingShare.next() } != null) Unit }

    private fun copyOne(name: String): String {
        val u = Uri.parse("content://test.provider/$name.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, ByteArrayInputStream(ByteArray(10) { 1 }))
        return assertNotNull(IncomingShare.copyShare(context, u))
    }

    @Test fun a_copied_share_is_re_offered_after_the_process_restarts() {
        val path = copyOne("died") // copied, then the process died before the import ran
        drain() // the new process starts with an empty channel and no memory of the share
        assertEquals(listOf(path), IncomingShare.recoverPending(context, System.currentTimeMillis()))
        val shared = runBlocking { withTimeout(5_000) { IncomingShare.next() } }
        assertEquals(SharedFile.Ready(path), shared)
    }

    @Test fun a_handled_share_is_not_re_offered() {
        val path = copyOne("done")
        markShareHandled(path) // imported, duplicate, failed or refused in this process
        drain()
        assertEquals(emptyList(), IncomingShare.recoverPending(context, System.currentTimeMillis()))
    }

    @Test fun a_missing_copy_and_an_expired_one_are_dropped_and_the_expired_file_deleted() {
        val gone = copyOne("gone").also { File(it).delete() } // wiped or cache cleared
        val old = copyOne("old")
        val later = System.currentTimeMillis() + IncomingShare.MAX_PENDING_AGE_MS + 1
        drain()
        assertEquals(emptyList(), IncomingShare.recoverPending(context, later))
        assertFalse(File(old).exists(), "an expired copy must not stay in the cache")
        assertFalse(File(gone).exists())
        assertEquals(emptyList(), IncomingShare.recoverPending(context, later), "dropped entries must not come back")
    }

    @Test fun re_offering_runs_once_per_start_and_the_marker_stays_until_handled() {
        val path = copyOne("twice")
        drain()
        assertEquals(listOf(path), IncomingShare.recoverPending(context, System.currentTimeMillis()))
        drain()
        // A second death before the import finished: still pending, still offered (hash dedupe makes this safe).
        assertEquals(listOf(path), IncomingShare.recoverPending(context, System.currentTimeMillis()))
        markShareHandled(path)
        drain()
        assertTrue(IncomingShare.recoverPending(context, System.currentTimeMillis()).isEmpty())
    }
}
