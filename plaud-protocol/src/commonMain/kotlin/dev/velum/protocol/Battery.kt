package dev.velum.protocol

/**
 * Unsolicited cmd 9 payloads observed on Note Pro look like battery level.
 * Example: full frame `01 09 00 00 5d` → payload `00 5d` → 93%.
 */
object Battery {
    /** App → device poll for battery (cmd 9). */
    fun encodeBatteryRequest(): ByteArray =
        Framing.encodeCommand(Commands.BATTERY_LIKELY)
}

data class BatteryReading(val percent: Int, val rawPayload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BatteryReading) return false
        return percent == other.percent && rawPayload.contentEquals(other.rawPayload)
    }

    override fun hashCode(): Int = 31 * percent + rawPayload.contentHashCode()

    companion object {
        fun parse(payload: ByteArray): BatteryReading {
            require(payload.isNotEmpty()) { "Empty battery payload" }
            val percent = payload.last().toInt() and 0xFF
            return BatteryReading(percent, payload)
        }
    }
}
