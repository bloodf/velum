package dev.velum.app.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source-level guards for MicRecordingService ANR / race ship blockers.
 * Full AudioRecord instrumentation needs a device; these assert the structural fixes.
 */
class MicRecordingServiceSourceTest {
    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/androidMain/kotlin/dev/velum/app/platform/MicRecordingService.kt"),
            File("composeApp/src/androidMain/kotlin/dev/velum/app/platform/MicRecordingService.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("MicRecordingService.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun stopRecording_joinIsNotOnMainPath_usesBackgroundThread() {
        val src = serviceSource()
        // ACTION_STOP must hand off to a background thread before join.
        assertTrue(
            src.contains("velum-mic-stop"),
            "ACTION_STOP should spawn velum-mic-stop thread",
        )
        assertTrue(
            src.contains("thread(name = \"velum-mic-stop\""),
            "stop must not join on the main onStartCommand path",
        )
        // join still exists for the record thread, but only inside stopRecording
        // which is invoked from the stop worker thread.
        assertTrue(src.contains("thread?.join(5_000)") || src.contains("thread?.join(5000)"))
    }

    @Test
    fun startForeground_beforeDiskAndAudioRecordInit() {
        val src = serviceSource()
        assertTrue(src.contains("promoteToForeground"))
        assertTrue(src.contains("velum-mic-init"))
        // beginRecordingAfterForeground does the heavy work after FGS promote.
        assertTrue(src.contains("beginRecordingAfterForeground"))
        val promoteIdx = src.indexOf("fun promoteToForeground")
        val beginIdx = src.indexOf("fun beginRecordingAfterForeground")
        assertTrue(promoteIdx >= 0 && beginIdx >= 0 && promoteIdx < beginIdx)
    }

    @Test
    fun pauseResume_doNotStopOrRestartAudioRecord() {
        val src = serviceSource()
        // Extract pauseRecording / resumeRecording bodies roughly.
        val pauseFn = src.substringAfter("private fun pauseRecording()")
            .substringBefore("private fun resumeRecording()")
        val resumeFn = src.substringAfter("private fun resumeRecording()")
            .substringBefore("private fun stopRecording")
        assertFalse(
            pauseFn.contains("audioRecord?.stop()") || pauseFn.contains("audioRecord!!.stop()"),
            "pause must be flag-only; AudioRecord.stop during read races the session",
        )
        assertFalse(
            resumeFn.contains("startRecording()"),
            "resume must not call startRecording(); AudioRecord stays started",
        )
        assertTrue(pauseFn.contains("paused.compareAndSet(false, true)"))
        assertTrue(resumeFn.contains("paused.compareAndSet(true, false)"))
    }
}
