package dev.velum.protocol

/**
 * Cmd 8 common-settings read/write helpers.
 *
 * Write payloads are `[settingId u8][value…]`. [ForbiddenSettings] are rejected
 * before framing so AUTO_DELETE / AUTO_SYNC can never leave the device.
 */
object CommonSettings {
    const val CMD: Int = 8

    fun encodeRead(settingId: Int): ByteArray =
        Framing.encodeCommand(CMD, byteArrayOf(settingId.toByte()))

    fun encodeWrite(settingId: Int, value: ByteArray): ByteArray {
        ForbiddenSettings.requireWritable(settingId)
        return Framing.encodeCommand(CMD, byteArrayOf(settingId.toByte()) + value)
    }
}
