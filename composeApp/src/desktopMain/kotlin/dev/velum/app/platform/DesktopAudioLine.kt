package dev.velum.app.platform

import java.io.Closeable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/**
 * Hardware audio input seam for desktop platforms.
 * Isolates [javax.sound.sampled.TargetDataLine] so synthetic tests never touch host audio hardware.
 */
internal interface DesktopAudioLine : Closeable {
    val sampleRateHz: Int
    val channels: Int
    val bufferSizeBytes: Int
    fun open()
    fun start()
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stop()
    override fun close()
}

internal fun interface DesktopAudioLineFactory {
    @Throws(Exception::class)
    fun createLine(inputSource: MicInputSource): DesktopAudioLine
}

internal object JavaSoundAudioLineFactory : DesktopAudioLineFactory {
    override fun createLine(inputSource: MicInputSource): DesktopAudioLine {
        // Velum canonical recording format: 16,000 Hz, 16-bit signed little-endian mono
        val targetFormat = AudioFormat(16_000f, 16, 1, true, false)
        val targetInfo = DataLine.Info(TargetDataLine::class.java, targetFormat)

        val line: TargetDataLine = if (AudioSystem.isLineSupported(targetInfo)) {
            val candidate = AudioSystem.getLine(targetInfo) as TargetDataLine
            try {
                val bufferSize = (16_000 * 2 / 5).coerceAtLeast(4096) // ~200ms
                candidate.open(targetFormat, bufferSize)
                candidate
            } catch (unavailable: LineUnavailableException) {
                openFallbackLine() ?: throw unavailable
            }
        } else {
            openFallbackLine() ?: throw LineUnavailableException("No supported audio capture line found")
        }

        return JavaSoundDesktopAudioLine(line)
    }

    private fun openFallbackLine(): TargetDataLine? {
        val genericInfo = DataLine.Info(TargetDataLine::class.java, null as AudioFormat?)
        if (!AudioSystem.isLineSupported(genericInfo)) return null
        val line = AudioSystem.getLine(genericInfo) as TargetDataLine

        // Try line's native format first
        try {
            line.open()
            return line
        } catch (_: Exception) {
            // Try standard hardware rates in priority order
            val fallbackFormats = listOf(
                AudioFormat(48_000f, 16, 2, true, false),
                AudioFormat(44_100f, 16, 2, true, false),
                AudioFormat(48_000f, 16, 1, true, false),
                AudioFormat(44_100f, 16, 1, true, false),
            )
            for (fmt in fallbackFormats) {
                try {
                    val bufSize = (fmt.sampleRate.toInt() * fmt.channels * 2 / 5).coerceAtLeast(4096)
                    line.open(fmt, bufSize)
                    return line
                } catch (_: Exception) {
                }
            }
        }
        return null
    }
}

internal class JavaSoundDesktopAudioLine(
    private val line: TargetDataLine,
) : DesktopAudioLine {
    override val sampleRateHz: Int = line.format.sampleRate.toInt().coerceAtLeast(8000)
    override val channels: Int = line.format.channels.coerceAtLeast(1)
    override val bufferSizeBytes: Int = line.bufferSize.takeIf { it > 0 }
        ?: (sampleRateHz * channels * 2 / 5).coerceAtLeast(4096)

    override fun open() {
        if (!line.isOpen) {
            line.open()
        }
    }

    override fun start() {
        line.start()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return line.read(buffer, offset, length)
    }

    override fun stop() {
        if (line.isOpen) {
            runCatching { line.stop() }
        }
    }

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }
}
