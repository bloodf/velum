package dev.velum.ble

import dev.velum.core.model.ConnectedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * No-op transport for desktop JVM and for UI wiring before permissions/hardware.
 */
class StubBleTransport(
    override val isAvailable: Boolean = false,
    private val unavailableReason: String = "BLE is not available on this platform yet",
) : AbstractBleTransport() {
    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    private var _activeProfile: GattProfile? = null
    override val activeProfile: GattProfile? get() = _activeProfile

    override fun advertisements(): Flow<ConnectedDevice> = emptyFlow()

    override suspend fun startScan() {
        _state.value = BleConnectionState.Failed(unavailableReason)
    }

    override suspend fun stopScan() {
        if (_state.value is BleConnectionState.Scanning) {
            _state.value = BleConnectionState.Idle
        }
    }

    override suspend fun connect(deviceId: String, profile: GattProfile) {
        // Connect never succeeds on the stub — do not publish an active profile.
        _activeProfile = null
        _state.value = BleConnectionState.Failed(unavailableReason)
    }

    override suspend fun disconnect() {
        _activeProfile = null
        _state.value = BleConnectionState.Idle
    }

    override suspend fun write(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean,
    ) {
        val profile = activeProfile
        if (profile != null) {
            val ch = profile.characteristic(characteristic)
            PlaudWriteGuard.requireAllowedIfPlaudWrite(
                serviceUuid = profile.resolveServiceUuid(ch),
                characteristicUuid = ch.uuid,
                payload = payload,
            )
        }
        error(unavailableReason)
    }

    internal override suspend fun writeSealedAlreadyGuarded(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean,
    ) {
        error(unavailableReason)
    }

    override fun notifications(characteristic: String): Flow<ByteArray> = flowOf()
}
