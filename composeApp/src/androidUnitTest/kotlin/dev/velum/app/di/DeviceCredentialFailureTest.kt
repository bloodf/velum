package dev.velum.app.di

import androidx.lifecycle.ViewModelStore
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.ui.DeviceViewModel
import dev.velum.ble.BleConnectionState
import dev.velum.ble.BleTransport
import dev.velum.ble.gatt.GattWrite
import dev.velum.core.model.ConnectedDevice
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.core.security.SecureStoreUnavailableException
import dev.velum.protocol.DeviceIdentityKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCredentialFailureTest {
    @Test
    fun credential_presence_failure_is_visible_and_connection_can_retry_without_sending_device_commands() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val ble = SyntheticTransport()
            val importer = SyntheticImporter().apply { failPresence = true }
            val vm = DeviceViewModel(ble, importer, localeTag = { "en" }); owner.put("device", vm)
            vm.connect("synthetic"); runCurrent()
            assertEquals(DeviceUiCopy.secureStorageUnavailable("en"), vm.sessionUi.value.statusMessage)
            assertEquals(0, ble.writes)
            importer.failPresence = false; importer.present = false
            vm.connect("synthetic"); runCurrent()
            assertEquals(DeviceUiCopy.connectedImportKeys("en"), vm.sessionUi.value.statusMessage)
            assertEquals(0, ble.writes)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun credential_decrypt_failure_is_not_misreported_as_incomplete_identity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val ble = SyntheticTransport(); val importer = SyntheticImporter()
            val vm = DeviceViewModel(ble, importer, localeTag = { "pt-BR" }); owner.put("device", vm)
            vm.connect("synthetic"); runCurrent()
            assertEquals(DeviceUiCopy.secureStorageUnavailable("pt-BR"), vm.sessionUi.value.statusMessage)
            assertNotEquals(DeviceUiCopy.connectedKeysIncomplete("pt-BR"), vm.sessionUi.value.statusMessage)
            assertEquals(0, ble.writes)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun credential_cancellation_does_not_become_a_storage_error_or_kill_future_actions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val ble = SyntheticTransport(); val importer = SyntheticImporter().apply { cancelled = true }
            val vm = DeviceViewModel(ble, importer, localeTag = { "en" }); owner.put("device", vm)
            vm.connect("synthetic"); runCurrent()
            assertNotEquals(DeviceUiCopy.secureStorageUnavailable("en"), vm.sessionUi.value.statusMessage)
            importer.cancelled = false; importer.present = false
            vm.connect("synthetic"); runCurrent()
            assertEquals(DeviceUiCopy.connectedImportKeys("en"), vm.sessionUi.value.statusMessage)
            assertEquals(0, ble.writes)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    private class SyntheticImporter : DeviceKeyImporter {
        var failPresence = false
        var present = true
        var cancelled = false
        override suspend fun hasKeys(): Boolean {
            if (failPresence) throw SecureStoreUnavailableException(IllegalStateException("synthetic provider"))
            return present
        }
        override suspend fun loadKeys(): DeviceIdentityKeys? {
            if (cancelled) throw CancellationException("synthetic cancellation")
            throw SecureStoreUnavailableException(IllegalStateException("synthetic decrypt"))
        }
        override suspend fun importKeys(serialNumber: String, serialSignatureBase64: String, bindToken: String, publicKeyPem: String, privateKeyPem: String) = error("Not used")
        override suspend fun clearKeys() = error("Not used")
    }

    private class SyntheticTransport : BleTransport {
        override val isAvailable = true
        override val linkGeneration = 0L
        override val connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
        override val discoveredDevices = MutableStateFlow(emptyList<ConnectedDevice>())
        var writes = 0
        override fun advertisements() = emptyFlow<ConnectedDevice>()
        override fun notifications() = emptyFlow<ByteArray>()
        override suspend fun startScan() = Unit
        override suspend fun stopScan() = Unit
        override suspend fun connect(deviceId: String) {
            connectionState.value = BleConnectionState.Connected(ConnectedDevice("synthetic", "PLAUD NOTE", null, null, null, -40))
        }
        override suspend fun disconnect() { connectionState.value = BleConnectionState.Idle }
        override suspend fun writeRaw(write: GattWrite) { writes++; error("No device command is allowed in this fixture") }
    }
}
