package dev.velum.protocol

/**
 * GATT identifiers for Plaud BLE devices (Tinnotech SDK).
 *
 * Source: community reverse-engineering of Plaud Note Pro
 * (plaude-archiver / OpenPlod). See docs/protocol/.
 */
object PlaudUuids {
    const val SERVICE = "00001910-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "00002bb0-0000-1000-8000-00805f9b34fb"
    const val WRITE = "00002bb1-0000-1000-8000-00805f9b34fb"
    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

    /** Manufacturer company id used in advertisements. */
    const val MANUFACTURER_ID = 0x005D
}
