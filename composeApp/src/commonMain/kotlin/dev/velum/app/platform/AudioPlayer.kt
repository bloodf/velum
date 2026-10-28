package dev.velum.app.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Local audio playback with speed control and optional silence skipping.
 * Android uses [android.media.MediaPlayer]; desktop/iOS return a no-op stub.
 */
interface AudioPlayer {
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val playbackSpeed: StateFlow<Float>
    val skipSilence: StateFlow<Boolean>

    /** Prepare [path] for playback. Returns false when the file cannot be opened. */
    suspend fun prepare(path: String): Boolean

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)

    /** Cycle through 0.75× → 1.0× → 1.25× → 1.5× → 2.0× → 0.75×. */
    fun cycleSpeed()

    fun setSkipSilence(enabled: Boolean)
    fun release()
}

expect fun createAudioPlayer(): AudioPlayer
