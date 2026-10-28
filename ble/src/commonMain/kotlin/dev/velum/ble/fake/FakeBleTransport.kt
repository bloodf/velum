package dev.velum.ble.fake

import dev.velum.ble.AbstractBleTransport
import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleSealedWriteSink
import dev.velum.ble.GattProfile
import dev.velum.ble.GattProfiles
import dev.velum.ble.PlaudWriteGuard
import dev.velum.core.model.ConnectedDevice
import dev.velum.protocol.AdvertisementFixtures
import dev.velum.protocol.Bytes
import dev.velum.protocol.DeviceModel
import dev.velum.protocol.PlaudAdvertisement
import dev.velum.protocol.PlaudUuids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process [dev.velum.ble.BleTransport] for CI.
 *
 * When connected with [GattProfiles.Plaud] and a [FakePlaudPeripheral] is
 * provided, Plaud write/notify are forwarded to that peripheral. Writes are
 * always recorded per characteristic name for multi-GATT tests.
 *
 * [advertisementFixtures] drive scan discovery: only payloads that parse as
 * Note Pro family discoveries are surfaced (malformed fixtures are skipped).
 *
 * Sealed writes are exposed only via [sealedWriteSink] for
 * [dev.velum.ble.PlaudEncryptedSession.writeEncrypted].
 */
class FakeBleTransport(
    private val peripheral: FakePlaudPeripheral? = null,
    private val deviceId: String = "fake-note-pro",
    private val serial: String = "881FAKE1",
    private val name: String = "Note Pro",
    private val advertisementFixtures: List<ByteArray> = listOf(
        AdvertisementFixtures.notePro881(portVersion = 20, serial = serial),
    ),
) : AbstractBleTransport() {
    override val isAvailable: Boolean = true

    private val mutex = Mutex()

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    private var _activeProfile: GattProfile? = null
    override val activeProfile: GattProfile? get() = _activeProfile

    /** All accepted writes: characteristic name → payload copies, in order. */
    private val _writes = mutableListOf<Pair<String, ByteArray>>()
    val recordedWrites: List<Pair<String, ByteArray>>
        get() = _writes.map { it.first to it.second.copyOf() }

    private val notifyFlows =
        mutableMapOf<String, MutableSharedFlow<ByteArray>>()

    private val advertisementFlow =
        MutableSharedFlow<ConnectedDevice>(extraBufferCapacity = 64)

    override fun advertisements(): Flow<ConnectedDevice> = advertisementFlow.asSharedFlow()

    override suspend fun startScan() {
        val discovered = mutableListOf<ConnectedDevice>()
        advertisementFixtures.forEachIndexed { index, mfg ->
            val parsed = PlaudAdvertisement.parseNoteProFamilyDiscovery(mfg) ?: return@forEachIndexed
            val device = ConnectedDevice(
                id = if (index == 0) deviceId else "$deviceId-$index",
                name = name,
                serial = parsed.serial,
                modelCode = parsed.modelCode,
                protocolVersion = parsed.portVersion,
                rssi = -42 - index,
            )
            discovered += device
            advertisementFlow.emit(device)
        }
        mutex.withLock {
            _state.value = BleConnectionState.Scanning
            _devices.value = discovered
        }
    }

    override suspend fun stopScan() {
        mutex.withLock {
            if (_state.value is BleConnectionState.Scanning) {
                _state.value = BleConnectionState.Idle
            }
        }
    }

    override suspend fun connect(deviceId: String, profile: GattProfile) {
        mutex.withLock {
            peripheral?.resetUnlock()
            _writes.clear()
            notifyFlows.clear()
            for (ch in profile.notifyCharacteristics()) {
                notifyFlows[ch.name] = MutableSharedFlow(extraBufferCapacity = 128)
            }
            val fromScan = _devices.value.firstOrNull { it.id == deviceId }
            val device = fromScan ?: ConnectedDevice(
                id = deviceId,
                name = name,
                serial = serial,
                modelCode = DeviceModel.NotePro.code,
                protocolVersion = 20,
                rssi = -42,
            )
            _state.value = BleConnectionState.Connecting(deviceId)
            // Set profile only after the fake "link" is up.
            _activeProfile = profile
            _state.value = BleConnectionState.Connected(device)
        }
    }

    override suspend fun disconnect() {
        mutex.withLock {
            peripheral?.resetUnlock()
            _activeProfile = null
            notifyFlows.clear()
            _state.value = BleConnectionState.Idle
        }
    }

    override suspend fun write(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean,
    ) {
        mutex.withLock {
            check(_state.value is BleConnectionState.Connected) { "Not connected" }
            val profile = activeProfile ?: error("No active GATT profile")
            val ch = profile.characteristic(characteristic)
            PlaudWriteGuard.requireAllowedIfPlaudWrite(
                serviceUuid = profile.resolveServiceUuid(ch),
                characteristicUuid = ch.uuid,
                payload = payload,
            )
            recordAndForward(characteristic, profile, ch.uuid, payload)
        }
    }

    private val sealedSink = object : BleSealedWriteSink {
        override suspend fun writeSealedAlreadyGuarded(
            characteristic: String,
            payload: ByteArray,
            withoutResponse: Boolean,
        ) {
            mutex.withLock {
                check(_state.value is BleConnectionState.Connected) { "Not connected" }
                val profile = activeProfile ?: error("No active GATT profile")
                val ch = profile.characteristic(characteristic)
                // No opcode parse — caller must have checked plaintext already.
                recordAndForward(characteristic, profile, ch.uuid, payload)
            }
        }
    }

    internal override fun sealedWriteSink(): BleSealedWriteSink = sealedSink

    private suspend fun recordAndForward(
        characteristic: String,
        profile: GattProfile,
        characteristicUuid: String,
        payload: ByteArray,
    ) {
        _writes += characteristic to payload.copyOf()
        if (
            peripheral != null &&
            PlaudWriteGuard.isPlaudWriteTarget(
                profile.resolveServiceUuid(profile.characteristic(characteristic)),
                characteristicUuid,
            )
        ) {
            peripheral.onWrite(payload)
        }
    }

    override fun notifications(characteristic: String): Flow<ByteArray> {
        val profile = activeProfile
        if (profile != null) {
            val ch = runCatching { profile.characteristic(characteristic) }.getOrNull()
            if (
                ch != null &&
                ch.uuid.equals(PlaudUuids.NOTIFY, ignoreCase = true) &&
                profile.resolveServiceUuid(ch).equals(PlaudUuids.SERVICE, ignoreCase = true) &&
                peripheral != null
            ) {
                return peripheral.notifications
            }
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
            val data = AdvertisementFixtures.notePro881(
                portVersion = protocolVersion,
                serial = serial,
            )
            check(PlaudUuids.MANUFACTURER_ID == 0x005D)
            check(Bytes.readU16Le(data, 1) == DeviceModel.NotePro.code)
            return data
        }
    }
}
