package dev.velum.app.di

import androidx.lifecycle.ViewModelStore
import dev.velum.app.i18n.DeviceUiCopy
import dev.velum.app.ui.DeviceViewModel
import dev.velum.ble.crypto.createFileDecryptor
import dev.velum.ble.crypto.createSessionCipher
import dev.velum.ble.crypto.JcaFileDecryptor
import dev.velum.ble.crypto.rsaKeyPairToPem
import dev.velum.ble.fake.FakeBleTransport
import dev.velum.ble.fake.FakePlaudPeripheral
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.protocol.DeviceIdentityKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Issue #93: battery level and reconnect through the real [DeviceViewModel] application path
 * against the in-process simulated peripheral. Synthetic identity only; says nothing about a
 * real Note Pro (#5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSimulatedPeripheralTest {
    private val pems = rsaKeyPairToPem()
    private val keys = DeviceIdentityKeys(
        serialNumber = "881FAKE1",
        serialSignature = ByteArray(256) { (it % 251).toByte() },
        publicKeyPem = pems.first,
        privateKeyPem = pems.second,
        bindToken = "synthetic-token",
    )

    private class Importer(private val keys: DeviceIdentityKeys) : DeviceKeyImporter {
        override suspend fun hasKeys() = true
        override suspend fun loadKeys() = keys
        override suspend fun importKeys(serialNumber: String, serialSignatureBase64: String, bindToken: String, publicKeyPem: String, privateKeyPem: String) = error("Not used")
        override suspend fun clearKeys() = error("Not used")
    }

    private fun transport(battery: Int, peripheralKeys: DeviceIdentityKeys = keys): FakeBleTransport {
        val file = (createFileDecryptor() as JcaFileDecryptor).encryptFile(pems.first, byteArrayOf(1, 2, 3))
        return FakeBleTransport(
            FakePlaudPeripheral(createSessionCipher(), peripheralKeys, file, batteryPercent = battery, requirePublicKey = false),
        )
    }

    @Test
    fun battery_level_from_simulated_peripheral_is_shown_and_survives_reconnect() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val vm = DeviceViewModel(transport(battery = 73), Importer(keys), localeTag = { "en" })
            owner.put("device", vm)
            vm.connect("fake-note-pro"); advanceUntilIdle()
            assertEquals(73, vm.sessionUi.value.batteryPercent)
            assertEquals(1, vm.sessionUi.value.sessions.size)

            vm.disconnect(); advanceUntilIdle()
            assertNull(vm.sessionUi.value.batteryPercent, "no stale battery after disconnect")

            vm.connect("fake-note-pro"); advanceUntilIdle()
            assertEquals(73, vm.sessionUi.value.batteryPercent)
            assertEquals(1, vm.sessionUi.value.sessions.size)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test
    fun rejected_credentials_show_unlock_failure_and_no_battery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val other = keys.copy(serialSignature = ByteArray(256) { 3 })
            val vm = DeviceViewModel(transport(battery = 73, peripheralKeys = other), Importer(keys), localeTag = { "en" })
            owner.put("device", vm)
            vm.connect("fake-note-pro"); advanceUntilIdle()
            assertNull(vm.sessionUi.value.batteryPercent)
            assertEquals(DeviceUiCopy.unlockFailed("en"), vm.sessionUi.value.statusMessage)
            assertNotNull(vm.sessionUi.value.statusMessage)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}
