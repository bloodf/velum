package dev.velum.ai.local

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStoreRenameTest {
    @Test
    fun file_rename_atomically_replaces_existing_target() {
        val dir = ModelStoreDir(Files.createTempDirectory("velum-rename").toString())
        try {
            val src = dir.resolve("rec.part").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val dest = dir.resolve("rec.ogg").also { it.writeBytes(byteArrayOf(9)) }
            assertTrue(src.renameTo(dest))
            assertFalse(src.exists())
            assertContentEquals(byteArrayOf(1, 2, 3), dest.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun directory_rename_still_replaces_existing_directory() {
        val dir = ModelStoreDir(Files.createTempDirectory("velum-rename-dir").toString())
        try {
            val src = dir.resolve("partial").also { it.createDirectories(); it.resolve("w.bin").writeBytes(byteArrayOf(1)) }
            val dest = dir.resolve("model").also { it.createDirectories(); it.resolve("old.bin").writeBytes(byteArrayOf(2)) }
            assertTrue(src.renameTo(dest))
            assertTrue(dest.resolve("w.bin").exists())
            assertFalse(dest.resolve("old.bin").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
