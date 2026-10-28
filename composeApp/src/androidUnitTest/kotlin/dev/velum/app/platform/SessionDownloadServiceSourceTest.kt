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
    fun usesConnectedDeviceWithShortServiceFallback() {
        val src = serviceSource()
        assertTrue(src.contains("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"))
        assertTrue(src.contains("FOREGROUND_SERVICE_TYPE_SHORT_SERVICE"))
        assertTrue(src.contains("enterShortServiceForegroundThenStop"))
        assertTrue(src.contains("promoteFailureHandler"))
    }

    @Test
    fun startPath_checksBluetoothConnectBeforeStartForegroundService() {
        val src = serviceSource()
        assertTrue(src.contains("hasBluetoothConnectPermission"))
        assertTrue(src.contains("DeniedBluetooth"))
        assertTrue(src.contains("BLUETOOTH_CONNECT"))
        assertTrue(src.contains("stopService"))
    }

    @Test
    fun manifestDeclaresConnectedDeviceAndShortService() {
        val manifest = manifestSource()
        assertTrue(manifest.contains("FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_SHORT_SERVICE"))
        assertTrue(manifest.contains(".platform.SessionDownloadService"))
        assertTrue(manifest.contains("connectedDevice|shortService"))
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
    fun downloadJobLivesInAppScopedManager_withCancelAndJoin() {
        val manager = managerSource()
        val vm = viewModelSource()
        assertTrue(manager.contains("class SessionDownloadManager"))
        assertTrue(manager.contains("cancelAndJoin"))
        assertTrue(manager.contains("keepAlive.onDownloadEnd()"))
        assertFalse(vm.contains("downloadKeepAlive"))
        assertTrue(vm.contains("sessionDownloads.cancelAndJoin()"))
    }
}
