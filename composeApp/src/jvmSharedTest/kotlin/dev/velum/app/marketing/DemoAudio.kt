package dev.velum.app.marketing

import java.io.File
import java.io.RandomAccessFile

/**
 * A quiet mono WAV of [seconds] for marketing captures, so the player shows a working transport. 8-bit at 1 kHz keeps
 * a 42-minute file near 2.5 MB; it is written to a temporary folder and never committed.
 */
object DemoAudio {
    private const val RATE = 1_000

    fun write(dir: File, seconds: Long, name: String = "demo.wav"): File {
        dir.mkdirs()
        val file = File(dir, name)
        val samples = seconds * RATE
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            fun le32(v: Long) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())
            fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
            out.write("RIFF".toByteArray()); out.write(le32(36 + samples))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
            out.write(le32(RATE.toLong())); out.write(le32(RATE.toLong())); out.write(le16(1)); out.write(le16(8))
            out.write("data".toByteArray()); out.write(le32(samples))
            // 8-bit PCM is unsigned: 128 is silence.
            val chunk = ByteArray(64 * 1024) { 128.toByte() }
            var left = samples
            while (left > 0) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                out.write(chunk, 0, n)
                left -= n
            }
        }
        return file
    }
}
