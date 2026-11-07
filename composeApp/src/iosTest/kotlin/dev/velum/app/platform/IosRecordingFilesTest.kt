@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask

class IosRecordingFilesTest {
    private val files = NSFileManager.defaultManager

    private fun fixture(root: String, test: (String) -> Unit) {
        val directory = "$root/fixture-${NSUUID().UUIDString}"
        assertTrue(files.createDirectoryAtPath(directory, true, null, null))
        try {
            test(directory)
        } finally {
            files.removeItemAtPath(directory, null)
        }
    }

    private fun file(path: String) {
        assertTrue(files.createFileAtPath(path, NSData(), null))
    }

    @Test
    fun recordingsAreDurableAndDeletionOnlyRemovesFiles() {
        val root = recordingsDirectoryPath()
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        assertTrue(root.startsWith("$support/"))
        fixture(root) { dir ->
            val audio = "$dir/audio.wav"
            file(audio)
            assertTrue(isAppOwnedAudioPath(audio))
            assertFalse(deleteFileAtPath(dir))
            assertTrue(deleteFileAtPath(audio))
            assertFalse(deleteFileAtPath(audio))
            assertTrue(deleteFileIfPresent(audio))
        }
    }

    @Test
    fun canonicalIdentityAndSupersededNames() = fixture(recordingsDirectoryPath()) { dir ->
        val old = "$dir/recording.wav"
        val next = "$dir/recording.m4a"
        file(old)
        assertTrue(isSamePath(old, "$dir/./recording.wav"))
        assertTrue(isSamePath(old, "$dir/child/../recording.wav"))
        assertTrue(isSupersededOwnAudio(old, next, dir, "recording"))
        assertFalse(isSupersededOwnAudio(old, old, dir, "recording"))
        assertFalse(isSupersededOwnAudio(old, next, dir, "other"))
        assertFalse(isSupersededOwnAudio(old, next, "$dir/child", "recording"))
        assertFalse(isSamePath("", ""))
    }

    @Test
    fun legacyFilesRemainUsableWithoutMigration() = fixture("/tmp/velum-recordings") { dir ->
        val path = "$dir/legacy.wav"
        file(path)
        assertTrue(isAppOwnedAudioPath(path))
        assertTrue(isSamePath(path, "/private$path"))
        assertFalse(isImportCacheCopy(path))
        assertTrue(deleteFileAtPath(path))
    }

    @Test
    fun traversalAndExternalFilesAreNeverDeleted() = fixture(NSTemporaryDirectory()) { outside ->
        val external = "$outside/external.wav"
        file(external)
        assertFalse(isAppOwnedAudioPath(external))
        assertFalse(deleteFileAtPath(external))
        assertFalse(deleteFileIfPresent(external))
        fixture(recordingsDirectoryPath()) { dir ->
            val link = "$dir/link"
            assertTrue(files.createSymbolicLinkAtPath(link, outside, null))
            assertFalse(isAppOwnedAudioPath("$link/external.wav"))
            assertFalse(deleteFileAtPath("$link/external.wav"))
            assertFalse(deleteFileAtPath(link))
            val traversal = "$dir/../${dir.substringAfterLast('/')}/audio.wav"
            assertFalse(isAppOwnedAudioPath(traversal))
            assertFalse(deleteFileIfPresent(traversal))
            assertFalse(isAppOwnedAudioPath("${recordingsDirectoryPath()}-other/audio.wav"))
        }
        assertTrue(files.fileExistsAtPath(external))
    }
}
