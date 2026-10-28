package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir

actual fun createOpusDecoder(): OpusDecoder = UnsupportedIosOpusDecoder()

private class UnsupportedIosOpusDecoder : OpusDecoder {
    override fun decodeToPcm(oggBytes: ByteArray): PcmAudio {
        // Demux still validates the container so callers get clear format errors.
        OggOpusDemuxer.demux(oggBytes)
        throw UnsupportedOperationException(
            "Opus decode is not wired on iOS yet (Android/JVM use kopus). " +
                "Ogg demux succeeded; packet decode is missing.",
        )
    }

    override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta {
        OggOpusDemuxer.demux(oggBytes)
        throw UnsupportedOperationException(
            "Opus decode is not wired on iOS yet (Android/JVM use kopus).",
        )
    }
}
