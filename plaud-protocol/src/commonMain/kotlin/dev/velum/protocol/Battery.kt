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

    override fun toString(): String = "BatteryReading(percent=$percent, rawPayload=${rawPayload.size} bytes)"

    companion object {
        /**
         * Parse a cmd-9 payload. Empty / truncated frames throw; percent is the
         * last byte (0–100 observed on device notifies).
         */
        fun parse(payload: ByteArray): BatteryReading {
            require(payload.isNotEmpty()) { "Empty battery payload" }
            val percent = payload.last().toInt() and 0xFF
            require(percent <= 100) {
                "Malformed battery percent $percent (expected 0–100)"
            }
            return BatteryReading(percent, payload.copyOf())
        }

        /** Like [parse] but returns null for empty or out-of-range payloads. */
        fun parseOrNull(payload: ByteArray): BatteryReading? =
            runCatching { parse(payload) }.getOrNull()
    }
}
