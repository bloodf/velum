package dev.velum.ai.local

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelStoreAppendTest {
    @Test fun empty_file_and_chunked_appends_preserve_contents() {
        val root = ModelStoreDir(NSTemporaryDirectory() + NSUUID().UUIDString)
        try {
            val file = root.resolve("nested/weights.bin")
            file.writeBytes(byteArrayOf())
            assertEquals(0L, file.length())
            file.appendBytes(byteArrayOf())
            val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
            repeat(4) { file.appendBytes(chunk) }
            assertEquals(4L * chunk.size, file.length())
            repeat(4) { index ->
                assertContentEquals(chunk, file.readRange(index.toLong() * chunk.size, chunk.size))
            }
            assertContentEquals(chunk.copyOfRange(17, 101), file.readRange(17, 84))
            assertContentEquals(byteArrayOf(), file.readRange(file.length(), 0))
            assertFailsWith<IllegalArgumentException> { file.readRange(file.length() - 1, 2) }
            val newFile = root.resolve("another/created-by-append.bin")
            newFile.appendBytes(byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), newFile.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
