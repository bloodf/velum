package dev.velum.protocol

/**
 * Device → app packet type (byte 0 of inbound frames).
 */
enum class PacketType(val code: Int) {
    CommandResponse(1),
    RecordingData(2),
    SpeedTest(4),
    DeviceLog(5),
    ;

    companion object {
        fun fromCode(code: Int): PacketType? = entries.find { it.code == code }
    }
}
