@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

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
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSURL

actual fun createAudioPlayer(): AudioPlayer = IosAudioPlayer()

// UI owns this player on Main, as it does on Android. Polling avoids a retained native delegate.
private class IosAudioPlayer : AudioPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var player: AVAudioPlayer? = null
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val _isPlaying = MutableStateFlow(false)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _playbackSpeed = MutableStateFlow(1.0f)
    private val _skipSilence = MutableStateFlow(false)

    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()
    override val skipSilence: StateFlow<Boolean> = _skipSilence.asStateFlow()

    override suspend fun prepare(path: String): Boolean {
        release()
        if (path.isBlank()) return false
        return try {
            val candidate = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null)
            candidate.enableRate = true
            candidate.rate = _playbackSpeed.value
            // Opening validates the file. play() prepares the output and reports whether it starts.
            player = candidate
            _durationMs.value = (candidate.duration * 1000).toLong().coerceAtLeast(0L)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun play() {
        val current = player ?: return
        if (_positionMs.value >= _durationMs.value) current.currentTime = 0.0
        current.rate = _playbackSpeed.value
        // The recording owner controls the shared PlayAndRecord session. Do not change or deactivate it.
        _isPlaying.value = current.play()
        syncPosition()
        pollJob?.cancel()
        pollJob = null
        if (!_isPlaying.value) return
        pollJob = scope.launch {
            while (isActive) {
                syncPosition()
                if (!_isPlaying.value) break
                delay(200L)
            }
        }
    }

    override fun pause() {
        player?.pause()
        pollJob?.cancel()
        pollJob = null
        syncPosition()
        _isPlaying.value = false
    }

    override fun seekTo(positionMs: Long) {
        val current = player ?: return
        val target = positionMs.coerceIn(0L, _durationMs.value)
        current.currentTime = target / 1000.0
        _positionMs.value = target
    }

    override fun seekBy(deltaMs: Long) {
        val position = _positionMs.value
        val target = if (deltaMs > 0 && position > Long.MAX_VALUE - deltaMs) Long.MAX_VALUE
            else position + deltaMs
        seekTo(target)
    }

    override fun cycleSpeed() {
        val next = (speeds.indexOf(_playbackSpeed.value) + 1) % speeds.size
        _playbackSpeed.value = speeds[next]
        player?.rate = speeds[next]
    }

    override fun setSkipSilence(enabled: Boolean) {
        // shortcut: iOS has no PCM silence scanner yet; this preference does not change native playback.
        _skipSilence.value = enabled
    }

    override fun release() {
        pollJob?.cancel()
        pollJob = null
        player?.stop()
        player = null
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    private fun syncPosition() {
        val current = player ?: return
        _positionMs.value = (current.currentTime * 1000).toLong().coerceIn(0L, _durationMs.value)
        _isPlaying.value = current.playing
    }
}
