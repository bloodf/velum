package dev.velum.ble.gatt

import dev.velum.protocol.Bytes
import dev.velum.protocol.Commands
import dev.velum.protocol.CommonSettings
import dev.velum.protocol.ForbiddenOpcodes
import dev.velum.protocol.ForbiddenSettings
import dev.velum.protocol.Framing

/**
 * Plaud plaintext write guard, deny by default. The only frames production sends on 2BB1 are:
 * - command frames `[APP_PREFIX, cmd (u16le), params...]`, where `Framing.encodeCommand` trims trailing zeros, so a
 *   param-less single-byte opcode is `[APP_PREFIX, cmd]` and is read as cmd, as the device does;
 * - unlock chunks `[opcode (u16le), total, index, data...]` for the three unlock opcodes (0xFE10 / 0xFE11 / 0xFE12).
 * Anything else is refused, including bare `[0x00, op]` / `[0x02, op]` shapes whose device reading is unknown.
 * Rejects forbidden opcodes (5 / 30 / 104 / 128 / 0xFE20 / 143) and every cmd 8 frame addressing a forbidden
 * setting (4 / 26), reads included: `Framing.encodeCommand` trims a value-0 write to `[settingId]`, which
 * is byte-identical to a read, so the two cannot be told apart here.
 */
object PlaudWriteGuard : WriteGuard {
    private val unlockOpcodes = setOf(Commands.UNLOCK_SN_SIGNATURE, Commands.UNLOCK_NEED_PUBLIC_KEY, Commands.UNLOCK_SESSION_SECRET)

    override fun check(plaintext: ByteArray) {
        require(plaintext.size >= 2) { "Outbound packet too short" }
        val prefixed = plaintext[0] == Framing.APP_PREFIX
        val hasParamsFrame = prefixed && plaintext.size >= 3
        val opcode = if (hasParamsFrame) {
            Bytes.readU16Le(plaintext, 1)
        } else if (prefixed) {
            plaintext[1].toInt() and 0xFF
        } else {
            Bytes.readU16Le(plaintext, 0)
        }
        ForbiddenOpcodes.requireAllowed(opcode)
        if (!prefixed) {
            require(opcode in unlockOpcodes && plaintext.size >= 4) { "Outbound frame shape not recognised" }
            return
        }
        if (opcode == CommonSettings.CMD) {
            val paramsOffset = if (hasParamsFrame) 3 else 2
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
