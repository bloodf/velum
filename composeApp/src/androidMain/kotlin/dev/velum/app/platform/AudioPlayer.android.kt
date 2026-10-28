package dev.velum.app.platform

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import dev.velum.app.playback.SkipSilenceScanner
import java.io.File
import java.io.RandomAccessFile
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
    private var pcm16LeMono: ByteArray? = null
    private var pcmSampleRateHz: Int = 16_000
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
        pcm16LeMono = null
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.isFile || !file.canRead()) return false
        return try {
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
                loadWavPcm(file)
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
        pcm16LeMono = null
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
        val pcm = pcm16LeMono ?: return
        val pos = _positionMs.value
        val skip = SkipSilenceScanner.findSkipPastSilence(
            pcm16LeMono = pcm,
            sampleRateHz = pcmSampleRateHz,
            fromMs = pos,
        ) ?: return
        if (skip.seekToMs > pos + 50L) {
            seekTo(skip.seekToMs)
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

    private fun loadWavPcm(file: File) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44L) return
                val header = ByteArray(12)
                raf.readFully(header)
                if (header[0] != 'R'.code.toByte() ||
                    header[1] != 'I'.code.toByte() ||
                    header[2] != 'F'.code.toByte() ||
                    header[3] != 'F'.code.toByte()
                ) {
                    return
                }
                var sampleRate = 16_000
                var channels = 1
                var bitsPerSample = 16
                var dataOffset = -1
                var dataSize = 0
                // Walk chunks after "WAVE"
                raf.seek(12)
                while (raf.filePointer + 8 <= raf.length()) {
                    val id = ByteArray(4)
                    raf.readFully(id)
                    val size = readIntLe(raf)
                    val chunk = id.decodeToString()
                    when (chunk) {
                        "fmt " -> {
                            if (size < 16) return
                            val audioFormat = readShortLe(raf).toInt() and 0xFFFF
                            channels = readShortLe(raf).toInt() and 0xFFFF
                            sampleRate = readIntLe(raf)
                            raf.skipBytes(6) // byteRate + blockAlign
                            bitsPerSample = readShortLe(raf).toInt() and 0xFFFF
                            val remaining = size - 16
                            if (remaining > 0) raf.skipBytes(remaining)
                            if (audioFormat != 1 || bitsPerSample != 16) {
                                pcm16LeMono = null
                                return
                            }
                        }
                        "data" -> {
                            dataOffset = raf.filePointer.toInt()
                            dataSize = size.coerceAtLeast(0)
                            break
                        }
                        else -> {
                            if (size > 0) raf.skipBytes(size)
                        }
                    }
                }
                if (dataOffset < 0 || dataSize <= 0) return
                if (channels != 1) {
                    // Downmix stereo → mono average for silence energy.
                    raf.seek(dataOffset.toLong())
                    val raw = ByteArray(dataSize.coerceAtMost((raf.length() - dataOffset).toInt()))
                    raf.readFully(raw)
                    pcmSampleRateHz = sampleRate.coerceAtLeast(1)
                    pcm16LeMono = downmixToMono(raw, channels)
                    return
                }
                raf.seek(dataOffset.toLong())
                val pcm = ByteArray(dataSize.coerceAtMost((raf.length() - dataOffset).toInt()))
                raf.readFully(pcm)
                pcmSampleRateHz = sampleRate.coerceAtLeast(1)
                pcm16LeMono = pcm
            }
        } catch (_: Throwable) {
            pcm16LeMono = null
        }
    }

    private fun downmixToMono(interleaved: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return interleaved
        val frameBytes = channels * 2
        val frames = interleaved.size / frameBytes
        val out = ByteArray(frames * 2)
        var inPos = 0
        var outPos = 0
        repeat(frames) {
            var sum = 0
            repeat(channels) {
                val lo = interleaved[inPos].toInt() and 0xFF
                val hi = interleaved[inPos + 1].toInt()
                sum += ((hi shl 8) or lo).toShort().toInt()
                inPos += 2
            }
            val avg = (sum / channels).toShort().toInt()
            out[outPos] = (avg and 0xFF).toByte()
            out[outPos + 1] = ((avg shr 8) and 0xFF).toByte()
            outPos += 2
        }
        return out
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return 0
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readShortLe(raf: RandomAccessFile): Short {
        val b0 = raf.read()
        val b1 = raf.read()
        if (b0 < 0 || b1 < 0) return 0
        return (b0 or (b1 shl 8)).toShort()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 200L
    }
}
