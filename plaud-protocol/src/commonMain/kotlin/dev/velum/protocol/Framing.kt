package dev.velum.protocol

/**
 * Encodes and decodes Plaud BLE packet frames.
 *
 * App → device: `[0x01][cmd u16 LE][params…]` (trailing zeros trimmed).
 * Device → app: byte 0 is [PacketType]; for command responses, bytes 1–2 are cmd.
 */
object Framing {
    const val APP_PREFIX: Byte = 0x01

    fun encodeCommand(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val raw = ByteArray(1 + 2 + params.size)
        raw[0] = APP_PREFIX
        Bytes.u16Le(cmd).copyInto(raw, 1)
        params.copyInto(raw, 3)
        return Bytes.trimTrailingZeros(raw)
    }

    fun encodeHandshake(
        token: String,
        protocolVersionHint: Int = 20,
        presentFlag: Int = 0,
    ): ByteArray {
        val padded = padBindToken(token, protocolVersionHint)
        val params = ByteArray(2 + 1 + padded.size)
        // Fixed prefix seen in community captures: 02 00 then P then token.
        params[0] = 0x02
        params[1] = 0x00
        params[2] = presentFlag.toByte()
        padded.copyInto(params, 3)
        return encodeCommand(Commands.HANDSHAKE, params)
    }

    /**
     * Bind token is the Plaud `user_id`, right-padded with ASCII `'0'`
     * to 32 chars when protocol ≥ 9, else 16.
     */
    fun padBindToken(token: String, protocolVersion: Int): ByteArray {
        val width = if (protocolVersion >= 9) 32 else 16
        val padded = token.take(width).padEnd(width, '0')
        return padded.encodeToByteArray()
    }

    fun decodeInbound(raw: ByteArray): InboundPacket {
        require(raw.isNotEmpty()) { "Empty packet" }
        val typeCode = raw[0].toInt() and 0xFF
        val type = PacketType.fromCode(typeCode)
            ?: return InboundPacket.Unknown(typeCode, raw)

        return when (type) {
            PacketType.CommandResponse -> {
                require(raw.size >= 3) { "Command response too short" }
                val cmd = Bytes.readU16Le(raw, 1)
                InboundPacket.CommandResponse(cmd, raw.copyOfRange(3, raw.size), raw)
            }
            PacketType.RecordingData -> InboundPacket.RecordingData(raw)
            PacketType.SpeedTest -> InboundPacket.SpeedTest(raw)
            PacketType.DeviceLog -> InboundPacket.DeviceLog(raw)
        }
    }
}

sealed class InboundPacket {
    abstract val raw: ByteArray

    data class CommandResponse(
        val cmd: Int,
        val payload: ByteArray,
        override val raw: ByteArray,
    ) : InboundPacket() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CommandResponse) return false
            return cmd == other.cmd &&
                payload.contentEquals(other.payload) &&
                raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int =
            31 * cmd + payload.contentHashCode() + raw.contentHashCode()
    }

    data class RecordingData(override val raw: ByteArray) : InboundPacket() {
        override fun equals(other: Any?): Boolean =
            other is RecordingData && raw.contentEquals(other.raw)

        override fun hashCode(): Int = raw.contentHashCode()
    }

    data class SpeedTest(override val raw: ByteArray) : InboundPacket() {
        override fun equals(other: Any?): Boolean =
            other is SpeedTest && raw.contentEquals(other.raw)

        override fun hashCode(): Int = raw.contentHashCode()
    }

    data class DeviceLog(override val raw: ByteArray) : InboundPacket() {
        override fun equals(other: Any?): Boolean =
            other is DeviceLog && raw.contentEquals(other.raw)

        override fun hashCode(): Int = raw.contentHashCode()
    }

    data class Unknown(val typeCode: Int, override val raw: ByteArray) : InboundPacket() {
        override fun equals(other: Any?): Boolean =
            other is Unknown && typeCode == other.typeCode && raw.contentEquals(other.raw)

        override fun hashCode(): Int = 31 * typeCode + raw.contentHashCode()
    }
}
