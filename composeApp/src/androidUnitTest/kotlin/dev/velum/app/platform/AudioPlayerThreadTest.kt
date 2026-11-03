package dev.velum.app.platform

import android.media.MediaPlayer
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowMediaPlayer.MediaInfo
import org.robolectric.shadows.ShadowMediaPlayer.State
import org.robolectric.shadows.util.DataSource

/**
 * #345: `prepare` ran on an IO thread and wrote the playback StateFlows there. A collector resumed inline
 * (Compose's test dispatcher does) then recomposed on that thread, and a ripple cancel threw "Animators may only be
 * run on Looper threads". The state must be written on the main thread, and a stale or cancelled `prepare` must
 * never replace, or release, a newer call's player.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioPlayerThreadTest {
    private val players = CopyOnWriteArrayList<MediaPlayer>()
    private val scopes = mutableListOf<Job>()

    @After fun cleanup() {
        ShadowMediaPlayer.setCreateListener(null)
        scopes.forEach { it.cancel() }
    }

    private fun wav(name: String, durationMs: Int = 1500): File {
        val file = File.createTempFile(name, ".wav").apply { writeBytes(ByteArray(64)); deleteOnExit() }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), MediaInfo(durationMs, 0))
        return file
    }

    private fun idleUntil(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(cond(), "timed out waiting for $what")
    }

    private fun launchPrepare(player: AudioPlayer, file: File): Job =
        CoroutineScope(Dispatchers.IO).launch { player.prepare(file.absolutePath) }.also { scopes += it }

    private fun State(mp: MediaPlayer): State = shadowOf(mp).state

    @Test fun prepare_publishes_every_state_change_on_the_main_thread() {
        val file = wav("player-thread")
        val player = createAudioPlayer()
        val mainThread = Thread.currentThread()
        val writers = CopyOnWriteArrayList<Pair<String, Thread>>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // Unconfined: the collector resumes on whichever thread writes the value, like the Compose test dispatcher.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.durationMs.collect { writers += "duration" to Thread.currentThread() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.isPlaying.collect { writers += "playing" to Thread.currentThread() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.positionMs.collect { writers += "position" to Thread.currentThread() } }
        repeat(2) { // the second call resets 1500 to 0, a real change, so the reset hop is the one that is tested
            val caller = thread { runBlocking { player.prepare(file.absolutePath) } }
            idleUntil("prepare") { !caller.isAlive }
        }
        scope.coroutineContext[Job]?.cancel()
        player.release()
        val durations = writers.count { it.first == "duration" }
        assertTrue(durations >= 4, "expected initial, 1500, reset and 1500 again, saw $durations")
        assertTrue(writers.all { it.second === mainThread }, "state written off the main thread: ${writers.map { it.first to it.second.name }}")
    }

    /** Blocks the first MediaPlayer built inside its constructor, i.e. while prepare is on its IO thread. */
    private fun blockFirstPlayer(): CountDownLatch {
        val release = CountDownLatch(1)
        val inside = CountDownLatch(1)
        ShadowMediaPlayer.setCreateListener { mp, _ ->
            players += mp
            if (players.size == 1) {
                inside.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
            }
        }
        blocked = inside
        return release
    }
    private var blocked = CountDownLatch(0)

    @Test fun a_cancelled_prepare_releases_its_own_player_and_leaves_none_installed() {
        val file = wav("player-cancel")
        val player = createAudioPlayer()
        val release = blockFirstPlayer()
        val job = launchPrepare(player, file)
        idleUntil("first hop and player creation") { blocked.count == 0L }
        job.cancel()
        release.countDown()
        idleUntil("prepare to finish") { job.isCompleted }
        assertTrue(job.isCancelled)
        assertEquals(State.END, State(players[0]), "the cancelled call must release the player it made")
        player.play() // nothing is installed: a harmless no-op
        assertTrue(State(players[0]) == State.END)
        player.release()
    }

    @Test fun a_stale_prepare_cannot_replace_or_release_a_newer_players() {
        val file1 = wav("player-stale-1")
        val file2 = wav("player-stale-2", durationMs = 900)
        val player = createAudioPlayer()
        val release = blockFirstPlayer()
        val job1 = launchPrepare(player, file1)
        idleUntil("P1 inside its player construction") { blocked.count == 0L }
        job1.cancel()
        val job2 = launchPrepare(player, file2)
        idleUntil("P2 ready") { job2.isCompleted }
        release.countDown() // P1 now runs on: its setDataSource/prepare finish, then it meets its cancellation
        idleUntil("P1 finished") { job1.isCompleted }
        assertEquals(2, players.size)
        assertEquals(State.END, State(players[0]), "P1 releases its own player")
        assertTrue(State(players[1]) != State.END, "P1 must not release P2's player")
        player.play()
        assertEquals(State.STARTED, State(players[1]), "P2 is the installed player and plays")
        player.release()
    }

    @Test fun a_stale_prepare_that_fails_does_not_release_a_newer_players() {
        val file1 = wav("player-fail-1")
        val file2 = wav("player-fail-2", durationMs = 900)
        ShadowMediaPlayer.addException(DataSource.toDataSource(file1.absolutePath), IOException("corrupt"))
        val player = createAudioPlayer()
        val release = blockFirstPlayer()
        val job1 = launchPrepare(player, file1)
        idleUntil("P1 inside its player construction") { blocked.count == 0L }
        job1.cancel()
        val job2 = launchPrepare(player, file2)
        idleUntil("P2 ready") { job2.isCompleted }
        release.countDown() // P1 now fails with an IOException while cancelled
        idleUntil("P1 finished") { job1.isCompleted }
        assertTrue(State(players[1]) != State.END, "P1's failure must not release P2's player")
        player.play()
        assertEquals(State.STARTED, State(players[1]))
        player.release()
    }
}
