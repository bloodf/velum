package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir
import eu.buney.kopus.OpusDecoder as KopusDecoder
import java.io.RandomAccessFile

actual fun createOpusDecoder(): OpusDecoder = KopusOpusDecoder()

/**
 * Ogg demux (common) + kopus Opus decode on Android.
 * MediaCodec path can replace this later for device-optimized decode.
 */
class KopusOpusDecoder : OpusDecoder {
    override fun decodeToPcm(oggBytes: ByteArray): PcmAudio =
        decodeInMemory(oggBytes)

    override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta =
        decodeStreaming(dest) { onHeader, onPacket ->
            OggOpusDemuxer.demuxForEach(oggBytes, onHeader, onPacket)
        }

    override fun decodePathToPcmFile(path: String, dest: ModelStoreDir, checkCancelled: () -> Unit): SpilledPcmMeta =
        RandomAccessFile(path, "r").use { raf ->
            val size = raf.length()
            decodeStreaming(dest, checkCancelled) { onHeader, onPacket ->
                OggOpusDemuxer.demuxAtForEach(
                    size = size,
                    readAt = { buf, position, length ->
                        // RandomAccessFile.read may return a short count; demux requires full pages.
                        raf.seek(position)
                        var got = 0
                        while (got < length) {
                            val n = raf.read(buf, got, length - got)
                            if (n < 0) return@demuxAtForEach if (got == 0) -1 else got
                            got += n
                        }
                        got
                    },
                    onHeader = onHeader,
                    onPacket = onPacket,
                )
            }
        }

    companion object {
        internal fun decodeInMemory(oggBytes: ByteArray): PcmAudio {
            var decoder: KopusDecoder? = null
            var skip = 0 // leading samples (pre-skip at the output rate) still to drop
            // libopus decodes any stream at the requested rate/channels: emit the import-path PCM
            // contract (16 kHz mono) directly, frame by frame, so memory stays bounded (#95).
            val rate = DECODED_PCM_RATE_HZ
            val channels = DECODED_PCM_CHANNELS
            // Opus packets can be up to 120ms; allocate for max frame, not 20ms only.
            val frameSize = rate / 50 * 6
            var out = ShortArray(0)
            var samples = ShortArray(0)
            var count = 0
            try {
                val streamMeta = OggOpusDemuxer.demuxForEach(
                    oggBytes = oggBytes,
                    onHeader = { _, _, preSkip ->
                        skip = preSkip * rate / 48_000
                        out = ShortArray(frameSize * channels)
                        samples = ShortArray(frameSize * channels * 64)
                        decoder = KopusDecoder(sampleRate = rate, channels = channels)
                    },
                    onPacket = { packet ->
                        val n = decoder!!.decode(
                            inData = packet,
                            len = packet.size,
                            outPcm = out,
                            frameSize = frameSize,
                        )
                        if (n > 0) {
                            val drop = minOf(skip, n)
                            skip -= drop
                            val first = drop * channels
                            val need = n * channels - first
                            if (count + need > samples.size) {
                                samples = samples.copyOf(
                                    (samples.size + need).coerceAtLeast(samples.size * 2),
                                )
                            }
                            for (i in 0 until need) {
                                samples[count++] = out[first + i]
                            }
                        }
                    },
                )
                return PcmAudio(
                    samples = samples.copyOf(count),
                    sampleRateHz = rate,
                    channels = channels,
                    truncated = streamMeta.truncated,
                )
            } finally {
                decoder?.close()
            }
        }

        private fun decodeStreaming(
            dest: ModelStoreDir,
            checkCancelled: () -> Unit = {},
            demux: (
                onHeader: (Int, Int, Int) -> Unit,
                onPacket: (ByteArray) -> Unit,
            ) -> OpusStreamMeta,
        ): SpilledPcmMeta {
            dest.writeBytes(ByteArray(0))
            var decoder: KopusDecoder? = null
            var skip = 0 // leading samples (pre-skip at the output rate) still to drop
            // libopus decodes any stream at the requested rate/channels: emit the import-path PCM
            // contract (16 kHz mono) directly, frame by frame, so memory stays bounded (#95).
            val rate = DECODED_PCM_RATE_HZ
            val channels = DECODED_PCM_CHANNELS
            // Opus packets can be up to 120ms; allocate for max frame, not 20ms only.
            val frameSize = rate / 50 * 6
            var out = ShortArray(0)
            var byteChunk = ByteArray(0)
            var sizeBytes = 0L
            try {
                val streamMeta = demux(
                    { _, _, preSkip ->
                        skip = preSkip * rate / 48_000
                        out = ShortArray(frameSize * channels)
                        byteChunk = ByteArray(frameSize * channels * 2)
                        decoder = KopusDecoder(sampleRate = rate, channels = channels)
                    },
                    { packet ->
                        checkCancelled()
                        val n = decoder!!.decode(
                            inData = packet,
                            len = packet.size,
                            outPcm = out,
                            frameSize = frameSize,
                        )
                        if (n > 0) {
                            val drop = minOf(skip, n)
                            skip -= drop
                            val first = drop * channels
                            val sampleCount = n * channels
                            var bi = 0
                            for (i in first until sampleCount) {
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
                    truncated = streamMeta.truncated,
                )
            } finally {
                decoder?.close()
            }
        }
    }
}
