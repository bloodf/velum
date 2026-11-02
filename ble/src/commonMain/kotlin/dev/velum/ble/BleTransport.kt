package dev.velum.ble

import dev.velum.ble.gatt.GattWrite
import dev.velum.core.model.ConnectedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform BLE transport. Android/iOS use Kable; desktop is stubbed for now.
 */
interface BleTransport {
    val isAvailable: Boolean
    val connectionState: StateFlow<BleConnectionState>
    val discoveredDevices: StateFlow<List<ConnectedDevice>>

    fun advertisements(): Flow<ConnectedDevice>

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(deviceId: String)
    suspend fun disconnect()

    /**
     * Raw GATT write. `internal` so only `:ble` can call it; every caller goes through
     * [dev.velum.ble.gatt.GuardedLink], which runs the forbidden-command guard first.
     */
    internal suspend fun writeRaw(write: GattWrite)

    /** Notifications from the Plaud notify characteristic (2BB0). */
    fun notifications(): Flow<ByteArray>
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
