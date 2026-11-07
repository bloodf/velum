package dev.velum.app.platform

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

actual fun createAudioPlayer(): AudioPlayer = DesktopAudioPlayer()

internal interface DesktopAudioOutput : AutoCloseable {
    val playedFrames: Long
    fun write(bytes: ByteArray, length: Int): Int
    fun drain()
}

private fun openOutput(format: AudioFormat): DesktopAudioOutput {
    val line = AudioSystem.getSourceDataLine(format)
    try {
        line.open(format, 4096 * format.frameSize)
        line.start()
    } catch (error: Exception) {
        line.close()
        throw error
    }
    return object : DesktopAudioOutput {
        override val playedFrames get() = line.longFramePosition
        override fun write(bytes: ByteArray, length: Int) = line.write(bytes, 0, length)
        override fun drain() = line.drain()
        override fun close() {
            line.stop()
            line.flush()
            line.close()
        }
    }
}

internal class DesktopAudioPlayer(
    private val outputFactory: (AudioFormat) -> DesktopAudioOutput = ::openOutput,
) : AudioPlayer {
    private val lock = Any()
    private var generation = 0L
    private var file: File? = null
    private var format: AudioFormat? = null
    private var frames = 0L
    private var position = 0L
    private var session: Session? = null
    private val playing = MutableStateFlow(false)
    private val elapsed = MutableStateFlow(0L)
    private val duration = MutableStateFlow(0L)
    override val isPlaying = playing.asStateFlow()
    override val positionMs = elapsed.asStateFlow()
    override val durationMs = duration.asStateFlow()
    override val playbackSpeed = MutableStateFlow(1f).asStateFlow()
    override val skipSilence = MutableStateFlow(false).asStateFlow()
    override val supportsPlaybackSpeed = false
    override val supportsSkipSilence = false

    private class Session(val start: Long) {
        @Volatile var cancelled = false
        private var output: DesktopAudioOutput? = null
        @Synchronized fun attach(value: DesktopAudioOutput): Boolean {
            if (cancelled) { value.close(); return false }
            output = value
            return true
        }
        @Synchronized fun played() = output?.playedFrames ?: 0L
        @Synchronized fun close() {
            cancelled = true
            val owned = output
            output = null
            runCatching { owned?.close() }
        }
    }

    override suspend fun prepare(path: String): Boolean {
        val ticket = synchronized(lock) {
            reset()
            generation
        }
        val metadata = withContext(Dispatchers.IO) {
            runCatching {
                val source = File(path)
                AudioSystem.getAudioInputStream(source).use { stream ->
                    val audio = stream.format
                    require(audio.encoding == AudioFormat.Encoding.PCM_SIGNED || audio.encoding == AudioFormat.Encoding.PCM_UNSIGNED)
                    require(audio.frameRate.isFinite() && audio.frameRate > 0 && stream.frameLength > 0)
                    require(audio.channels in 1..8 && audio.sampleSizeInBits in listOf(8, 16, 24, 32))
                    require(audio.frameSize == audio.channels * (audio.sampleSizeInBits / 8))
                    require(stream.frameLength <= source.length() / audio.frameSize)
                    Triple(source, audio, stream.frameLength)
                }
            }.getOrNull()
        }
        return synchronized(lock) {
            if (ticket != generation || metadata == null) false else {
                file = metadata.first
                format = metadata.second
                frames = metadata.third
                duration.value = millis(frames)
                true
            }
        }
    }

    private fun millis(value: Long) = (value * 1000.0 / (format?.frameRate ?: 1f)).toLong()
    private fun updatePosition(value: Long) {
        position = value.coerceIn(0, frames)
        elapsed.value = millis(position)
    }

    override fun play() = synchronized(lock) {
        val source = file ?: return@synchronized
        val audio = format ?: return@synchronized
        if (session != null) return@synchronized
        if (position >= frames) updatePosition(0)
        val current = Session(position)
        session = current
        playing.value = true
        Thread({ stream(source, audio, current) }, "velum-audio").apply { isDaemon = true; start() }
    }

    private fun stream(source: File, audio: AudioFormat, current: Session) {
        var completed = false
        try {
            AudioSystem.getAudioInputStream(source).use { input ->
                var remaining = current.start * audio.frameSize
                val buffer = ByteArray(1024 * audio.frameSize)
                while (remaining > 0 && !current.cancelled) {
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    check(count > 0) { "Audio ended before seek position" }
                    remaining -= count
                }
                if (current.cancelled) return
                val output = outputFactory(audio)
                if (!current.attach(output)) return
                while (!current.cancelled) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        output.drain()
                        completed = true
                        break
                    }
                    var written = 0
                    while (written < count && !current.cancelled) {
                        val chunk = if (written == 0) buffer else buffer.copyOfRange(written, count)
                        val n = output.write(chunk, count - written)
                        check(n > 0 && n <= count - written) { "Audio output made no progress" }
                        written += n
                    }
                    synchronized(lock) {
                        if (session === current) updatePosition(current.start + current.played())
                    }
                }
            }
        } catch (_: Exception) {
            // Output can disappear or be closed by pause/replacement. Keep the player reusable.
        } finally {
            synchronized(lock) {
                if (session === current) {
                    updatePosition(if (completed) frames else current.start + current.played())
                    session = null
                    playing.value = false
                }
            }
            current.close()
        }
    }

    override fun pause() = synchronized(lock) { stop() }
    private fun stop() {
        val old = session
        session = null
        if (old != null) {
            updatePosition(old.start + old.played())
            old.close()
        }
        playing.value = false
    }
    override fun seekTo(positionMs: Long) = synchronized(lock) {
        val resume = playing.value
        stop()
        updatePosition((positionMs.coerceAtLeast(0) * ((format?.frameRate ?: 0f) / 1000.0)).toLong())
        if (resume) play()
    }
    override fun seekBy(deltaMs: Long) = synchronized(lock) { seekTo(elapsed.value + deltaMs) }
    override fun cycleSpeed() = Unit
    override fun setSkipSilence(enabled: Boolean) = Unit
    private fun reset() {
        generation++
        stop()
        file = null
        format = null
        frames = 0
        position = 0
        elapsed.value = 0
        duration.value = 0
    }
    override fun release() = synchronized(lock) { reset() }
}
