@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*
import platform.Foundation.*
import platform.posix.*

class IosInboxIntakeTest {
    private val files = NSFileManager.defaultManager

    private fun fixture(test: (String, String, NSUserDefaults) -> Unit) {
        val root = NSURL.fileURLWithPath(NSTemporaryDirectory()).URLByResolvingSymlinksInPath!!.path + "/inbox-test-${NSUUID().UUIDString}"
        val defaults = NSUserDefaults(suiteName = NSUUID().UUIDString)
        files.createDirectoryAtPath("$root/Inbox", true, null, null)
        files.createDirectoryAtPath("$root/imports", true, null, null)
        try { test("$root/Inbox", "$root/imports", defaults) } finally { files.removeItemAtPath(root, null) }
    }

    private fun file(path: String): String {
        val fd = open(path, O_WRONLY or O_CREAT or O_EXCL, 384)
        check(fd >= 0)
        check(ftruncate(fd, 7) == 0)
        close(fd)
        return NSURL.fileURLWithPath(path).absoluteString!!
    }

    @Test fun startupWaitsThenOffersOnceAndRecoversWithFreshConsent() = fixture { source, cache, defaults ->
        runTest {
            val intake = IosInboxIntake(source, cache, defaults)
            val waiting = async { intake.next() }
            runCurrent()
            assertFalse(waiting.isCompleted)
            val url = file("$source/audio.wav")
            assertTrue(intake.receive(url))
            val offered = waiting.await() as SharedFile.Ready
            assertNull(offered.recoveredAtMs)
            assertTrue(isImportFixture(offered.path, cache))
            assertFalse(intake.receive(url))
            val recovery = IosInboxIntake(source, cache, defaults)
            val restored = recovery.next() as SharedFile.Ready
            assertEquals(offered.path, restored.path)
            assertNotNull(restored.recoveredAtMs)
            val consent = ExternalShareConsent()
            var starts = 0
            val approval = async { consent.runIfApproved { starts++ } }
            runCurrent()
            assertEquals(0, starts)
            consent.decide(false)
            assertFalse(approval.await())
            recovery.handled(restored.path)
            assertFalse(files.fileExistsAtPath(restored.path))
            assertTrue(files.fileExistsAtPath("$source/audio.wav"))
            assertFalse(recovery.receive(url))
        }
    }

    private fun isImportFixture(path: String, cache: String) = path.startsWith("$cache/inbox-")

    @Test fun rejectsExternalLinksTraversalAndRemoteUrls() = fixture { source, cache, defaults ->
        val intake = IosInboxIntake(source, cache, defaults)
        val outside = file("$cache/foreign.wav")
        assertFalse(intake.receive(outside))
        assertFalse(intake.receive("https://example.com/audio.wav"))
        assertFalse(intake.receive("file://remote$source/audio.wav"))
        files.createSymbolicLinkAtPath("$source/link.wav", "$cache/foreign.wav", null)
        assertFalse(intake.receive(NSURL.fileURLWithPath("$source/link.wav").absoluteString!!))
        assertFalse(intake.receive("file://$source/../imports/foreign.wav"))
        assertTrue(files.fileExistsAtPath("$cache/foreign.wav"))
    }

    @Test fun clearRejectsLateDeliveryAndStaleAttemptAndCancelledWaitCanResume() = fixture { source, cache, defaults ->
        runTest {
            val intake = IosInboxIntake(source, cache, defaults)
            val waiter = async { intake.next() }
            runCurrent()
            waiter.cancel()
            val generation = intake.generation()
            val url = file("$source/audio.wav")
            intake.clear()
            assertFalse(intake.receive(url, generation))
            assertTrue(intake.receive(url))
            val ready = intake.next() as SharedFile.Ready
            intake.clear()
            intake.handled(ready.path)
            assertFailsWith<IllegalStateException> { intake.attempt(ready.path) }
            assertFalse(intake.receive(url))
            assertFalse(files.fileExistsAtPath(ready.path))
        }
    }

    @Test fun threeAttemptsStopRecoveryAndQueueIsBounded() = fixture { source, cache, defaults ->
        runTest {
            val intake = IosInboxIntake(source, cache, defaults)
            repeat(8) { assertTrue(intake.receive(file("$source/$it.wav"))) }
            assertFalse(intake.receive(file("$source/overflow.wav")))
            val ready = intake.next() as SharedFile.Ready
            repeat(3) { intake.attempt(ready.path) }
            val restart = IosInboxIntake(source, cache, defaults)
            assertEquals(SharedFile.CopyFailed, restart.next())
            assertFalse(files.fileExistsAtPath(ready.path))
            restart.clear()
        }
    }

    @Test fun copyLimitIsInclusiveAndExclusiveDestinationPreservesForeignData() = fixture { source, cache, _ ->
        file("$source/audio.wav")
        copyInboxFile("$source/audio.wav", "$cache/exact.wav", 7)
        assertFails { copyInboxFile("$source/audio.wav", "$cache/too-big.wav", 6) }
        assertFalse(files.fileExistsAtPath("$cache/too-big.wav"))
        assertFails { copyInboxFile("$source/audio.wav", "$cache/exact.wav", 7) }
        assertEquals(7L, (files.attributesOfItemAtPath("$cache/exact.wav", null)!![NSFileSize] as NSNumber).longLongValue)
    }
}
