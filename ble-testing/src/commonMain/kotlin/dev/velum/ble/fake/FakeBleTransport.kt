package dev.velum.ble.fake

import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.core.model.ConnectedDevice
import dev.velum.protocol.Bytes
import dev.velum.protocol.DeviceModel
import dev.velum.protocol.PlaudUuids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * In-process [BleTransport] backed by [FakePlaudPeripheral] for CI.
 *
 * Advertises Note Pro manufacturer data: company 0x005D, project 881, port 20.
 */
class FakeBleTransport(
    private val peripheral: FakePlaudPeripheral,
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

    override suspend fun connect(deviceId: String) {
        peripheral.resetUnlock()
        _state.value = BleConnectionState.Connecting(deviceId)
        _state.value = BleConnectionState.Connected(device)
    }

    override suspend fun disconnect() {
        peripheral.resetUnlock()
        _state.value = BleConnectionState.Idle
    }

    override suspend fun write(payload: ByteArray, withoutResponse: Boolean) {
        check(_state.value is BleConnectionState.Connected) { "Not connected" }
        peripheral.onWrite(payload)
    }

    override fun notifications(): Flow<ByteArray> = peripheral.notifications

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
