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

    private fun isValidPcm16Le(format: AudioFormat): Boolean {
        return format.encoding == AudioFormat.Encoding.PCM_SIGNED &&
            format.sampleSizeInBits == 16 &&
            !format.isBigEndian &&
            format.channels in 1..2 &&
            format.sampleRate >= 8000f
    }

    override fun createLine(inputSource: MicInputSource): DesktopAudioLine {
        // Explicitly validated 16-bit signed little-endian PCM formats only
        val candidateFormats = listOf(
            AudioFormat(16_000f, 16, 1, true, false),
            AudioFormat(48_000f, 16, 2, true, false),
            AudioFormat(44_100f, 16, 2, true, false),
            AudioFormat(48_000f, 16, 1, true, false),
            AudioFormat(44_100f, 16, 1, true, false),
            AudioFormat(32_000f, 16, 1, true, false),
            AudioFormat(24_000f, 16, 1, true, false),
            AudioFormat(16_000f, 16, 2, true, false),
            AudioFormat(8_000f, 16, 1, true, false),
        )

        var lastError: Exception? = null

        // 1. Try candidates on explicit format Info
        for (fmt in candidateFormats) {
            val info = DataLine.Info(TargetDataLine::class.java, fmt)
            if (!AudioSystem.isLineSupported(info)) continue

            var candidate: TargetDataLine? = null
            try {
                candidate = AudioSystem.getLine(info) as TargetDataLine
                val bufferSize = (fmt.sampleRate.toInt() * fmt.channels * 2 / 5).coerceAtLeast(4096)
                candidate.open(fmt, bufferSize)
                if (isValidPcm16Le(candidate.format)) {
                    return JavaSoundDesktopAudioLine(candidate)
                }
                // Close immediately if opened format is unexpected
                candidate.close()
            } catch (e: Exception) {
                lastError = e
                runCatching { candidate?.close() }
            }
        }

        // 2. Try candidates on generic TargetDataLine if supported
        val genericInfo = DataLine.Info(TargetDataLine::class.java, null as AudioFormat?)
        if (AudioSystem.isLineSupported(genericInfo)) {
            var genericLine: TargetDataLine? = null
            try {
                genericLine = AudioSystem.getLine(genericInfo) as TargetDataLine
                for (fmt in candidateFormats) {
                    try {
                        val bufferSize = (fmt.sampleRate.toInt() * fmt.channels * 2 / 5).coerceAtLeast(4096)
                        genericLine.open(fmt, bufferSize)
                        if (isValidPcm16Le(genericLine.format)) {
                            return JavaSoundDesktopAudioLine(genericLine)
                        }
                        genericLine.close()
                    } catch (_: Exception) {
                        runCatching { genericLine.close() }
                    }
                }
            } catch (e: Exception) {
                lastError = e
                runCatching { genericLine?.close() }
            }
        }

        throw lastError ?: LineUnavailableException("No supported 16-bit signed little-endian audio capture line found")
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
