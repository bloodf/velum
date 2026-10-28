package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import eu.buney.kopus.OpusDecoder as KopusDecoder
import java.io.RandomAccessFile

actual fun createOpusDecoder(): OpusDecoder = KopusOpusDecoder()

/**
 * Ogg demux (common) + kopus native Opus decode on JVM.
 */
class KopusOpusDecoder : OpusDecoder {
    override fun decodeToPcm(oggBytes: ByteArray): PcmAudio =
        decodeInMemory(oggBytes)

    override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta =
        decodeStreaming(dest) { onHeader, onPacket ->
            OggOpusDemuxer.demuxForEach(oggBytes, onHeader, onPacket)
        }

    override fun decodePathToPcmFile(path: String, dest: ModelStoreDir): SpilledPcmMeta =
        RandomAccessFile(path, "r").use { raf ->
            val size = raf.length()
            decodeStreaming(dest) { onHeader, onPacket ->
                OggOpusDemuxer.demuxAtForEach(
                    size = size,
                    readAt = { buf, position, length ->
                        raf.seek(position)
                        raf.read(buf, 0, length)
                    },
                    onHeader = onHeader,
                    onPacket = onPacket,
                )
            }
        }

    companion object {
        internal fun decodeInMemory(oggBytes: ByteArray): PcmAudio {
            var decoder: KopusDecoder? = null
            var rate = 48_000
            var channels = 1
            var frameSize = rate / 50
            var out = ShortArray(0)
            var samples = ShortArray(0)
            var count = 0
            try {
                OggOpusDemuxer.demuxForEach(
                    oggBytes = oggBytes,
                    onHeader = { sampleRateHz, ch ->
                        rate = normalizeRate(sampleRateHz)
                        channels = ch.coerceIn(1, 2)
                        frameSize = rate / 50
                        out = ShortArray(frameSize * channels)
                        samples = ShortArray(frameSize * channels * 64)
                        decoder = KopusDecoder(sampleRate = rate, channels = channels)
                    },
                    onPacket = { packet ->
                        val n = decoder!!.decode(
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
                    },
                )
                return PcmAudio(
                    samples = samples.copyOf(count),
                    sampleRateHz = rate,
                    channels = channels,
                )
            } finally {
                decoder?.close()
            }
        }

        private fun decodeStreaming(
            dest: ModelStoreDir,
            demux: (
                onHeader: (Int, Int) -> Unit,
                onPacket: (ByteArray) -> Unit,
            ) -> OpusStreamMeta,
        ): SpilledPcmMeta {
            dest.writeBytes(ByteArray(0))
            var decoder: KopusDecoder? = null
            var rate = 48_000
            var channels = 1
            var frameSize = rate / 50
            var out = ShortArray(0)
            var byteChunk = ByteArray(0)
            var sizeBytes = 0L
            try {
                demux(
                    { sampleRateHz, ch ->
                        rate = normalizeRate(sampleRateHz)
                        channels = ch.coerceIn(1, 2)
                        frameSize = rate / 50
                        out = ShortArray(frameSize * channels)
                        byteChunk = ByteArray(frameSize * channels * 2)
                        decoder = KopusDecoder(sampleRate = rate, channels = channels)
                    },
                    { packet ->
                        val n = decoder!!.decode(
                            inData = packet,
                            outPcm = out,
                            frameSize = frameSize,
                        )
                        if (n > 0) {
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
                    },
                )
                return SpilledPcmMeta(
                    sampleRateHz = rate,
                    channels = channels,
                    sizeBytes = sizeBytes,
                )
            } finally {
                decoder?.close()
            }
        }

        private fun normalizeRate(hz: Int): Int = when (hz) {
            8_000, 12_000, 16_000, 24_000, 48_000 -> hz
            else -> 48_000
        }
    }
}
