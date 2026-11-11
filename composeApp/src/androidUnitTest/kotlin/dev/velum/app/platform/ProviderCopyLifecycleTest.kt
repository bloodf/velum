package dev.velum.app.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderCopyLifecycleTest {
    /** Fake IPC endpoint exercises the production session, watchdog and owned-file lifecycle together. */
    private class FakeIpc(val stage: String, val files: ProviderCopyFiles) {
        val session = ProviderCopySession("request", "app", 1000, 10, 300_000)
        var now = 0L
        var childAlive = true
        var stops = 0
        val watchdog = ProviderCopyDeadline(300_000, { now }) { cancel() }
        init { check(session.hello("request", 1000, 20, "app:provider_copy", 0)) }
        fun cancel() {
            session.abort()
            stops++
            childAlive = false // fake authenticated self-stop; never calls Android Process.killProcess
            files.close()
        }
        fun complete(success: Boolean = true): Boolean {
            session.completed("request", 1000, 20, success, "recording.wav", now)
            childAlive = false
            return session.died(now)
        }
    }

    @Test fun stalledProviderAtEachBoundaryIsReclaimedAndCannotPublishLate() {
        for (stage in listOf("metadata", "open", "read", "close")) {
            val root = Files.createTempDirectory("provider-lifecycle").toFile()
            try {
                val files = ProviderCopyFiles(root, "request", true)
                files.partial.writeBytes(byteArrayOf(1))
                val ipc = FakeIpc(stage, files)
                ipc.now = 300_000
                ipc.watchdog.check()
                assertFalse(ipc.childAlive, stage)
                assertEquals(1, ipc.stops, stage)
                assertFalse(ipc.complete(), stage)
                assertTrue(root.listFiles().orEmpty().isEmpty(), stage)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun cancellationAndUnexpectedDeathDiscardOnlyOwnedPartial() {
        for (cancel in listOf(false, true)) {
            val root = Files.createTempDirectory("provider-cancel").toFile()
            try {
                val foreign = java.io.File(root, "foreign.wav").apply { writeBytes(byteArrayOf(9)) }
                val files = ProviderCopyFiles(root, "request", false)
                files.partial.writeBytes(byteArrayOf(1, 2))
                val ipc = FakeIpc("read", files)
                if (cancel) ipc.cancel() else { assertFalse(ipc.session.died(1)); files.close() }
                assertFalse(ipc.complete())
                assertContentEquals(byteArrayOf(9), foreign.readBytes())
                assertEquals(listOf(foreign), root.listFiles().orEmpty().toList())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun onlyCompleteCopyFromDeadChildIsPublishedAndAccepted() {
        val root = Files.createTempDirectory("provider-success").toFile()
        try {
            val files = ProviderCopyFiles(root, "request", true)
            val bytes = byteArrayOf(1, 2, 3)
            files.partial.writeBytes(bytes)
            val ipc = FakeIpc("finished", files)
            assertTrue(ipc.complete())
            val result = files.publish("recording.wav")
            files.accept()
            files.close()
            assertEquals("share_request.wav", result.file.name)
            assertContentEquals(bytes, result.file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationAfterMoveAndBeforeAcceptanceRemovesPublishedCopy() {
        val root = Files.createTempDirectory("provider-move").toFile()
        try {
            val files = ProviderCopyFiles(root, "request", false)
            files.partial.writeBytes(byteArrayOf(1))
            val result = files.publish("recording.wav")
            files.close()
            assertFalse(result.file.exists())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun existingPublicationTargetIsNeverOverwrittenOrDeleted() {
        val root = Files.createTempDirectory("provider-existing").toFile()
        try {
            val foreign = java.io.File(root, "share_request.wav").apply { writeBytes(byteArrayOf(9)) }
            val files = ProviderCopyFiles(root, "request", true)
            files.partial.writeBytes(byteArrayOf(1))
            kotlin.test.assertFailsWith<java.nio.file.FileAlreadyExistsException> { files.publish("recording.wav") }
            files.close()
            assertContentEquals(byteArrayOf(9), foreign.readBytes())
        } finally { root.deleteRecursively() }
    }

}
