package dev.velum.app.platform

import java.io.File
import kotlin.test.Test
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

    @Test
    fun usesConnectedDeviceForegroundType() {
        val src = serviceSource()
        assertTrue(src.contains("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"))
        assertTrue(src.contains("promoteToForeground"))
        assertTrue(src.contains("DownloadProgressNotifier.buildNotification"))
    }

    @Test
    fun startPath_handlesAndroid14RestrictionsGracefully() {
        val src = serviceSource()
        assertTrue(src.contains("startForegroundService denied") || src.contains("ForegroundService"))
        assertTrue(src.contains("catch (t: Throwable)"))
        assertTrue(src.contains("download continues without FGS"))
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
}
