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

    private var releaseFirstPlayer = CountDownLatch(0)
    private var insideFirstPlayer = CountDownLatch(0)

    @After fun cleanup() {
        releaseFirstPlayer.countDown() // never leave a blocked IO thread behind
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

    private fun stateOf(mp: MediaPlayer): State = shadowOf(mp).state

    @Test fun prepare_publishes_every_state_change_on_the_main_thread() {
        val file = wav("player-thread")
        recordPlayers()
        val player = createAudioPlayer()
        val mainThread = Thread.currentThread()
        val writers = CopyOnWriteArrayList<Pair<String, Thread>>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // Unconfined: the collector resumes on whichever thread writes the value, like the Compose test dispatcher.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.durationMs.collect { writers += "duration" to Thread.currentThread() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.isPlaying.collect { writers += "playing" to Thread.currentThread() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.positionMs.collect { writers += "position" to Thread.currentThread() } }
        fun prepare() {
            val caller = thread { runBlocking { player.prepare(file.absolutePath) } }
            idleUntil("prepare") { !caller.isAlive }
        }
        prepare()
        player.play() // isPlaying becomes true; the second prepare must reset it on Main
        player.seekTo(700) // and the position
        prepare()
        scope.coroutineContext[Job]?.cancel()
        assertEquals(State.END, stateOf(players[0]), "the second prepare releases the first player")
        player.release()
        fun count(name: String) = writers.count { it.first == name }
        assertTrue(count("duration") >= 4, "expected initial, 1500, reset and 1500 again, saw ${count("duration")}")
        assertTrue(count("playing") >= 3, "expected initial, playing and reset, saw ${count("playing")}")
        assertTrue(count("position") >= 3, "expected initial, 700 and reset, saw ${count("position")}")
        assertTrue(writers.all { it.second === mainThread }, "state written off the main thread: ${writers.map { it.first to it.second.name }}")
    }

    /** Records every MediaPlayer built. */
    private fun recordPlayers() = ShadowMediaPlayer.setCreateListener { mp, _ -> players += mp }

    /** Blocks the first MediaPlayer built inside its constructor, i.e. while prepare is on its IO thread. */
    private fun blockFirstPlayer() {
        releaseFirstPlayer = CountDownLatch(1)
        insideFirstPlayer = CountDownLatch(1)
        val release = releaseFirstPlayer
        val inside = insideFirstPlayer
        ShadowMediaPlayer.setCreateListener { mp, _ ->
            players += mp
            if (players.size == 1) {
                inside.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun a_cancelled_prepare_releases_its_own_player_and_leaves_none_installed() {
        val file = wav("player-cancel")
        val player = createAudioPlayer()
        blockFirstPlayer()
        val job = launchPrepare(player, file)
        idleUntil("first hop and player creation") { insideFirstPlayer.count == 0L }
        job.cancel()
        releaseFirstPlayer.countDown()
        idleUntil("prepare to finish") { job.isCompleted }
        assertTrue(job.isCancelled)
        assertEquals(State.END, stateOf(players[0]), "the cancelled call must release the player it made")
        player.play() // nothing is installed: a harmless no-op
        assertTrue(stateOf(players[0]) == State.END)
        player.release()
    }

    @Test fun a_stale_prepare_cannot_replace_or_release_a_newer_players() {
        val file1 = wav("player-stale-1")
        val file2 = wav("player-stale-2", durationMs = 900)
        val player = createAudioPlayer()
        blockFirstPlayer()
        val job1 = launchPrepare(player, file1)
        idleUntil("P1 inside its player construction") { insideFirstPlayer.count == 0L }
        job1.cancel()
        val job2 = launchPrepare(player, file2)
        idleUntil("P2 ready") { job2.isCompleted }
        releaseFirstPlayer.countDown() // P1 now runs on: its setDataSource/prepare finish, then it meets its cancellation
        idleUntil("P1 finished") { job1.isCompleted }
        assertEquals(2, players.size)
        assertEquals(State.END, stateOf(players[0]), "P1 releases its own player")
        assertTrue(stateOf(players[1]) != State.END, "P1 must not release P2's player")
        player.play()
        assertEquals(State.STARTED, stateOf(players[1]), "P2 is the installed player and plays")
        player.release()
    }

    @Test fun a_stale_prepare_that_fails_does_not_release_a_newer_players() {
        val file1 = wav("player-fail-1")
        val file2 = wav("player-fail-2", durationMs = 900)
        ShadowMediaPlayer.addException(DataSource.toDataSource(file1.absolutePath), IOException("corrupt"))
        val player = createAudioPlayer()
        blockFirstPlayer()
        val job1 = launchPrepare(player, file1)
        idleUntil("P1 inside its player construction") { insideFirstPlayer.count == 0L }
        job1.cancel()
        val job2 = launchPrepare(player, file2)
        idleUntil("P2 ready") { job2.isCompleted }
        releaseFirstPlayer.countDown() // P1 now fails with an IOException while cancelled
        idleUntil("P1 finished") { job1.isCompleted }
        assertTrue(stateOf(players[1]) != State.END, "P1's failure must not release P2's player")
        player.play()
        assertEquals(State.STARTED, stateOf(players[1]))
        player.release()
    }

    @Test fun a_cancel_right_after_the_first_hop_still_releases_the_old_player() {
        val file = wav("player-leak")
        recordPlayers()
        val player = createAudioPlayer()
        val first = thread { runBlocking { player.prepare(file.absolutePath) } }
        idleUntil("first prepare") { !first.isAlive }
        val job2Ref = java.util.concurrent.atomic.AtomicReference<Job>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        // The reset to 0 runs inside P2's first hop; cancelling there is the cancel that lands right after it.
        collector.launch(start = CoroutineStart.UNDISPATCHED) { player.durationMs.collect { if (it == 0L) job2Ref.get()?.cancel() } }
        val job2 = launchPrepare(player, file)
        job2Ref.set(job2)
        idleUntil("P2 finished") { job2.isCompleted }
        collector.coroutineContext[Job]?.cancel()
        assertTrue(job2.isCancelled)
        assertEquals(State.END, stateOf(players[0]), "the player taken out by the first hop must be released")
        player.release()
    }

    @Test fun a_failed_prepare_leaves_zero_state_no_player_and_a_noop_play() {
        val good = wav("player-ok")
        val bad = wav("player-bad")
        ShadowMediaPlayer.addException(DataSource.toDataSource(bad.absolutePath), IOException("corrupt"))
        recordPlayers()
        val player = createAudioPlayer()
        var ok: Boolean? = null
        var failed: Boolean? = null
        val t1 = thread { ok = runBlocking { player.prepare(good.absolutePath) } }
        idleUntil("good prepare") { !t1.isAlive }
        val t2 = thread { failed = runBlocking { player.prepare(bad.absolutePath) } }
        idleUntil("bad prepare") { !t2.isAlive }
        assertEquals(true, ok)
        assertEquals(false, failed)
        assertEquals(State.END, stateOf(players[0]), "the second prepare releases the first player")
        assertEquals(State.END, stateOf(players[1]), "the failed prepare releases its own player")
        assertEquals(0L, player.durationMs.value)
        assertEquals(0L, player.positionMs.value)
        assertEquals(false, player.isPlaying.value)
        player.play()
        assertEquals(false, player.isPlaying.value, "nothing is installed, so play is a no-op")
        player.release()
    }

    @Test fun re_preparing_stops_the_poll_so_the_old_position_is_not_written_back() {
        val file = wav("player-poll")
        val player = createAudioPlayer()
        val t1 = thread { runBlocking { player.prepare(file.absolutePath) } }
        idleUntil("first prepare") { !t1.isAlive }
        player.play()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS) // the poll runs and reads a position
        val t2 = thread { runBlocking { player.prepare(file.absolutePath) } }
        idleUntil("second prepare") { !t2.isAlive }
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(0L, player.positionMs.value, "a poll tick of the old player must not move the position after the reset")
        // Nothing is playing now, so no poll tick may stay scheduled on the main looper.
        assertEquals(java.time.Duration.ZERO, shadowOf(Looper.getMainLooper()).nextScheduledTaskTime, "the old poll is still scheduled")
        player.release()
    }

    @Test fun a_cancel_before_the_first_hop_leaves_the_current_player_alone() {
        val file = wav("player-before-hop")
        recordPlayers()
        val player = createAudioPlayer()
        val t1 = thread { runBlocking { player.prepare(file.absolutePath) } }
        idleUntil("first prepare") { !t1.isAlive }
        // The main looper is not run, so P2's first hop is still pending when it is cancelled.
        val job2 = launchPrepare(player, file)
        job2.cancel()
        idleUntil("P2 finished") { job2.isCompleted }
        assertTrue(job2.isCancelled)
        assertEquals(1, players.size, "P2 never built a player")
        assertTrue(stateOf(players[0]) != State.END, "the current player must survive a cancel that came before any change")
        player.play()
        assertEquals(State.STARTED, stateOf(players[0]), "and it still plays")
        player.release()
    }

    @Test fun a_cancel_inside_the_last_hop_uninstalls_the_player_and_rethrows() {
        val file = wav("player-last-hop")
        recordPlayers()
        val player = createAudioPlayer()
        val jobRef = java.util.concurrent.atomic.AtomicReference<Job>()
        val thrown = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        // 1500 is published by the last hop, after the player is installed: cancel there.
        collector.launch(start = CoroutineStart.UNDISPATCHED) {
            player.durationMs.collect { if (it == 1500L) { player.play(); jobRef.get()?.cancel() } } // playing when the cancel lands
        }
        val job = CoroutineScope(Dispatchers.IO).launch {
            try { player.prepare(file.absolutePath) } catch (e: kotlinx.coroutines.CancellationException) { thrown.set(e); throw e }
        }.also { scopes += it }
        jobRef.set(job)
        idleUntil("prepare finished") { job.isCompleted }
        collector.coroutineContext[Job]?.cancel()
        assertTrue(thrown.get() != null, "the cancellation must reach the caller, not be turned into a result")
        assertEquals(State.END, stateOf(players[0]))
        assertEquals(0L, player.durationMs.value, "the half-installed duration is rolled back")
        assertEquals(false, player.isPlaying.value, "playing is rolled back with the install")
        assertEquals(0L, player.positionMs.value)
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals(java.time.Duration.ZERO, shadowOf(Looper.getMainLooper()).nextScheduledTaskTime, "the poll started by that play must be stopped")
        player.play()
        assertEquals(false, player.isPlaying.value, "the released player is no longer installed")
        player.release()
    }
}
