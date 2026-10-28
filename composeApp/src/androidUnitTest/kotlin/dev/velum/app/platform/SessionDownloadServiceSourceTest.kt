package dev.velum.app.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source-level guards for SessionDownloadService / manifest wiring (#85).
 */
class SessionDownloadServiceSourceTest {
    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/androidMain/kotlin/dev/velum/app/platform/SessionDownloadService.kt"),
            File("composeApp/src/androidMain/kotlin/dev/velum/app/platform/SessionDownloadService.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("SessionDownloadService.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun manifestSource(): String {
        val candidates = listOf(
            File("src/androidMain/AndroidManifest.xml"),
            File("composeApp/src/androidMain/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun managerSource(): String {
        val candidates = listOf(
            File("src/commonMain/kotlin/dev/velum/app/download/SessionDownloadManager.kt"),
            File("composeApp/src/commonMain/kotlin/dev/velum/app/download/SessionDownloadManager.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("SessionDownloadManager.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun viewModelSource(): String {
        val candidates = listOf(
            File("src/commonMain/kotlin/dev/velum/app/ui/DeviceViewModel.kt"),
            File("composeApp/src/commonMain/kotlin/dev/velum/app/ui/DeviceViewModel.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("DeviceViewModel.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun usesConnectedDeviceForegroundTypeOnApi29Plus() {
        val src = serviceSource()
        assertTrue(src.contains("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"))
        assertTrue(src.contains("SDK_INT >= 29"))
        assertTrue(src.contains("promoteToForeground"))
        assertTrue(src.contains("DownloadProgressNotifier.buildNotification"))
        assertTrue(src.contains("enterMinimalForegroundThenStop"))
    }

    @Test
    fun startPath_checksBluetoothConnectAndHandlesDeniedStart() {
        val src = serviceSource()
        assertTrue(src.contains("hasBluetoothConnectPermission"))
        assertTrue(src.contains("BLUETOOTH_CONNECT"))
        assertTrue(src.contains("startForegroundService denied"))
        assertTrue(src.contains("download continues without FGS"))
        assertTrue(src.contains("stopService"))
    }

    @Test
    fun promoteFailure_alwaysStartForegroundBeforeStopSelf() {
        val src = serviceSource()
        assertTrue(src.contains("enterMinimalForegroundThenStop"))
        assertTrue(src.contains("minimal startForeground failed") || src.contains("minimalForegroundNotification"))
        // Failure branch must not stopSelf without attempting startForeground.
        assertTrue(src.contains("handleStart"))
    }

    @Test
    fun manifestDeclaresConnectedDeviceServiceAndPermission() {
        val manifest = manifestSource()
        assertTrue(manifest.contains("FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(manifest.contains(".platform.SessionDownloadService"))
        assertTrue(manifest.contains("foregroundServiceType=\"connectedDevice\""))
    }

    @Test
    fun doesNotAlterMicOrDataSyncServiceTypes() {
        val manifest = manifestSource()
        assertTrue(manifest.contains("MicRecordingService"))
        assertTrue(manifest.contains("foregroundServiceType=\"microphone\""))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_MICROPHONE"))
    }

    @Test
    fun downloadJobLivesInAppScopedManager_notViewModelScope() {
        val manager = managerSource()
        val vm = viewModelSource()
        assertTrue(manager.contains("class SessionDownloadManager"))
        assertTrue(manager.contains("keepAlive.onDownloadBegin()"))
        assertTrue(manager.contains("keepAlive.onDownloadEnd()"))
        assertFalse(
            vm.contains("viewModelScope.launch") && vm.contains("runDownloadSession"),
            "DeviceViewModel must not own the download job on viewModelScope",
        )
        assertFalse(vm.contains("downloadKeepAlive"))
        assertTrue(vm.contains("sessionDownloads.start(") || vm.contains("sessionDownloads.start"))
        assertTrue(vm.contains("sessionDownloads.cancel()"))
    }
}
