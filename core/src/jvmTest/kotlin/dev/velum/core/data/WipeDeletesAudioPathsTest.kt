package dev.velum.core.data

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WipeDeletesAudioPathsTest {
    @Test
    fun wipe_invokes_deleteLocalFile_for_each_recording_path() = runTest {
        val handle = createDatabase(DriverFactory())
        val repo = VelumRepository(handle)
        repo.insertRecording(
            id = "wipe-audio-1",
            sessionId = null,
            folderId = null,
            title = "Wav",
            source = "phone",
            deviceSerial = null,
            startedAt = 1,
            durationSeconds = 1,
            sizeBytes = 10,
            localPath = "/tmp/velum-wipe-test-1.wav",
            transcript = null,
            summaryMarkdown = null,
            status = "ready",
        )
        val deleted = mutableListOf<String>()
        repo.wipeAllUserData { path -> deleted += path; true }
        assertTrue(
            deleted.contains("/tmp/velum-wipe-test-1.wav"),
            "wipe must delete on-disk audio paths; got $deleted",
        )
    }
}
