package dev.velum.protocol

/**
 * Manufacturer data `0x005D` layout (community-documented):
 * - bytes 1–2: model (`0x0371` = 881 = Note Pro)
 * - bytes 9–16: serial (ASCII)
 * - bytes 18–19: protocol version (u16 LE), e.g. `14 00` = 20
 */
data class PlaudAdvertisement(
    val modelCode: Int,
    val serial: String,
    val protocolVersion: Int,
    val rawManufacturerData: ByteArray,
) {
    val model: DeviceModel get() = DeviceModel.fromCode(modelCode)

    val modelName: String
        get() = model.displayName.takeUnless { model == DeviceModel.Unknown }
            ?: "Unknown ($modelCode)"

    val commandTable: CommandTable
        get() = CommandTable.forModel(model, protocolVersion)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaudAdvertisement) return false
        return modelCode == other.modelCode &&
            serial == other.serial &&
            protocolVersion == other.protocolVersion &&
            rawManufacturerData.contentEquals(other.rawManufacturerData)
    }

    override fun hashCode(): Int {
        var result = modelCode
        result = 31 * result + serial.hashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + rawManufacturerData.contentHashCode()
        return result
    }

    companion object {
        fun parseManufacturerData(data: ByteArray): PlaudAdvertisement? {
            if (data.size < 20) return null
            val modelCode = Bytes.readU16Le(data, 1)
            val serialBytes = data.copyOfRange(9, 17)
            val serial = serialBytes.decodeToString().trimEnd('\u0000', ' ')
            val protocolVersion = Bytes.readU16Le(data, 18)
            return PlaudAdvertisement(modelCode, serial, protocolVersion, data)
        }
    }
}

/**
 * CRC-16/CCITT (byte-wise), used for file tail checks.
 * Polynomial 0x1021, init 0xFFFF, refin/refout false, xorout 0x0000
 * (common CCITT-FALSE). Exact firmware match is still [?] in community docs.
 */
object Crc16Ccitt {
    fun compute(data: ByteArray, initial: Int = 0xFFFF): Int {
        var crc = initial and 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }
}
