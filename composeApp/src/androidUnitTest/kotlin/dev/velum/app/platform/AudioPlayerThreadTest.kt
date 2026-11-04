package dev.velum.app.platform

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowMediaPlayer.MediaInfo
import org.robolectric.shadows.util.DataSource

/**
 * #345: `prepare` ran on an IO thread and wrote the playback StateFlows there. A collector resumed inline
 * (Compose's test dispatcher does) then recomposed on that thread, and a ripple cancel threw "Animators may only be
 * run on Looper threads". The state must be written on the main thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioPlayerThreadTest {
    @Test fun prepare_publishes_state_on_the_main_thread() {
        val file = File.createTempFile("player-thread", ".wav").apply { writeBytes(ByteArray(64)); deleteOnExit() }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), MediaInfo(1500, 0))
        val player = createAudioPlayer()
        val mainThread = Thread.currentThread()
        val writers = CopyOnWriteArrayList<Thread>()
        // Unconfined: the collector resumes on whichever thread writes the value, like the Compose test dispatcher.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.durationMs.collect { writers += Thread.currentThread() } }
        // Not on the test (main) thread: it has to stay free to run the looper that prepare hands state to.
        val caller = thread { runBlocking { player.prepare(file.absolutePath) } }
        val deadline = System.currentTimeMillis() + 20_000
        while (caller.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        assertTrue(!caller.isAlive, "prepare did not finish")
        assertTrue(writers.size >= 2, "expected the reset and the duration, saw ${writers.size}")
        assertTrue(writers.all { it === mainThread }, "state written off the main thread: ${writers.map { it.name }}")
    }

    @Test fun a_cancelled_prepare_is_cancelled_not_reported_as_a_failed_prepare() {
        val file = File.createTempFile("player-cancel", ".wav").apply { writeBytes(ByteArray(64)); deleteOnExit() }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), MediaInfo(1500, 0))
        val player = createAudioPlayer()
        var result: Boolean? = null
        // The main looper is not run while the job starts, so its first hop to Main is still pending when it is cancelled.
        val job = CoroutineScope(Dispatchers.IO).launch { result = player.prepare(file.absolutePath) }
        Thread.sleep(300)
        job.cancel()
        val deadline = System.currentTimeMillis() + 20_000
        while (job.isActive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(job.isCancelled, "the cancel must reach the caller")
        assertTrue(result == null, "a cancelled prepare must not return a value, got $result")
        player.play() // no player was left behind: a harmless no-op
    }
}
