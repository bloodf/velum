package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import eu.buney.kopus.OpusDecoder as KopusDecoder

actual fun createOpusDecoder(): OpusDecoder = KopusOpusDecoder()

/**
 * Ogg demux (common) + kopus Opus decode on Android.
 * MediaCodec path can replace this later for device-optimized decode.
 */
class KopusOpusDecoder : OpusDecoder {
    override fun decodeToPcm(oggBytes: ByteArray): PcmAudio =
        decodeInMemory(oggBytes)

    override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta =
        decodeToFile(oggBytes, dest)

    companion object {
        internal fun decodeInMemory(oggBytes: ByteArray): PcmAudio {
            val stream = OggOpusDemuxer.demux(oggBytes)
            val rate = normalizeRate(stream.sampleRateHz)
            val channels = stream.channels.coerceIn(1, 2)
            val decoder = KopusDecoder(sampleRate = rate, channels = channels)
            try {
                val frameSize = rate / 50
                val out = ShortArray(frameSize * channels)
                var samples = ShortArray(frameSize * channels * 64)
                var count = 0
                for (packet in stream.packets) {
                    val n = decoder.decode(
                        inData = packet,
                        outPcm = out,
                        frameSize = frameSize,
                    )
                    if (n > 0) {
                        val need = n * channels
                        if (count + need > samples.size) {
                            samples = samples.copyOf(
                                (samples.size + need).coerceAtLeast(samples.size * 2),
                            )
                        }
                        for (i in 0 until need) {
                            samples[count++] = out[i]
                        }
                    }
                }
                return PcmAudio(
                    samples = samples.copyOf(count),
                    sampleRateHz = rate,
                    channels = channels,
                )
            } finally {
                decoder.close()
            }
        }

        internal fun decodeToFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta {
            val stream = OggOpusDemuxer.demux(oggBytes)
            val rate = normalizeRate(stream.sampleRateHz)
            val channels = stream.channels.coerceIn(1, 2)
            val decoder = KopusDecoder(sampleRate = rate, channels = channels)
            try {
                dest.writeBytes(ByteArray(0))
                val frameSize = rate / 50
                val out = ShortArray(frameSize * channels)
                val byteChunk = ByteArray(frameSize * channels * 2)
                var sizeBytes = 0L
                for (packet in stream.packets) {
                    val n = decoder.decode(
                        inData = packet,
                        outPcm = out,
                        frameSize = frameSize,
                    )
                    if (n <= 0) continue
                    val sampleCount = n * channels
                    var bi = 0
                    for (i in 0 until sampleCount) {
                        val s = out[i].toInt()
                        byteChunk[bi++] = (s and 0xff).toByte()
                        byteChunk[bi++] = ((s shr 8) and 0xff).toByte()
                    }
                    dest.appendBytes(byteChunk.copyOf(bi))
                    sizeBytes += bi.toLong()
                }
                return SpilledPcmMeta(
                    sampleRateHz = rate,
                    channels = channels,
                    sizeBytes = sizeBytes,
                )
            } finally {
                decoder.close()
            }
        }

        private fun normalizeRate(hz: Int): Int = when (hz) {
            8_000, 12_000, 16_000, 24_000, 48_000 -> hz
            else -> 48_000
        }
    }
}
