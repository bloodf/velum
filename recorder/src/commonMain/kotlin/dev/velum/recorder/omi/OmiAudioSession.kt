package dev.velum.recorder.omi

import dev.velum.recorder.RecorderAudioCodec

/**
 * Read-only Omi / Friend audio session: feeds audio-characteristic notifications (see [OmiAudioPackets]) through
 * the reassembler and yields complete codec frames. It never writes to the device.
 */
class OmiAudioSession(val codec: OmiCodec) {
    private val reassembler = OmiPacketReassembler()

    /** Completed frame when [notification] starts a new packet after a previous one, else null. */
    fun accept(notification: ByteArray): ByteArray? =
        OmiAudioPackets.parseFragment(notification)?.let { reassembler.push(it) }

    /** The trailing frame when the stream ends. */
    fun finish(): ByteArray? = reassembler.flush()

    /** (received, expected) fragment counts; a gap means lost BLE notifications. */
    fun stats(): Pair<Long, Long> = reassembler.stats()
}

object OmiBattery {
    /** Battery Level (0x2A19) is one byte, 0..100 percent; anything else is not a valid reading. */
    fun parse(value: ByteArray): Int? =
        value.firstOrNull()?.toInt()?.and(0xff)?.takeIf { it in 0..100 }
}

fun OmiCodec.toRecorderCodec(): RecorderAudioCodec = when (this) {
    OmiCodec.Opus16 -> RecorderAudioCodec.RawOpus
    OmiCodec.Pcm16, OmiCodec.Pcm8 -> RecorderAudioCodec.Pcm16
    OmiCodec.MuLaw16, OmiCodec.MuLaw8 -> RecorderAudioCodec.MuLaw
}
