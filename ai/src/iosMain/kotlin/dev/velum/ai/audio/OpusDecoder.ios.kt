package dev.velum.ai.audio

import dev.velum.ai.local.ModelStoreDir

actual fun createOpusDecoder(): OpusDecoder = UnsupportedIosOpusDecoder()

private class UnsupportedIosOpusDecoder : OpusDecoder {
    override fun decodeToPcm(oggBytes: ByteArray): PcmAudio {
        // Validate container without retaining the Opus packet list.
        OggOpusDemuxer.demuxForEach(oggBytes) { }
        throw UnsupportedOperationException(
            "Opus decode is not wired on iOS yet (Android/JVM use kopus). " +
                "Ogg demux succeeded; packet decode is missing.",
        )
    }

    override fun decodeToPcmFile(oggBytes: ByteArray, dest: ModelStoreDir): SpilledPcmMeta {
        OggOpusDemuxer.demuxForEach(oggBytes) { }
        throw UnsupportedOperationException(
            "Opus decode is not wired on iOS yet (Android/JVM use kopus).",
        )
    }

    // decodePathToPcmFile: inherit common default (ModelStoreDir.readRange + demuxAtForEach).
}
