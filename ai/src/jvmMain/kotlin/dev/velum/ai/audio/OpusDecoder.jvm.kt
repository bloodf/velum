package dev.velum.ai.audio

import eu.buney.kopus.OpusDecoder as KopusDecoder

actual fun createOpusDecoder(): OpusDecoder = KopusOpusDecoder()

/**
 * Ogg demux (common) + kopus native Opus decode on JVM.
 */
class KopusOpusDecoder : OpusDecoder {
    override fun decodeToPcm(oggBytes: ByteArray): PcmAudio {
        val stream = OggOpusDemuxer.demux(oggBytes)
        val rate = when (stream.sampleRateHz) {
            8_000, 12_000, 16_000, 24_000, 48_000 -> stream.sampleRateHz
            else -> 48_000
        }
        val channels = stream.channels.coerceIn(1, 2)
        val decoder = KopusDecoder(sampleRate = rate, channels = channels)
        try {
            val pcm = ArrayList<Short>()
            val frameSize = rate / 50 // 20 ms
            val out = ShortArray(frameSize * channels)
            for (packet in stream.packets) {
                val samples = decoder.decode(
                    inData = packet,
                    outPcm = out,
                    frameSize = frameSize,
                )
                if (samples > 0) {
                    for (i in 0 until samples * channels) {
                        pcm.add(out[i])
                    }
                }
            }
            return PcmAudio(
                samples = pcm.toShortArray(),
                sampleRateHz = rate,
                channels = channels,
            )
        } finally {
            decoder.close()
        }
    }
}
