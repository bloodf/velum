package dev.velum.protocol

/**
 * Opcodes Velum must never send to a device.
 *
 * - 5 — depair
 * - 104 — clear all recordings / factory-style wipe
 * - 0xFE20 — reserved unlock/reset path (community: destructive)
 * - 143 — OTA
 */
object ForbiddenOpcodes {
    const val DEPAIR: Int = 5
    const val CLEAR_ALL: Int = 104
    const val FE20: Int = 0xFE20
    const val OTA: Int = 143

    val ALL: Set<Int> = setOf(DEPAIR, CLEAR_ALL, FE20, OTA)

    fun isForbidden(opcode: Int): Boolean = opcode in ALL

    fun requireAllowed(opcode: Int) {
        require(!isForbidden(opcode)) {
            "Forbidden opcode 0x${opcode.toString(16)} ($opcode) — Velum never sends depair/clear/OTA/0xFE20"
        }
    }
}
