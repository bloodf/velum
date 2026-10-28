package dev.velum.ble

import dev.velum.core.model.ConnectedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform BLE transport. Android/iOS use Kable; desktop is stubbed for now.
 *
 * Connection and I/O are generic over a [GattProfile] so Plaud, Omi, Pocket,
 * and Limitless drivers share one radio stack.
 */
interface BleTransport {
    val isAvailable: Boolean
    val connectionState: StateFlow<BleConnectionState>
    val discoveredDevices: StateFlow<List<ConnectedDevice>>

    /** Active GATT profile after a successful [connect], or null when disconnected. */
    val activeProfile: GattProfile?

    fun advertisements(): Flow<ConnectedDevice>

    suspend fun startScan()
    suspend fun stopScan()

    /**
     * Connect using [profile] for characteristic resolution and MTU hints.
     *
     * Default overload keeps the historical Plaud UI path working without a
     * profile argument.
     */
    suspend fun connect(deviceId: String) = connect(deviceId, GattProfiles.Plaud)

    suspend fun connect(deviceId: String, profile: GattProfile)

    suspend fun disconnect()

    /**
     * Write [payload] to the named characteristic on the active profile.
     *
     * Always runs the Plaud plaintext opcode/settings guard when the target
     * resolves to the Plaud write characteristic UUID (`2BB1`). There is no
     * public opt-out; sealed session traffic must go through
     * [PlaudBleTransport.writeSealedAfterPlaintextGuard].
     */
    suspend fun write(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean = false,
    )

    /** Notifications from the named characteristic on the active profile. */
    fun notifications(characteristic: String): Flow<ByteArray>
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
