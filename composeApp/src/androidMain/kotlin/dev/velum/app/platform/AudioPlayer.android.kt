package dev.velum.app.platform

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import java.io.File
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
    private var wavFile: File? = null
    private var wavSource: WavPcmSource? = null
    private var skipScan: Job? = null
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
        releasePlayerOnly()
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        wavFile = null
        wavSource = null
        if (path.isBlank()) {
            return@withContext false
        }
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            return@withContext false
        }
        try {
            val mp = MediaPlayer()
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
            _durationMs.value = mp.duration.coerceAtLeast(0).toLong()
            if (path.endsWith(".wav", ignoreCase = true) ||
                path.endsWith(".wave", ignoreCase = true)
            ) {
                wavFile = file // no data is read here: skip-silence opens it on demand
            }
            true
        } catch (_: Throwable) {
            releasePlayerOnly()
            false
        }
    }

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
            // Disk reads and the scan stay off the main thread; the seek comes back on it.
            val skip = withContext(Dispatchers.IO) {
                val source = wavSource ?: WavPcmSource.open(file)?.also { wavSource = it }
                source?.findSkipPastSilence(pos)
            } ?: return@launch
            if (skip.seekToMs > pos + 50L && _isPlaying.value) seekTo(skip.seekToMs)
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
