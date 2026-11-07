@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.app.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentHashTest {
    @Test
    fun knownBytesAndFileVectors() {
        val vectors = listOf(
            "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        )
        for ((text, expected) in vectors) {
            val bytes = text.encodeToByteArray()
            assertEquals(expected, sha256Bytes(bytes))
            withFile(bytes) { path -> assertEquals(expected, sha256File(path)) }
        }
    }

    @Test
    fun binaryFileWithMultipleChunksAndPartialTail() {
        val bytes = ByteArray(64 * 1024 * 2 + 17) { (it % 251).toByte() }
        val expected = "1ec3437cee3cccf3647e130524ade52848571960a3340ae33573beef17d330b3"
        assertEquals(expected, sha256Bytes(bytes))
        withFile(bytes) { path -> assertEquals(expected, sha256File(path)) }
    }

    @Test
    fun missingFileAndDirectoryFail() {
        val missing = NSTemporaryDirectory() + NSUUID().UUIDString
        assertFailsWith<IllegalStateException> { sha256File(missing) }
        // A directory opens on Darwin, then fails in read rather than yielding an empty digest.
        assertFailsWith<IllegalStateException> { sha256File(NSTemporaryDirectory()) }
    }

    private fun withFile(bytes: ByteArray, block: (String) -> Unit) {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString
        try {
            val data = bytes.usePinned { input ->
                NSData.create(bytes = if (bytes.isEmpty()) null else input.addressOf(0), length = bytes.size.toULong())
            }
            assertTrue(data.writeToFile(path, atomically = true))
            block(path)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }
}
