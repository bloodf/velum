package dev.velum.ble

import kotlinx.coroutines.flow.Flow

/**
 * Thin Plaud-facing wrapper over a generic [BleTransport].
 *
 * Preserves the historical `write(payload)` / `notifications()` shape used by
 * [dev.velum.ble.client.PlaudDeviceClient] while routing I/O through the Plaud
 * [GattProfiles.Plaud] characteristic names.
 */
class PlaudBleTransport(
    private val transport: BleTransport,
) {
    val underlying: BleTransport get() = transport

    suspend fun connect(deviceId: String) {
        transport.connect(deviceId, GattProfiles.Plaud)
    }

    suspend fun write(payload: ByteArray, withoutResponse: Boolean = false) {
        transport.write(GattProfiles.PLAUD_WRITE, payload, withoutResponse)
    }

    fun notifications(): Flow<ByteArray> =
        transport.notifications(GattProfiles.PLAUD_NOTIFY)
}
