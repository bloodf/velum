package dev.velum.ble.gatt

import dev.velum.protocol.Bytes
import dev.velum.protocol.CommonSettings
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing

/**
 * Plaud plaintext write guard (moved verbatim from `PlaudDeviceClient.assertOutboundAllowed`).
 * Rejects forbidden opcodes (5 / 104 / 128 / 0xFE20 / 143) and writes of forbidden settings (4 / 26).
 */
object PlaudWriteGuard : WriteGuard {
    override fun check(plaintext: ByteArray) {
        require(plaintext.size >= 2) { "Outbound packet too short" }
        val hasAppPrefix = plaintext[0] == Framing.APP_PREFIX && plaintext.size >= 3
        val opcode = if (hasAppPrefix) {
            Bytes.readU16Le(plaintext, 1)
        } else {
            Bytes.readU16Le(plaintext, 0)
        }
        ForbiddenOpcodes.requireAllowed(opcode)
        if (opcode == CommonSettings.CMD) {
            val paramsOffset = if (hasAppPrefix) 3 else 2
            val params = if (plaintext.size > paramsOffset) {
                plaintext.copyOfRange(paramsOffset, plaintext.size)
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
