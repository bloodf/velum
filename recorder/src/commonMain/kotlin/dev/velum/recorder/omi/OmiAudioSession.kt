package dev.velum.recorder.omi

import dev.velum.recorder.RecorderAudioCodec

/**
 * Read-only Omi / Friend audio session. [codec] is the value the device reports on its codec characteristic
 * (19b10002, read once at connect). Audio notifications (see [OmiAudioPackets]) go through [OmiFrameAssembler],
 * which only returns frames that arrived in sequence: a gap drops the pending frame and counts it in [lostFrames].
 * It never writes to the device.
 */
class OmiAudioSession(val codec: OmiCodec) {
    private val assembler = OmiFrameAssembler()

    val recorderCodec: RecorderAudioCodec get() = codec.toRecorderCodec()
    val sampleRateHz: Int get() = codec.sampleRateHz

    /** Frames dropped because of a sequence gap or an oversize frame. */
    val lostFrames: Long get() = assembler.lostFrames

    /** The previous in-sequence frame when [notification] starts a new one, else null. */
    fun accept(notification: ByteArray): ByteArray? = assembler.accept(notification)

    /** The trailing frame when the stream ends. */
    fun finish(): ByteArray? = assembler.finish()
}

object OmiBattery {
    /** Battery Level (0x2A19) is exactly one byte, 0..100 percent; anything else is not a valid reading. */
    fun parse(value: ByteArray): Int? =
        value.singleOrNull()?.toInt()?.and(0xff)?.takeIf { it in 0..100 }
}

fun OmiCodec.toRecorderCodec(): RecorderAudioCodec = when (this) {
    OmiCodec.Opus16 -> RecorderAudioCodec.RawOpus
    OmiCodec.Pcm16, OmiCodec.Pcm8 -> RecorderAudioCodec.Pcm16
    OmiCodec.MuLaw16, OmiCodec.MuLaw8 -> RecorderAudioCodec.MuLaw
}
