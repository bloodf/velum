package dev.velum.protocol

/**
 * Shared Plaud outbound safety checks used by the session client **and** the
 * BLE transport write path for the Plaud GATT profile.
 *
 * Rejects [ForbiddenOpcodes] and writes to [ForbiddenSettings] before any
 * bytes can leave the process toward a radio (or fake peripheral).
 */
object PlaudOutboundGuard {
    /**
     * Inspect a plaintext Plaud frame (APP-prefixed or raw opcode) and throw
     * if it would send a forbidden opcode or forbidden settings write.
     */
    fun requireAllowed(packet: ByteArray) {
        require(packet.size >= 2) { "Outbound packet too short" }
        val hasAppPrefix = packet[0] == Framing.APP_PREFIX && packet.size >= 3
        val opcode = if (hasAppPrefix) {
            Bytes.readU16Le(packet, 1)
        } else {
            Bytes.readU16Le(packet, 0)
        }
        ForbiddenOpcodes.requireAllowed(opcode)
        if (opcode == CommonSettings.CMD) {
            val paramsOffset = if (hasAppPrefix) 3 else 2
            val params = if (packet.size > paramsOffset) {
                packet.copyOfRange(paramsOffset, packet.size)
            } else {
                ByteArray(0)
            }
            // Cmd 8 read is [settingId]; write is [settingId][value…].
            // Only writes of forbidden settings are rejected (reads stay allowed).
            if (params.size > 1) {
                ForbiddenSettings.requireWritable(params[0].toInt() and 0xFF)
            }
        }
    }
}
