package dev.velum.ble

import dev.velum.ble.gatt.GattChar
import dev.velum.ble.gatt.GattLink
import dev.velum.ble.gatt.GattProfiles
import dev.velum.core.model.ConnectedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform BLE transport. Android/iOS use Kable; desktop is stubbed for now.
 */
interface BleTransport : GattLink {
    val isAvailable: Boolean
    val connectionState: StateFlow<BleConnectionState>
    val discoveredDevices: StateFlow<List<ConnectedDevice>>

    fun advertisements(): Flow<ConnectedDevice>

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(deviceId: String)
    suspend fun disconnect()

    /** Notifications from the Plaud notify characteristic (2BB0). */
    fun notifications(): Flow<ByteArray>

    /**
     * Notifications of [char] for the connected profile. Default: only Plaud's 2BB0 stream exists; a transport
     * that connects other profiles overrides this (the Kable transport does not yet: needs a device to verify).
     */
    fun notifications(char: GattChar): Flow<ByteArray> =
        if (char == GattProfiles.PLAUD_NOTIFY) notifications() else emptyFlow()
}

sealed class BleConnectionState {
    data object Idle : BleConnectionState()
    data object Scanning : BleConnectionState()
    data class Connecting(val deviceId: String) : BleConnectionState()
    data class Connected(val device: ConnectedDevice) : BleConnectionState()
    data class Failed(val message: String) : BleConnectionState()
}

/** Factory used by the Compose shell. */
expect fun createBleTransport(): BleTransport
