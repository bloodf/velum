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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Kable-backed BLE transport for Android and iOS.
 * Desktop uses [StubBleTransport] until a SimpleBLE (or similar) binding exists.
 *
 * Characteristic UUIDs come from the [GattProfile] passed to [connect].
 */
@OptIn(ExperimentalUuidApi::class)
class KableBleTransport : BleTransport {
    override val isAvailable: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<ConnectedDevice>> = _devices.asStateFlow()

    override var activeProfile: GattProfile? = null
        private set

    private val advertisementFlow = MutableSharedFlow<ConnectedDevice>(extraBufferCapacity = 64)
    private val notifyFlows =
        mutableMapOf<String, MutableSharedFlow<ByteArray>>()

    private var scanJob: Job? = null
    private var peripheral: Peripheral? = null
    private var notifyJobs: List<Job> = emptyList()
    private var disconnectWatchJob: Job? = null
    private val seen = mutableMapOf<String, Advertisement>()

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
            match {
                services = listOf(Uuid.parse(GattProfiles.Omi.serviceUuid))
            }
            match {
                services = listOf(Uuid.parse(GattProfiles.Pocket.serviceUuid))
            }
            match {
                services = listOf(Uuid.parse(GattProfiles.Limitless.serviceUuid))
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
                    val device = adv.toConnectedDevice()
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

    override suspend fun connect(deviceId: String, profile: GattProfile) {
        val adv = seen[deviceId]
            ?: run {
                _state.value = BleConnectionState.Failed("Unknown device $deviceId — scan first")
                return
            }
        stopScan()
        _state.value = BleConnectionState.Connecting(deviceId)
        try {
            peripheral?.close()
            clearNotifyJobs()
            val p = Peripheral(adv)
            peripheral = p
            activeProfile = profile
            p.connect()
            // preferredMtu is recorded on the profile for drivers; Kable negotiates
            // ATT MTU via its own defaults / platform hooks.
            notifyFlows.clear()
            val jobs = mutableListOf<Job>()
            for (ch in profile.notifyCharacteristics()) {
                val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
                notifyFlows[ch.name] = flow
                val kableChar = characteristicOf(
                    service = Uuid.parse(profile.resolveServiceUuid(ch)),
                    characteristic = Uuid.parse(ch.uuid),
                )
                jobs += scope.launch {
                    p.observe(kableChar).collect { flow.emit(it) }
                }
            }
            notifyJobs = jobs
            val device = adv.toConnectedDevice()
            _state.value = BleConnectionState.Connected(device)
            disconnectWatchJob?.cancel()
            disconnectWatchJob = scope.launch {
                p.state
                    .filter { it is State.Disconnected }
                    .collect {
                        clearNotifyJobs()
                        if (peripheral === p) {
                            runCatching { p.close() }
                            peripheral = null
                        }
                        activeProfile = null
                        notifyFlows.clear()
                        _state.value = BleConnectionState.Idle
                    }
            }
        } catch (t: Throwable) {
            activeProfile = null
            _state.value = BleConnectionState.Failed(t.message ?: t.toString())
        }
    }

    override suspend fun disconnect() {
        disconnectWatchJob?.cancelAndJoin()
        disconnectWatchJob = null
        clearNotifyJobs()
        runCatching { peripheral?.disconnect() }
        peripheral?.close()
        peripheral = null
        activeProfile = null
        notifyFlows.clear()
        _state.value = BleConnectionState.Idle
    }

    override suspend fun write(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean,
    ) {
        val p = peripheral ?: error("Not connected")
        val profile = activeProfile ?: error("No active GATT profile")
        val ch = profile.characteristic(characteristic)
        require(ch.canWrite) {
            "Characteristic '${ch.name}' on profile '${profile.id}' is not writable"
        }
        PlaudWriteGuard.maybeRequireAllowed(profile, characteristic, payload)
        val kableChar = characteristicOf(
            service = Uuid.parse(profile.resolveServiceUuid(ch)),
            characteristic = Uuid.parse(ch.uuid),
        )
        val type = if (withoutResponse) WriteType.WithoutResponse else WriteType.WithResponse
        p.write(kableChar, payload, type)
    }

    override fun notifications(characteristic: String): Flow<ByteArray> =
        notifyFlows[characteristic]?.asSharedFlow() ?: emptyFlow()

    private suspend fun clearNotifyJobs() {
        notifyJobs.forEach { it.cancel() }
        notifyJobs.forEach { it.join() }
        notifyJobs = emptyList()
    }

    private fun Advertisement.toConnectedDevice(): ConnectedDevice {
        val mfg = manufacturerData(PlaudUuids.MANUFACTURER_ID)
            ?: manufacturerData?.data
        val parsed = mfg?.let { PlaudAdvertisement.parseManufacturerData(it) }
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
