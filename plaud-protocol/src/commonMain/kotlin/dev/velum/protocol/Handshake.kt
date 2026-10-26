package dev.velum.protocol

/**
 * Handshake (cmd 1) response parser.
 *
 * Observed layout after `[0x01][cmd u16]`:
 * `[status u8][protocol u16 LE][tz u8][tzMinutes u8][channels u8]
 *  [wifi u8][noNsAgc u8][ogg u8]…` then version fields in the trailing bytes.
 *
 * Fixture from a real Note Pro (fw 1.0.0):
 * `01 01 00 00 14 00 05 1e 01 01 00 01 56 00 07 01`
 */
data class HandshakeResponse(
    val status: HandshakeStatus,
    val protocolVersion: Int,
    val timezoneHours: Int,
    val timezoneMinutes: Int,
    val audioChannels: Int,
    val wifiSupported: Boolean,
    val noNsAgc: Boolean,
    val oggAudio: Boolean,
    val rawPayload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeResponse) return false
        return status == other.status &&
            protocolVersion == other.protocolVersion &&
            timezoneHours == other.timezoneHours &&
            timezoneMinutes == other.timezoneMinutes &&
            audioChannels == other.audioChannels &&
            wifiSupported == other.wifiSupported &&
            noNsAgc == other.noNsAgc &&
            oggAudio == other.oggAudio &&
            rawPayload.contentEquals(other.rawPayload)
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + timezoneHours
        result = 31 * result + timezoneMinutes
        result = 31 * result + audioChannels
        result = 31 * result + wifiSupported.hashCode()
        result = 31 * result + noNsAgc.hashCode()
        result = 31 * result + oggAudio.hashCode()
        result = 31 * result + rawPayload.contentHashCode()
        return result
    }

    companion object {
        fun parse(payload: ByteArray): HandshakeResponse {
            require(payload.size >= 9) { "Handshake payload too short: ${payload.size}" }
            val statusCode = payload[0].toInt() and 0xFF
            val status = HandshakeStatus.fromCode(statusCode)
                ?: error("Unknown handshake status $statusCode")
            return HandshakeResponse(
                status = status,
                protocolVersion = Bytes.readU16Le(payload, 1),
                timezoneHours = payload[3].toInt() and 0xFF,
                timezoneMinutes = payload[4].toInt() and 0xFF,
                audioChannels = payload[5].toInt() and 0xFF,
                wifiSupported = (payload[6].toInt() and 0xFF) != 0,
                noNsAgc = (payload[7].toInt() and 0xFF) != 0,
                oggAudio = (payload[8].toInt() and 0xFF) != 0,
                rawPayload = payload,
            )
        }
    }
}
