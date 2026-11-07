package dev.velum.app.ui.recording

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #345: what the screen does after `prepare` must run on Main even when the caller resumed on the IO worker. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AfterPrepareTest {
    private fun runFromWorker(ok: Boolean, seekMs: Long): List<Pair<String, Thread>> {
        val seen = CopyOnWriteArrayList<Pair<String, Thread>>()
        // Not on the test (main) thread, like the IO worker a resumed LaunchedEffect can be left on.
        val worker = thread {
            runBlocking {
                afterPrepare(
                    ok = ok,
                    seekMs = seekMs,
                    seekTo = { seen += "seek:$it" to Thread.currentThread() },
                    onUnavailable = { seen += "unavailable" to Thread.currentThread() },
                )
            }
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (worker.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(!worker.isAlive, "afterPrepare did not finish")
        return seen
    }

    @Test fun the_seek_runs_on_the_main_thread() {
        val seen = runFromWorker(ok = true, seekMs = 4_000)
        assertEquals(listOf("seek:4000"), seen.map { it.first })
        assertTrue(seen.all { it.second === Thread.currentThread() }, "seek ran on ${seen.map { it.second.name }}")
    }

    @Test fun a_failed_prepare_reports_on_the_main_thread_and_does_not_seek() {
        val seen = runFromWorker(ok = false, seekMs = 4_000)
        assertEquals(listOf("unavailable"), seen.map { it.first })
        assertTrue(seen.all { it.second === Thread.currentThread() }, "error state set on ${seen.map { it.second.name }}")
    }

    @Test fun without_a_start_position_nothing_seeks() {
        assertEquals(emptyList(), runFromWorker(ok = true, seekMs = 0).map { it.first })
    }
}
