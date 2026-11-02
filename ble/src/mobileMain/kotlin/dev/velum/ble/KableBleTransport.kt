package dev.velum.ble

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import dev.velum.core.model.ConnectedDevice
import dev.velum.protocol.PlaudAdvertisement
import dev.velum.protocol.PlaudUuids
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Kable-backed BLE transport for Android and iOS.
 * Desktop uses [StubBleTransport] until a SimpleBLE (or similar) binding exists.
 */
@OptIn(ExperimentalUuidApi::class)
class KableBleTransport : BleTransport {
    override val isAvailable: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    private val advertisementFlow = MutableSharedFlow<ConnectedDevice>(extraBufferCapacity = 64)
    private val notifyFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)

    private var scanJob: Job? = null
    private var peripheral: Peripheral? = null
    private var notifyJob: Job? = null
    private var disconnectWatchJob: Job? = null
    private val seen = mutableMapOf<String, Advertisement>()

    private val writeCharacteristic = characteristicOf(
        service = Uuid.parse(PlaudUuids.SERVICE),
        characteristic = Uuid.parse(PlaudUuids.WRITE),
    )
    private val notifyCharacteristic = characteristicOf(
        service = Uuid.parse(PlaudUuids.SERVICE),
        characteristic = Uuid.parse(PlaudUuids.NOTIFY),
    )

    private val scanner = Scanner {
        filters {
            match {
                services = listOf(Uuid.parse(PlaudUuids.SERVICE))
            }
            match {
                manufacturerData = listOf(
                    com.juul.kable.Filter.ManufacturerData(
                        id = PlaudUuids.MANUFACTURER_ID,
                        data = null,
                        dataMask = null,
                    ),
                )
            }
        }
    }

    override fun advertisements(): Flow<ConnectedDevice> = advertisementFlow.asSharedFlow()

    override suspend fun startScan() {
        stopScan()
        _state.value = BleConnectionState.Scanning
        _devices.value = emptyList()
        seen.clear()
        scanJob = scope.launch {
            try {
                scanner.advertisements.collect { adv ->
                    // The scanner also matches on company id alone; drop those whose payload is not Plaud.
                    val device = adv.toConnectedDevice() ?: return@collect
                    seen[device.id] = adv
                    _devices.update { current ->
                        val without = current.filterNot { it.id == device.id }
                        (without + device).sortedByDescending { it.rssi ?: Int.MIN_VALUE }
                    }
                    advertisementFlow.emit(device)
                }
            } catch (t: Throwable) {
                _state.value = BleConnectionState.Failed(t.message ?: t.toString())
            }
        }
    }

    override suspend fun stopScan() {
        scanJob?.cancelAndJoin()
        scanJob = null
        if (_state.value is BleConnectionState.Scanning) {
            _state.value = BleConnectionState.Idle
        }
    }

    override suspend fun connect(deviceId: String) {
        val adv = seen[deviceId]
            ?: run {
                _state.value = BleConnectionState.Failed("Unknown device $deviceId — scan first")
                return
            }
        // Validate before opening a peripheral so a bad advert never leaks a connection or notify job.
        val device = adv.toConnectedDevice() ?: run {
            _state.value = BleConnectionState.Failed("Device $deviceId is not a Plaud recorder")
            return
        }
        stopScan()
        _state.value = BleConnectionState.Connecting(deviceId)
        try {
            peripheral?.close()
            val p = Peripheral(adv)
            peripheral = p
            p.connect()
            notifyJob?.cancel()
            notifyJob = scope.launch {
                p.observe(notifyCharacteristic).collect { notifyFlow.emit(it) }
            }
            _state.value = BleConnectionState.Connected(device)
            disconnectWatchJob?.cancel()
            disconnectWatchJob = scope.launch {
                p.state
                    .filter { it is State.Disconnected }
                    .collect {
                        notifyJob?.cancel()
                        notifyJob = null
                        if (peripheral === p) {
                            runCatching { p.close() }
                            peripheral = null
                        }
                        _state.value = BleConnectionState.Idle
                    }
            }
        } catch (t: Throwable) {
            _state.value = BleConnectionState.Failed(t.message ?: t.toString())
        }
    }

    override suspend fun disconnect() {
        disconnectWatchJob?.cancelAndJoin()
        disconnectWatchJob = null
        notifyJob?.cancelAndJoin()
        notifyJob = null
        runCatching { peripheral?.disconnect() }
        peripheral?.close()
        peripheral = null
        _state.value = BleConnectionState.Idle
    }

    override suspend fun write(payload: ByteArray, withoutResponse: Boolean) {
        val p = peripheral ?: error("Not connected")
        val type = if (withoutResponse) WriteType.WithoutResponse else WriteType.WithResponse
        p.write(writeCharacteristic, payload, type)
    }

    override fun notifications(): Flow<ByteArray> = notifyFlow.asSharedFlow()

    /** Null when the advert is neither a Plaud GATT service advert nor valid Plaud manufacturer data. */
    private fun Advertisement.toConnectedDevice(): ConnectedDevice? {
        val parsed = manufacturerData(PlaudUuids.MANUFACTURER_ID)
            ?.let { PlaudAdvertisement.parse(PlaudUuids.MANUFACTURER_ID, it) }
        val hasPlaudService = uuids.contains(Uuid.parse(PlaudUuids.SERVICE))
        if (parsed == null && !hasPlaudService) return null
        return ConnectedDevice(
            id = identifier.toString(),
            name = name ?: peripheralName,
            serial = parsed?.serial,
            modelCode = parsed?.modelCode,
            protocolVersion = parsed?.protocolVersion,
            rssi = rssi,
        )
    }
}

actual fun createBleTransport(): BleTransport = KableBleTransport()
