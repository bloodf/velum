package dev.velum.app.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicCaptureAndroidSourceTest {
    private fun micCaptureAndroidSource(): String {
        val candidates = listOf(
            File("src/androidMain/kotlin/dev/velum/app/platform/MicCapture.android.kt"),
            File("composeApp/src/androidMain/kotlin/dev/velum/app/platform/MicCapture.android.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("MicCapture.android.kt not found")
        return file.readText()
    }

    @Test
    fun addMarker_timeoutReturnsFailedNotOkZero() {
        val src = micCaptureAndroidSource()
        assertFalse(
            src.contains("MicMarkerResult.Ok(MicMarker(offsetMs = 0"),
            "addMarker timeout must not invent Ok(offset 0)",
        )
        assertTrue(src.contains("MicMarkerResult.Failed(\"Timed out adding marker\")"))
    }
}
