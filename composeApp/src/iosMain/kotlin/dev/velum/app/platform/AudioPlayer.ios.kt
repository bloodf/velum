package dev.velum.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun createAudioPlayer(): AudioPlayer = StubAudioPlayer()

private class StubAudioPlayer : AudioPlayer {
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

    override fun prepare(path: String): Boolean = false
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(deltaMs: Long) = Unit
    override fun cycleSpeed() = Unit
    override fun setSkipSilence(enabled: Boolean) {
        _skipSilence.value = enabled
    }
    override fun release() = Unit
}
