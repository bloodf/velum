package dev.velum.protocol

/**
 * Unsolicited cmd 9 payloads observed on Note Pro look like battery level.
 * Layout: `charging u8, level u8` — example full frame `01 09 00 00 5d`
 * → payload `00 5d` → 93%.
 *
 * Out-of-range level (e.g. `0xFF` while charging) is observed on hardware and
 * must not throw on the app path — use [parseOrNull].
 */
object Battery {
    /** App → device poll for battery (cmd 9). */
    fun encodeBatteryRequest(): ByteArray =
        Framing.encodeCommand(Commands.BATTERY_LIKELY)

    /**
     * Parse a cmd-9 payload. Returns null for truncated (&lt; 2 bytes), empty,
     * or out-of-range level (not 0–100) — including `0xFF` while charging.
     */
    fun parseOrNull(payload: ByteArray): BatteryReading? =
        BatteryReading.parseOrNull(payload)
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
         * Parse a cmd-9 payload. Truncated frames (&lt; 2 bytes: charging+level)
         * and out-of-range percent throw; percent is the level byte at index 1.
         */
        fun parse(payload: ByteArray): BatteryReading {
            require(payload.size >= 2) {
                "Truncated battery payload (need charging+level, size=${payload.size})"
            }
            val percent = payload[1].toInt() and 0xFF
            require(percent <= 100) {
                "Malformed battery percent $percent (expected 0–100)"
            }
            return BatteryReading(percent, payload.copyOf())
        }

        /** Like [parse] but returns null for truncated or out-of-range payloads. */
        fun parseOrNull(payload: ByteArray): BatteryReading? =
            runCatching { parse(payload) }.getOrNull()
    }
}
