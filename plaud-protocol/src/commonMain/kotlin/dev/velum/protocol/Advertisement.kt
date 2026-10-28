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

    /** Community / APK name for [protocolVersion] (bytes 18–19 of manufacturer data). */
    val portVersion: Int get() = protocolVersion

    val modelName: String
        get() = model.displayName.takeUnless { model == DeviceModel.Unknown }
            ?: "Unknown ($modelCode)"

    val commandTable: CommandTable
        get() = CommandTable.forModel(model, protocolVersion)

    /**
     * True when manufacturer data identifies a Note Pro family device with a
     * usable protocol/port version. Malformed or unrelated payloads must not
     * report successful Note Pro discovery via this check.
     */
    val isNoteProFamilyDiscovery: Boolean
        get() = model.isNoteProFamily && portVersion in NOTE_PRO_PORT_VERSION_RANGE

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
        /** Observed Note Pro family protocol / port versions (inclusive). */
        val NOTE_PRO_PORT_VERSION_RANGE: IntRange = 20..29

        fun parseManufacturerData(data: ByteArray): PlaudAdvertisement? {
            if (data.size < 20) return null
            val modelCode = Bytes.readU16Le(data, 1)
            val serialBytes = data.copyOfRange(9, 17)
            val serial = serialBytes.decodeToString().trimEnd('\u0000', ' ')
            val protocolVersion = Bytes.readU16Le(data, 18)
            return PlaudAdvertisement(modelCode, serial, protocolVersion, data.copyOf())
        }

        /**
         * Parse only when the payload is a Note Pro family discovery
         * (project 881/883 + portVersion in range). Malformed or unrelated
         * fixtures return null — never a false successful Note Pro discovery.
         */
        fun parseNoteProFamilyDiscovery(data: ByteArray): PlaudAdvertisement? =
            parseManufacturerData(data)?.takeIf { it.isNoteProFamilyDiscovery }
    }
}

/**
 * Synthetic manufacturer-data fixtures for project 881 / portVersion checks.
 * Values are protocol layout only — never real device identity material.
 */
object AdvertisementFixtures {
    fun notePro881(
        portVersion: Int = 20,
        serial: String = "881FAKE1",
    ): ByteArray = build(modelCode = DeviceModel.NotePro.code, serial = serial, portVersion = portVersion)

    fun notePro883(
        portVersion: Int = 20,
        serial: String = "883FAKE1",
    ): ByteArray = build(modelCode = DeviceModel.NotePro883.code, serial = serial, portVersion = portVersion)

    /** Shorter than the documented 20-byte layout. */
    fun tooShort(): ByteArray = ByteArray(12).also {
        Bytes.u16Le(DeviceModel.NotePro.code).copyInto(it, 1)
    }

    /** Empty manufacturer payload. */
    fun empty(): ByteArray = ByteArray(0)

    /** 20 zero bytes — parses structurally but is not a Note Pro discovery. */
    fun allZeros(): ByteArray = ByteArray(20)

    /** Truncated before portVersion (bytes 18–19 missing). */
    fun truncatedBeforePortVersion(): ByteArray = ByteArray(18).also {
        Bytes.u16Le(DeviceModel.NotePro.code).copyInto(it, 1)
        "881TRUNC".encodeToByteArray().copyInto(it, 9, 0, 8)
    }

    /** Valid length, non–Note Pro model code. */
    fun otherModel(modelCode: Int = DeviceModel.Note.code, portVersion: Int = 20): ByteArray =
        build(modelCode = modelCode, serial = "888OTHER", portVersion = portVersion)

    /** Valid 881 layout but portVersion outside the Note Pro range. */
    fun notePro881WrongPort(portVersion: Int = 0): ByteArray =
        notePro881(portVersion = portVersion)

    fun build(modelCode: Int, serial: String, portVersion: Int): ByteArray {
        val data = ByteArray(20)
        Bytes.u16Le(modelCode).copyInto(data, 1)
        val serialBytes = serial.encodeToByteArray()
        serialBytes.copyInto(data, 9, 0, minOf(8, serialBytes.size))
        Bytes.u16Le(portVersion).copyInto(data, 18)
        return data
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
