package dev.velum.ai.local

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelStoreAppendTest {
    @Test fun durable_writes_replace_contents_and_preserve_empty_data() {
        val root = ModelStoreDir(NSTemporaryDirectory() + NSUUID().UUIDString)
        try {
            val file = root.resolve("nested/voice.enc")
            val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
            file.writeBytesDurably(bytes)
            assertContentEquals(bytes, file.readBytes())
            file.writeBytesDurably(byteArrayOf(7, 8))
            assertContentEquals(byteArrayOf(7, 8), file.readBytes())
            file.writeBytesDurably(byteArrayOf())
            assertEquals(0L, file.length())
            assertContentEquals(byteArrayOf(), file.readBytes())
            assertFailsWith<IllegalStateException> { root.writeBytesDurably(bytes) }
            assertEquals(true, root.isDirectory())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun corrupt_zip_is_rejected_without_creating_extracted_files() {
        val root = ModelStoreDir(NSTemporaryDirectory() + NSUUID().UUIDString)
        try {
            val archive = root.resolve("model.ZIP")
            archive.writeBytes(byteArrayOf(0x50, 0x4b, 3, 4))
            val destination = root.resolve("extracted")
            // ZIP must stay classified as an archive so download/readiness never treat it as raw weights.
            assertEquals(true, ModelArchiveExtractor.isArchive(archive.name))
            assertFailsWith<IllegalArgumentException> {
                ModelArchiveExtractor.extract(archive, destination)
            }
            assertEquals(false, destination.exists())
            assertContentEquals(byteArrayOf(0x50, 0x4b, 3, 4), archive.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

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
