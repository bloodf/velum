@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class DedicatedIosPartialTransferTest {
    private fun withDirectory(test: (String) -> Unit) {
        val dir = NSTemporaryDirectory() + "velum-partial-" + NSUUID().UUIDString
        try { test(dir) } finally { IosPartialTransferFileStore.deleteAll(dir) }
    }

    @Test
    fun checkpointBytesAndVerdictsSurviveReopen() = withDirectory { dir ->
        val bytes = byteArrayOf(0, 1, -1, 42)
        IosPartialTransferFileStore.open(dir, 7, "device-A", 4).apply {
            write(bytes)
            setCrcMismatch(true)
            setReadFailures(2)
            close()
        }
        IosPartialTransferFileStore.open(dir, 7, "device-A").apply {
            assertEquals(4L, length())
            assertContentEquals(bytes, read(0, 4))
            assertTrue(crcMismatch())
            assertEquals(2, readFailures())
            write(byteArrayOf(8))
            assertEquals(5L, length())
            close()
        }
        IosPartialTransferFileStore.open(dir, 7, "device-A").apply {
            assertContentEquals(bytes + byteArrayOf(8), read(0, 5))
            close()
        }
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
        assertTrue(names.none { "device-A" in it.toString() })
    }

    @Test
    fun uncheckpointedTailIsDiscardedAndDevicesStaySeparate() = withDirectory { dir ->
        IosPartialTransferFileStore.open(dir, 7, "A", 2).apply {
            write(byteArrayOf(1, 2))
            write(byteArrayOf(3))
            close()
        }
        IosPartialTransferFileStore.open(dir, 7, "B").apply {
            assertEquals(0L, length())
            close()
        }
        IosPartialTransferFileStore.open(dir, 7, "A").apply {
            assertEquals(2L, length())
            assertContentEquals(byteArrayOf(1, 2), read(0, 2))
            close()
        }
    }

    @Test
    fun missingSerialAlwaysStartsEmpty() = withDirectory { dir ->
        IosPartialTransferFileStore.open(dir, 8, null).apply {
            write(byteArrayOf(1, 2))
            length()
            close()
        }
        IosPartialTransferFileStore.open(dir, 8, " ").apply {
            assertEquals(0L, length())
            close()
        }
    }

    @Test
    fun truncationAndClearedVerdictsPersist() = withDirectory { dir ->
        IosPartialTransferFileStore.open(dir, 9, "A", 1).apply {
            write(byteArrayOf(1, 2, 3))
            truncate(2)
            close()
        }
        IosPartialTransferFileStore.open(dir, 9, "A").apply {
            assertContentEquals(byteArrayOf(1, 2), read(0, 2))
            setCrcMismatch(true)
            setReadFailures(3)
            truncate(0)
            close()
        }
        IosPartialTransferFileStore.open(dir, 9, "A").apply {
            assertEquals(0L, length())
            assertFalse(crcMismatch())
            assertEquals(0, readFailures())
            delete()
        }
        assertTrue(IosPartialTransferFileStore.delete(dir, 9, "A"))
    }

    @Test
    fun staleSweepRemovesDataAndMetadataButKeepsSalt() = withDirectory { dir ->
        IosPartialTransferFileStore.open(dir, 10, "A", 1).apply {
            write(byteArrayOf(1))
            setCrcMismatch(true)
            setReadFailures(1)
            close()
        }
        assertEquals(0, IosPartialTransferFileStore.sweepStale(dir, 0))
        assertEquals(4, IosPartialTransferFileStore.sweepStale(dir, Long.MAX_VALUE))
        assertEquals(listOf("serial-key.salt"), NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null))
    }
}
