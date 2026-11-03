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
        // State is written on the main thread only: a collector's recomposition can run on the writing thread
        // (the Compose test dispatcher does), and a ripple or animator touched there fails off a Looper (#345).
        // The skip-silence fields belong to Main (the poll runs there), so they are reset there too.
        onMain {
            _isPlaying.value = false
            _positionMs.value = 0L
            _durationMs.value = 0L
            skipScan?.cancel() // a scan of the previous file must not seek this one
            skipScan = null
            wavFile = null
            wavSource = null
        }
        releasePlayerOnly()
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
            player = mp
            val durationMs = mp.duration.coerceAtLeast(0).toLong()
            onMain { _durationMs.value = durationMs }
            if (path.endsWith(".wav", ignoreCase = true) ||
                path.endsWith(".wave", ignoreCase = true)
            ) {
                // no data is read here: skip-silence opens it on demand
                withContext(Dispatchers.Main.immediate) { wavFile = file }
            }
            true
        } catch (e: CancellationException) {
            // A cancel at a Main hop: release only the player this call made, then let the cancel through.
            runCatching { created?.release() }
            if (player === created) player = null
            throw e
        } catch (_: Throwable) {
            releasePlayerOnly()
            false
        }
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }

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
        try {
            player?.reset()
            player?.release()
        } catch (_: Throwable) {
            // ignore
        }
        player = null
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
