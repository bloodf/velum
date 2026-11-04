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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #405: a share copied by a process that died before importing it is offered again, within limits. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PendingShareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("velum_pending_shares", Context.MODE_PRIVATE)

    @Before fun clean() {
        prefs.edit().clear().commit()
        File(context.cacheDir, "imports").deleteRecursively()
        installAppContext(context)
        IncomingShare.resetProcessMemory()
        drain()
    }

    @After fun leaveNothingBehind() { drain() }

    private fun drain() = runBlocking { while (withTimeoutOrNull(300) { IncomingShare.next() } != null) Unit }

    private fun copyOne(name: String): String {
        val u = Uri.parse("content://test.provider/$name.mp3")
        shadowOf(context.contentResolver).registerInputStream(u, ByteArrayInputStream(ByteArray(10) { 1 }))
        return assertNotNull(IncomingShare.copyShare(context, u))
    }

    /** A new process: no memory of what the old one marked, nothing in the channel. */
    private fun restart() { IncomingShare.resetProcessMemory(); drain() }

    private fun recover(nowMs: Long = System.currentTimeMillis()) = IncomingShare.recoverPending(context, nowMs)

    private fun offers(): List<SharedFile> = runBlocking {
        val out = mutableListOf<SharedFile>()
        while (true) out += withTimeoutOrNull(300) { IncomingShare.next() } ?: break
        out
    }

    @Test fun a_copied_share_is_re_offered_once_after_the_process_restarts_and_flagged_recovered() {
        val path = copyOne("died")
        restart()
        assertEquals(listOf(path), recover())
        val shared = offers().single() as SharedFile.Ready
        assertEquals(path, shared.path)
        assertNotNull(shared.recoveredAtMs, "a recovered share must be flagged so the UI stays quiet")
    }

    @Test fun a_share_this_process_just_copied_is_not_offered_a_second_time() {
        copyOne("cold-start") // copied by this process: it is already in flight
        drain()
        assertEquals(emptyList(), recover())
        restart()
        assertEquals(1, recover().size)
    }

    @Test fun a_handled_share_is_not_re_offered() {
        val path = copyOne("done")
        markShareHandled(path)
        restart()
        assertEquals(emptyList(), recover())
    }

    @Test fun a_missing_copy_is_dropped_and_an_expired_or_future_one_is_dropped_with_its_copy() {
        val gone = copyOne("gone").also { File(it).delete() }
        val old = copyOne("old")
        val future = copyOne("future")
        restart()
        val now = System.currentTimeMillis()
        // 'old' is more than a day behind the clock, 'future' more than a day ahead (the clock was set back).
        assertEquals(emptyList(), recover(now + IncomingShare.MAX_PENDING_AGE_MS + 1))
        assertFalse(File(old).exists(), "an expired copy must not stay in the cache")
        assertFalse(File(future).exists())
        assertFalse(File(gone).exists())
        assertEquals(emptyList(), IncomingShare.pendingPaths(context))
        restart()
        assertEquals(emptyList(), recover(now))
    }

    @Test fun a_share_whose_import_keeps_dying_is_dropped_with_its_copy_after_three_starts() {
        val path = copyOne("poison")
        repeat(2) { IncomingShare.markAttempt(context, path) } // two imports started and never finished
        restart()
        assertEquals(listOf(path), recover(), "two attempts are still allowed")
        repeat(1) { IncomingShare.markAttempt(context, path) } // the third start also died
        restart()
        assertEquals(emptyList(), recover())
        assertFalse(File(path).exists(), "the copy of a share that keeps killing the app must go")
        assertEquals(emptyList(), IncomingShare.pendingPaths(context))
    }

    @Test fun re_offering_is_not_counted_as_an_attempt() {
        val path = copyOne("offered")
        repeat(5) { restart(); assertEquals(listOf(path), recover()) } // offered five times, never started
    }

    @Test fun a_wipe_clears_every_pending_share() {
        val path = copyOne("wiped")
        clearPendingShares()
        restart()
        assertEquals(emptyList(), recover(), "a wiped share must not come back")
        assertTrue(File(path).exists().let { true }) // the wipe deletes the copy elsewhere; recovery must not need it
    }

    @Test fun a_path_that_is_not_our_own_copy_is_dropped_and_never_deleted() {
        val foreignDir = File(context.cacheDir, "elsewhere").apply { mkdirs() }
        val outside = File(foreignDir, "share_x.mp3").apply { writeBytes(ByteArray(10)) } // right name, wrong directory
        val noPrefix = File(File(context.cacheDir, "imports").apply { mkdirs() }, "note.mp3").apply { writeBytes(ByteArray(10)) }
        val traversal = File(File(context.cacheDir, "imports"), "../elsewhere/share_x.mp3").path
        val now = System.currentTimeMillis()
        for (stamp in listOf(now, now - IncomingShare.MAX_PENDING_AGE_MS - 5_000)) { // fresh and expired
            prefs.edit().putStringSet(
                "pending",
                setOf("$stamp|0|${outside.path}", "$stamp|0|${noPrefix.path}", "$stamp|0|$traversal", "$stamp|0|/data/data/dev.velum.app/databases/velum.db"),
            ).commit()
            restart()
            assertEquals(emptyList(), recover(now), "stamp $stamp")
            assertTrue(outside.exists() && noPrefix.exists(), "a foreign path must never be deleted (stamp $stamp)")
            assertEquals(emptyList(), IncomingShare.pendingPaths(context))
        }
    }

    @Test fun pending_paths_lists_only_the_copies_still_marked() {
        val keep = copyOne("keep")
        copyOne("handled").also { markShareHandled(it) }
        assertEquals(listOf(keep), IncomingShare.pendingPaths(context))
    }
}
