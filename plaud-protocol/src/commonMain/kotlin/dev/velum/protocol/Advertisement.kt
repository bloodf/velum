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
        /**
         * Parse manufacturer data only when it came from Plaud's company id
         * ([PlaudUuids.MANUFACTURER_ID]); anything else (or an unknown id) is not a Plaud device.
         */
        fun parse(manufacturerId: Int?, data: ByteArray): PlaudAdvertisement? =
            if (manufacturerId == PlaudUuids.MANUFACTURER_ID) parseManufacturerData(data) else null

        /**
         * [data] is the payload after the company id. Returns null unless the documented layout is
         * structurally plausible: at least 20 bytes, non-zero model code and a protocol version in
         * 1..255. The serial charset is NOT validated (no real capture is on record, #5); it is
         * sanitised for display instead. Models outside [DeviceModel] still parse as
         * [DeviceModel.Unknown] by design.
         */
        fun parseManufacturerData(data: ByteArray): PlaudAdvertisement? {
            if (data.size < 20) return null
            val modelCode = Bytes.readU16Le(data, 1)
            if (modelCode == 0) return null
            val protocolVersion = Bytes.readU16Le(data, 18)
            if (protocolVersion !in 1..255) return null
            return PlaudAdvertisement(modelCode, displaySerial(data.copyOfRange(9, 17)), protocolVersion, data)
        }

        /** Printable ASCII (trailing NUL/space padding trimmed) as text; anything else as `0x`-hex. */
        private fun displaySerial(raw: ByteArray): String {
            var end = raw.size
            while (end > 0 && (raw[end - 1].toInt() == 0 || raw[end - 1].toInt() == 0x20)) end--
            val bytes = raw.copyOf(end)
            val printable = bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }
            return if (printable) {
                bytes.decodeToString()
            } else {
                "0x" + bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
            }
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
