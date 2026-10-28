package dev.velum.ble.fake

import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.ble.GattProfile
import dev.velum.ble.GattProfiles
import dev.velum.ble.PlaudWriteGuard
import dev.velum.core.model.ConnectedDevice
import dev.velum.protocol.Bytes
import dev.velum.protocol.DeviceModel
import dev.velum.protocol.PlaudUuids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * In-process [BleTransport] for CI.
 *
 * When connected with [GattProfiles.Plaud] and a [FakePlaudPeripheral] is
 * provided, Plaud write/notify are forwarded to that peripheral. Writes are
 * always recorded per characteristic name for multi-GATT tests.
 */
class FakeBleTransport(
    private val peripheral: FakePlaudPeripheral? = null,
    private val deviceId: String = "fake-note-pro",
    private val serial: String = "881FAKE1",
    private val name: String = "PLAUD NOTE PRO",
) : BleTransport {
    override val isAvailable: Boolean = true

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val device = ConnectedDevice(
        id = deviceId,
        name = name,
        serial = serial,
        modelCode = DeviceModel.NotePro.code,
        protocolVersion = 20,
        rssi = -42,
    )

    private val _devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    override var activeProfile: GattProfile? = null
        private set

    /** All accepted writes: characteristic name → payload copies, in order. */
    private val _writes = mutableListOf<Pair<String, ByteArray>>()
    val recordedWrites: List<Pair<String, ByteArray>>
        get() = _writes.map { it.first to it.second.copyOf() }

    private val notifyFlows =
        mutableMapOf<String, MutableSharedFlow<ByteArray>>()

    override fun advertisements(): Flow<ConnectedDevice> = emptyFlow()

    override suspend fun startScan() {
        _state.value = BleConnectionState.Scanning
        _devices.value = listOf(device)
    }

    override suspend fun stopScan() {
        if (_state.value is BleConnectionState.Scanning) {
            _state.value = BleConnectionState.Idle
        }
    }

    override suspend fun connect(deviceId: String, profile: GattProfile) {
        peripheral?.resetUnlock()
        activeProfile = profile
        _writes.clear()
        notifyFlows.clear()
        for (ch in profile.notifyCharacteristics()) {
            notifyFlows[ch.name] = MutableSharedFlow(extraBufferCapacity = 128)
        }
        _state.value = BleConnectionState.Connecting(deviceId)
        _state.value = BleConnectionState.Connected(device)
    }

    override suspend fun disconnect() {
        peripheral?.resetUnlock()
        activeProfile = null
        notifyFlows.clear()
        _state.value = BleConnectionState.Idle
    }

    override suspend fun write(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean,
    ) {
        check(_state.value is BleConnectionState.Connected) { "Not connected" }
        val profile = activeProfile ?: error("No active GATT profile")
        profile.characteristic(characteristic) // validate name
        PlaudWriteGuard.maybeRequireAllowed(profile, characteristic, payload)
        _writes += characteristic to payload.copyOf()
        if (
            profile.id == GattProfiles.Plaud.id &&
            characteristic == GattProfiles.PLAUD_WRITE &&
            peripheral != null
        ) {
            peripheral.onWrite(payload)
        }
    }

    override fun notifications(characteristic: String): Flow<ByteArray> {
        val profile = activeProfile
        if (
            profile?.id == GattProfiles.Plaud.id &&
            characteristic == GattProfiles.PLAUD_NOTIFY &&
            peripheral != null
        ) {
            return peripheral.notifications
        }
        return notifyFlows[characteristic]?.asSharedFlow()
            ?: emptyFlow()
    }

    /** Emit a fake notification on [characteristic] (non-Plaud peripheral tests). */
    suspend fun emitNotification(characteristic: String, payload: ByteArray) {
        val flow = notifyFlows[characteristic]
            ?: error("No notify flow for '$characteristic' — connect with a matching profile first")
        flow.emit(payload.copyOf())
    }

    fun clearRecordedWrites() {
        _writes.clear()
    }

    companion object {
        /** Build 0x005D manufacturer payload: model @1, serial @9, protocol @18. */
        fun noteProManufacturerData(
            serial: String = "881FAKE1",
            protocolVersion: Int = 20,
        ): ByteArray {
            val data = ByteArray(20)
            // byte 0 often holds a flags/type nibble in captures; leave 0.
            Bytes.u16Le(DeviceModel.NotePro.code).copyInto(data, 1)
            serial.encodeToByteArray().copyInto(data, 9, 0, minOf(8, serial.length))
            Bytes.u16Le(protocolVersion).copyInto(data, 18)
            check(PlaudUuids.MANUFACTURER_ID == 0x005D)
            return data
        }
    }
}
