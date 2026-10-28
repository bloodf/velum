package dev.velum.ble

import dev.velum.protocol.PlaudUuids
import dev.velum.recorder.omi.OmiUuids
import dev.velum.recorder.pocket.PocketUuids

/**
 * Named GATT characteristic on a [GattProfile].
 *
 * [uuid] is the 128-bit characteristic UUID string; [serviceUuid] defaults to
 * the profile service when null (for multi-service profiles).
 */
data class GattCharacteristic(
    val name: String,
    val uuid: String,
    val serviceUuid: String? = null,
    val canWrite: Boolean = false,
    val canNotify: Boolean = false,
)

/**
 * Driver-facing GATT layout: primary service, named write/notify characteristics,
 * and optional MTU hints for the transport.
 */
data class GattProfile(
    val id: String,
    val displayName: String,
    val serviceUuid: String,
    val characteristics: List<GattCharacteristic>,
    /** Preferred ATT MTU to request after connect (null = platform default). */
    val preferredMtu: Int? = null,
    /** Soft minimum useful MTU for this protocol (documentation / tests). */
    val minUsefulMtu: Int? = null,
) {
    private val byName: Map<String, GattCharacteristic> =
        characteristics.associateBy { it.name }

    fun characteristic(name: String): GattCharacteristic =
        byName[name]
            ?: error("Characteristic '$name' is not part of GATT profile '$id'")

    fun writeCharacteristics(): List<GattCharacteristic> =
        characteristics.filter { it.canWrite }

    fun notifyCharacteristics(): List<GattCharacteristic> =
        characteristics.filter { it.canNotify }

    fun resolveServiceUuid(characteristic: GattCharacteristic): String =
        characteristic.serviceUuid ?: serviceUuid
}

/**
 * Well-known profiles for shipped / in-tree recorder drivers.
 *
 * Limitless UUIDs come from BasedHardware/omi `models.dart` (already cited in
 * [OmiUuids]); they are not invented here.
 */
object GattProfiles {
    /** Plaud write characteristic name (2BB1). */
    const val PLAUD_WRITE: String = "write"

    /** Plaud notify characteristic name (2BB0). */
    const val PLAUD_NOTIFY: String = "notify"

    val Plaud: GattProfile = GattProfile(
        id = "plaud",
        displayName = "Plaud",
        serviceUuid = PlaudUuids.SERVICE,
        preferredMtu = 512,
        minUsefulMtu = 23,
        characteristics = listOf(
            GattCharacteristic(
                name = PLAUD_WRITE,
                uuid = PlaudUuids.WRITE,
                canWrite = true,
            ),
            GattCharacteristic(
                name = PLAUD_NOTIFY,
                uuid = PlaudUuids.NOTIFY,
                canNotify = true,
            ),
        ),
    )

    const val OMI_AUDIO_DATA: String = "audio_data"
    const val OMI_AUDIO_CODEC: String = "audio_codec"
    const val OMI_LIGHT_CODEC: String = "light_codec"

    val Omi: GattProfile = GattProfile(
        id = "omi",
        displayName = "Omi / Friend",
        serviceUuid = OmiUuids.SERVICE,
        preferredMtu = 512,
        minUsefulMtu = 23,
        characteristics = listOf(
            GattCharacteristic(
                name = OMI_AUDIO_DATA,
                uuid = OmiUuids.AUDIO_DATA,
                canNotify = true,
            ),
            GattCharacteristic(
                name = OMI_AUDIO_CODEC,
                uuid = OmiUuids.AUDIO_CODEC,
                canNotify = true,
            ),
            GattCharacteristic(
                name = OMI_LIGHT_CODEC,
                uuid = OmiUuids.LIGHT_CODEC,
                canNotify = true,
            ),
        ),
    )

    const val POCKET_CMD_WRITE: String = "cmd_write"
    const val POCKET_CMD_NOTIFY: String = "cmd_notify"
    const val POCKET_AUDIO_NOTIFY: String = "audio_notify"

    val Pocket: GattProfile = GattProfile(
        id = "pocket",
        displayName = "Pocket",
        serviceUuid = PocketUuids.AUDIO_SERVICE,
        preferredMtu = 512,
        minUsefulMtu = 23,
        characteristics = listOf(
            GattCharacteristic(
                name = POCKET_CMD_WRITE,
                uuid = PocketUuids.AUDIO_WRITE,
                canWrite = true,
            ),
            GattCharacteristic(
                name = POCKET_CMD_NOTIFY,
                uuid = PocketUuids.AUDIO_META,
                canWrite = true,
                canNotify = true,
            ),
            GattCharacteristic(
                name = POCKET_AUDIO_NOTIFY,
                uuid = PocketUuids.AUDIO_DATA,
                canNotify = true,
            ),
        ),
    )

    const val LIMITLESS_TX: String = "tx"
    const val LIMITLESS_RX: String = "rx"

    /**
     * Limitless Pendant dual-characteristic profile.
     *
     * UUIDs: [OmiUuids.LIMITLESS_SERVICE] / [OmiUuids.LIMITLESS_TX] /
     * [OmiUuids.LIMITLESS_RX] from BasedHardware/omi `models.dart`.
     */
    val Limitless: GattProfile = GattProfile(
        id = "limitless",
        displayName = "Limitless Pendant",
        serviceUuid = OmiUuids.LIMITLESS_SERVICE,
        preferredMtu = 512,
        minUsefulMtu = 23,
        characteristics = listOf(
            GattCharacteristic(
                name = LIMITLESS_TX,
                uuid = OmiUuids.LIMITLESS_TX,
                canWrite = true,
            ),
            GattCharacteristic(
                name = LIMITLESS_RX,
                uuid = OmiUuids.LIMITLESS_RX,
                canNotify = true,
            ),
        ),
    )

    val All: List<GattProfile> = listOf(Plaud, Omi, Pocket, Limitless)

    fun byId(id: String): GattProfile =
        All.firstOrNull { it.id == id }
            ?: error("Unknown GATT profile id '$id'")
}
