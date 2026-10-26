package dev.velum.app.di

import dev.velum.ai.createOpenAiCompatibleClient
import dev.velum.app.ui.DeviceViewModel
import dev.velum.app.ui.SettingsViewModel
import dev.velum.ble.BleTransport
import dev.velum.ble.createBleTransport
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.security.DeviceKeyImporter
import dev.velum.core.security.SecureDeviceKeyImporter
import dev.velum.core.security.SecureStore
import dev.velum.core.settings.SettingsRepository

class AppContainer(
    val secureStore: SecureStore,
    driverFactory: DriverFactory,
    val ble: BleTransport = createBleTransport(),
) {
    val database = createDatabase(driverFactory)
    val repository = VelumRepository(database)
    val settingsRepository = SettingsRepository(secureStore)
    val deviceKeyImporter: DeviceKeyImporter = SecureDeviceKeyImporter(secureStore)
    val aiClient = createOpenAiCompatibleClient()
    val settingsViewModel = SettingsViewModel(settingsRepository, aiClient)
    val deviceViewModel = DeviceViewModel(ble)
}
