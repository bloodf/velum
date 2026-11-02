package dev.velum.ble.gatt

import dev.velum.protocol.Bytes
import dev.velum.protocol.CommonSettings
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing

/**
 * Plaud plaintext write guard, moved from `PlaudDeviceClient.assertOutboundAllowed` with one addition:
 * 2-byte `[APP_PREFIX, cmd]` frames (param-less single-byte opcodes after `Framing.encodeCommand` trims
 * trailing zeros) are read as cmd, as the device does, instead of as a u16le.
 * Rejects forbidden opcodes (5 / 104 / 128 / 0xFE20 / 143) and every cmd 8 frame addressing a forbidden
 * setting (4 / 26), reads included: `Framing.encodeCommand` trims a value-0 write to `[settingId]`, which
 * is byte-identical to a read, so the two cannot be told apart here.
 */
object PlaudWriteGuard : WriteGuard {
    override fun check(plaintext: ByteArray) {
        require(plaintext.size >= 2) { "Outbound packet too short" }
        val hasAppPrefix = plaintext[0] == Framing.APP_PREFIX && plaintext.size >= 3
        val opcode = if (hasAppPrefix) {
            Bytes.readU16Le(plaintext, 1)
        } else if (plaintext[0] == Framing.APP_PREFIX && plaintext.size == 2) {
            // Framing.encodeCommand trims trailing zeros, so a param-less single-byte opcode
            // (5 / 104 / 128 / 143 ...) is sent as [APP_PREFIX, cmd]; the device reads it as cmd, not u16le.
            plaintext[1].toInt() and 0xFF
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
            params.firstOrNull()?.let { first ->
                val settingId = first.toInt() and 0xFF
                require(!ForbiddenSettings.isForbidden(settingId)) {
                    "Forbidden setting id $settingId: cmd 8 frames for AUTO_DELETE(4) / AUTO_SYNC(26) never leave the app"
                }
            }
        }
    }
}
