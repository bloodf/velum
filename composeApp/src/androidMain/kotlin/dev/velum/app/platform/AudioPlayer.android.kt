package dev.velum.app.platform

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import java.io.File
import dev.velum.app.playback.SkipSilenceScanner
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayer()

private enum class PlaybackSpeed(val value: Float) {
    S075(0.75f),
    S100(1.0f),
    S125(1.25f),
    S150(1.5f),
    S200(2.0f),
    ;

    fun next(): PlaybackSpeed = when (this) {
        S075 -> S100
        S100 -> S125
        S125 -> S150
        S150 -> S200
        S200 -> S075
    }

}

private class AndroidAudioPlayer : AudioPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var pollJob: Job? = null
    /** The WAV being played, if it is one; its PCM is only ever read in windows, and only for skip-silence (#333). */
    @Volatile private var wavFile: File? = null
    @Volatile private var wavSource: WavPcmSource? = null
    @Volatile private var skipScan: Job? = null
    private var speed = PlaybackSpeed.S100

    private val _isPlaying = MutableStateFlow(false)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _playbackSpeed = MutableStateFlow(PlaybackSpeed.S100.value)
    private val _skipSilence = MutableStateFlow(false)

    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()
    override val skipSilence: StateFlow<Boolean> = _skipSilence.asStateFlow()

    override suspend fun prepare(path: String): Boolean = withContext(Dispatchers.IO) {
        // The player, the published state and the skip-silence fields are only touched on Main, in this order:
        //  1. first hop: stop the poll, reset the state and the skip-silence fields, take the old player;
        //  2. file work on IO (the old player is released, the new one is built and prepared);
        //  3. last hop: install the new player, its duration and its WAV in one step.
        // State is written on the main thread only: a collector's recomposition can run on the writing thread
        // (the Compose test dispatcher does), and a ripple or animator touched there fails off a Looper (#345).
        var old: MediaPlayer? = null
        try {
            onMain {
                stopPolling() // the old poll job must not stay scheduled behind the new player
                _isPlaying.value = false
                _positionMs.value = 0L
                _durationMs.value = 0L
                skipScan?.cancel() // a scan of the previous file must not seek this one
                skipScan = null
                wavFile = null
                wavSource = null
                old = player
                player = null
            }
        } finally {
            // Taken out of `player` on Main, so it is released here even if the call was cancelled right after the
            // hop: nothing else can reach it any more. These are plain calls, safe from a cancelled coroutine on IO.
            runCatching { old?.reset() }
            runCatching { old?.release() }
        }
        ensureActive()
        if (path.isBlank()) {
            return@withContext false
        }
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            return@withContext false
        }
        var created: MediaPlayer? = null
        try {
            val mp = MediaPlayer()
            created = mp
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                _isPlaying.value = false
                _positionMs.value = _durationMs.value
                stopPolling()
            }
            mp.setOnErrorListener { _, _, _ ->
                _isPlaying.value = false
                stopPolling()
                true
            }
            mp.prepare()
            applySpeed(mp, speed)
            val durationMs = mp.duration.coerceAtLeast(0).toLong()
            val isWav = path.endsWith(".wav", ignoreCase = true) || path.endsWith(".wave", ignoreCase = true)
            // One hop, and a cancelled call never reaches it: a stale prepare cannot replace a newer player.
            // No data is read for a WAV here: skip-silence opens it on demand.
            onMain {
                player = mp
                _durationMs.value = durationMs
                if (isWav) wavFile = file
            }
            true
        } catch (t: Throwable) {
            // Only the player this call made: `player` may already be a newer call's. Uninstall it first, then
            // release it, so nothing reads a released player. The hop returns by throwing when this call was
            // cancelled (its block has run by then), so the release sits in `finally`.
            try {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (created != null && player === created) {
                        player = null
                        stopPolling()
                        _isPlaying.value = false
                        _positionMs.value = 0L
                        _durationMs.value = 0L
                        wavFile = null
                        wavSource = null
                    }
                }
            } finally {
                runCatching { created?.release() }
            }
            if (t is CancellationException) throw t
            false
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    override fun play() {
        val mp = player ?: return
        try {
            applySpeed(mp, speed)
            mp.start()
            _isPlaying.value = true
            startPolling()
        } catch (_: Throwable) {
            _isPlaying.value = false
        }
    }

    override fun pause() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (_: Throwable) {
            // ignore
        }
        _isPlaying.value = false
        stopPolling()
        syncPosition()
    }

    override fun seekTo(positionMs: Long) {
        val mp = player ?: return
        val target = positionMs.coerceIn(0L, _durationMs.value.coerceAtLeast(0L)).toInt()
        try {
            mp.seekTo(target)
            _positionMs.value = target.toLong()
        } catch (_: Throwable) {
            // ignore
        }
    }

    override fun seekBy(deltaMs: Long) {
        seekTo(_positionMs.value + deltaMs)
    }

    override fun cycleSpeed() {
        speed = speed.next()
        _playbackSpeed.value = speed.value
        val mp = player ?: return
        applySpeed(mp, speed)
    }

    override fun setSkipSilence(enabled: Boolean) {
        _skipSilence.value = enabled
    }

    override fun release() {
        stopPolling()
        releasePlayerOnly()
        wavFile = null
        wavSource = null
        skipScan?.cancel()
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    private fun releasePlayerOnly() {
        val mp = player
        player = null
        runCatching { mp?.reset() }
        runCatching { mp?.release() }
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                syncPosition()
                maybeSkipSilence()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun syncPosition() {
        val mp = player ?: return
        try {
            _positionMs.value = mp.currentPosition.coerceAtLeast(0).toLong()
            if (!mp.isPlaying) {
                _isPlaying.value = false
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun maybeSkipSilence() {
        if (!_skipSilence.value) return
        if (!_isPlaying.value) return
        if (skipScan?.isActive == true) return
        val file = wavFile ?: return
        val pos = _positionMs.value
        skipScan = scope.launch {
            // Disk reads and the scan stay off the main thread; the seek comes back on it. Any failure (like the
            // old loader's catch of Throwable, OutOfMemoryError included) only means no skipping and must not
            // escape this scope; a cancellation is the one thing that is rethrown.
            val found = withContext(Dispatchers.IO) {
                try {
                    val source = wavSource?.takeIf { it.isFor(file) } ?: WavPcmSource.open(file)
                    source?.let { it to it.findSkipPastSilence(pos) { ensureActive() } }
                } catch (c: CancellationException) {
                    throw c // a cancelled scan must stop, not be swallowed as "nothing found"
                } catch (_: Throwable) {
                    null
                }
            } ?: return@launch
            val (source, skip) = found
            if (wavFile !== file) return@launch // another file was prepared meanwhile
            wavSource = source
            val target = skip?.seekToMs ?: return@launch
            if (SkipSilenceScanner.shouldApplySkip(pos, _positionMs.value, target, _isPlaying.value)) seekTo(target)
        }
    }

    private fun applySpeed(mp: MediaPlayer, speed: PlaybackSpeed) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val params = (mp.playbackParams ?: PlaybackParams()).setSpeed(speed.value)
            mp.playbackParams = params
        } catch (_: Throwable) {
            // Some devices reject params while idle; retry on play().
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 200L
    }
}
