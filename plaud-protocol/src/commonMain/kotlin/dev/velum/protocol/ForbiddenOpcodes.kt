package dev.velum.protocol

/**
 * Opcodes Velum must never send to a device.
 *
 * - 5 — depair
 * - 104 — clear all recordings / factory-style wipe
 * - 128 — resetFindmy
 * - 0xFE20 — reserved unlock/reset path (community: destructive)
 * - 143 — OTA
 */
object ForbiddenOpcodes {
    const val DEPAIR: Int = 5
    const val CLEAR_ALL: Int = 104
    const val RESET_FINDMY: Int = 128
    const val FE20: Int = 0xFE20
    const val OTA: Int = 143

    val ALL: Set<Int> = setOf(DEPAIR, CLEAR_ALL, RESET_FINDMY, FE20, OTA)

    fun isForbidden(opcode: Int): Boolean = opcode in ALL

    fun requireAllowed(opcode: Int) {
        require(!isForbidden(opcode)) {
            "Forbidden opcode 0x${opcode.toString(16)} ($opcode) — Velum never sends depair/clear/resetFindmy/OTA/0xFE20"
        }
    }
}

/**
 * Common-settings ids that must never be written (cmd 8 / params writes).
 *
 * - 4 — AUTO_DELETE_RECORD_FILE
 * - 26 — AUTO_SYNC (cloud)
 */
object ForbiddenSettings {
    const val AUTO_DELETE: Int = 4
    const val AUTO_SYNC: Int = 26

    val ALL: Set<Int> = setOf(AUTO_DELETE, AUTO_SYNC)

    fun isForbidden(settingId: Int): Boolean = settingId in ALL

    fun requireWritable(settingId: Int) {
        require(!isForbidden(settingId)) {
            "Forbidden setting id $settingId — Velum never writes AUTO_DELETE(4) or AUTO_SYNC(26)"
        }
    }
}

/**
 * APK 3.30.0 command map entries with no interpreted handler.
 * Documented so tests and callers leave them unused (do not send).
 */
object UnusedCommands {
    val ALL: Set<Int> = setOf(5, 13, 38, 50, 51, 109, 141, 142, 145, 151)

    fun isUnused(opcode: Int): Boolean = opcode in ALL
}
