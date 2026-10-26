package dev.velum.protocol

/**
 * Plaud product codes from BLE manufacturer data (bytes 1–2).
 *
 * Project **883** is Note Pro family alongside **881** (APK OTA push disables both).
 */
enum class DeviceModel(val code: Int, val displayName: String) {
    PlaudOne(860, "Plaud One"),
    NotePin(880, "NotePin"),
    NotePro(881, "Note Pro"),
    NotePro883(883, "Note Pro"),
    NotePinS(882, "NotePin S"),
    Note(888, "Note"),
    Unknown(-1, "Unknown"),
    ;

    /** Note Pro family (881 / 883). Official app turns off OTA push for these. */
    val isNoteProFamily: Boolean
        get() = this == NotePro || this == NotePro883

    /** Velum never runs OTA against Note Pro family; other models still forbid opcode 143. */
    val supportsOta: Boolean
        get() = !isNoteProFamily

    companion object {
        fun fromCode(code: Int): DeviceModel =
            entries.find { it.code == code } ?: Unknown
    }
}

/**
 * Per-model / per-protocol command opcodes.
 * Conflicting sources (Pro vs original Note) are resolved by [forModel].
 */
data class CommandTable(
    val model: DeviceModel,
    val protocolVersion: Int,
    val handshake: Int = Commands.HANDSHAKE,
    val getSsn: Int = Commands.GET_SSN,
    val recordingState: Int = 3,
    val setClock: Int,
    val getStorage: Int = 6,
    val commonSettings: Int = 8,
    val battery: Int = Commands.BATTERY_LIKELY,
    val startRecord: Int = 20,
    val pauseRecord: Int = 21,
    val resumeRecord: Int = 22,
    val stopRecord: Int = 23,
    val ledOn: Int,
    val ledOff: Int,
    val getSessions: Int = Commands.GET_SESSIONS,
    val syncFileStart: Int = Commands.SYNC_FILE_START,
    val syncFileStop: Int = Commands.SYNC_FILE_STOP,
    val deleteFile: Int = Commands.DELETE_FILE,
    val deleteFileRsp: Int = Commands.DELETE_FILE_RSP,
    val setBleName: Int,
    val getBleName: Int,
    val usesEncryptedLink: Boolean,
    val usesRsaUnlock: Boolean,
    val usesEccUnlock: Boolean,
) {
    companion object {
        fun forModel(model: DeviceModel, protocolVersion: Int): CommandTable {
            val encrypted = protocolVersion >= 20
            val rsa = protocolVersion in 20..29
            val ecc = protocolVersion >= 30
            return when (model) {
                DeviceModel.NotePro,
                DeviceModel.NotePro883,
                DeviceModel.NotePin,
                DeviceModel.NotePinS,
                -> CommandTable(
                    model = model,
                    protocolVersion = protocolVersion,
                    setClock = 4,
                    ledOn = 24,
                    ledOff = 25,
                    setBleName = 107,
                    getBleName = 108,
                    usesEncryptedLink = encrypted,
                    usesRsaUnlock = rsa,
                    usesEccUnlock = ecc,
                )
                DeviceModel.Note -> CommandTable(
                    model = model,
                    protocolVersion = protocolVersion,
                    setClock = 38,
                    ledOn = 33,
                    ledOff = 34,
                    setBleName = 130,
                    getBleName = 108,
                    usesEncryptedLink = encrypted,
                    usesRsaUnlock = rsa,
                    usesEccUnlock = ecc,
                )
                DeviceModel.PlaudOne -> CommandTable(
                    model = model,
                    protocolVersion = protocolVersion.coerceAtLeast(30),
                    setClock = 4,
                    ledOn = 24,
                    ledOff = 25,
                    setBleName = 107,
                    getBleName = 108,
                    usesEncryptedLink = true,
                    usesRsaUnlock = false,
                    usesEccUnlock = true,
                )
                DeviceModel.Unknown -> CommandTable(
                    model = model,
                    protocolVersion = protocolVersion,
                    setClock = 4,
                    ledOn = 24,
                    ledOff = 25,
                    setBleName = 107,
                    getBleName = 108,
                    usesEncryptedLink = encrypted,
                    usesRsaUnlock = rsa,
                    usesEccUnlock = ecc,
                )
            }
        }
    }
}

/** Standard GATT helpers beyond the Plaud 0x1910 service. */
object StandardGatt {
    const val GENERIC_ACCESS = "00001800-0000-1000-8000-00805f9b34fb"
    const val GENERIC_ATTRIBUTE = "00001801-0000-1000-8000-00805f9b34fb"
    const val DEVICE_INFORMATION = "0000180a-0000-1000-8000-00805f9b34fb"
    const val TX_POWER = "00001804-0000-1000-8000-00805f9b34fb"
    const val BATTERY_SERVICE = PlaudUuids.BATTERY_SERVICE
    const val BATTERY_LEVEL = PlaudUuids.BATTERY_LEVEL
    const val FIND_MY = "0000fd44-0000-1000-8000-00805f9b34fb"
    const val ACCESSORY_INFORMATION = "87290102-3c51-43b1-a1a9-11b9dc38478b"
    const val FIRMWARE_REVISION = "00002a26-0000-1000-8000-00805f9b34fb"
    const val MODEL_NUMBER = "00002a24-0000-1000-8000-00805f9b34fb"
    const val SERIAL_NUMBER = "00002a25-0000-1000-8000-00805f9b34fb"
    const val MANUFACTURER_NAME = "00002a29-0000-1000-8000-00805f9b34fb"
}
