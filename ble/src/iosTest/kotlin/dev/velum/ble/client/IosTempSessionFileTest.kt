@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.ble.client

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager

class IosTempSessionFileTest {
    @Test
    fun appendsAfterReadsWithoutChangingOffsets() {
        val file = assertNotNull(createTempSessionFile())
        try {
            assertContentEquals(byteArrayOf(), file.readAll())
            file.write(byteArrayOf(0, -1, 42))
            assertContentEquals(byteArrayOf(0, -1, 42), file.readAll())
            file.write(byteArrayOf())
            file.write(byteArrayOf(7, -128))
            assertEquals(5L, file.size())
            repeat(2) { assertContentEquals(byteArrayOf(0, -1, 42, 7, -128), file.readAll()) }
        } finally {
            file.delete()
        }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.absolutePath()))
        file.delete()
        assertFailsWith<IllegalStateException> { file.write(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { file.readAll() }
    }

    @Test
    fun ownsOnlyItsUniqueFile() {
        val first = assertNotNull(createTempSessionFile())
        val second = assertNotNull(createTempSessionFile())
        try {
            assertNotEquals(first.absolutePath(), second.absolutePath())
            first.delete()
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(second.absolutePath()))
            second.write(ByteArray(65537) { (it % 251).toByte() })
            assertContentEquals(ByteArray(65537) { (it % 251).toByte() }, second.readAll())
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun rejectsOverCapAppendBeforeWriting() {
        val template = (platform.Foundation.NSTemporaryDirectory() + "velum-spool-test-XXXXXX\u0000").encodeToByteArray()
        val file = template.usePinned { pinned ->
            val fd = platform.posix.mkstemp(pinned.addressOf(0))
            assertTrue(fd >= 0)
            IosTempSessionFile(fd, pinned.addressOf(0).toKString(), maxBytes = 3)
        }
        try {
            file.write(byteArrayOf(1, 2))
            assertFailsWith<IllegalArgumentException> { file.write(byteArrayOf(3, 4)) }
            assertEquals(2L, file.size())
            assertContentEquals(byteArrayOf(1, 2), file.readAll())
            file.write(byteArrayOf(3))
            assertContentEquals(byteArrayOf(1, 2, 3), file.readAll())
        } finally {
            file.delete()
        }
    }

    @Test
    fun cancellationFinallyDeletesPartialDownload() {
        var path = ""
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            val file = assertNotNull(createTempSessionFile())
            path = file.absolutePath()
            try {
                file.write(byteArrayOf(1, 2))
                throw kotlin.coroutines.cancellation.CancellationException("cancel download")
            } finally {
                file.delete()
            }
        }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path))
    }
}
