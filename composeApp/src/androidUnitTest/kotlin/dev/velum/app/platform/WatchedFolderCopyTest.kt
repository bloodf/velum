package dev.velum.app.platform

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #325: an interrupted copy never reaches the final path, and the next sync copies the whole file. */
class WatchedFolderCopyTest {
    private val dir: File = Files.createTempDirectory("velum-watched").toFile()
    private val payload = ByteArray(10_000) { (it % 251).toByte() }

    private fun throwsAfter(bytes: Int) = object : InputStream() {
        private var n = 0
        override fun read(): Int {
            if (n >= bytes) throw IOException("card removed")
            return payload[n++].toInt() and 0xFF
        }
    }

    @Test fun an_interrupted_copy_leaves_nothing_at_the_final_path_and_the_next_sync_retries() {
        val dest = File(dir, "1_a.wav")
        assertTrue(runCatching { copyWholeFile(dest, payload.size.toLong()) { throwsAfter(4_000) } }.isFailure)
        assertFalse(dest.exists(), "partial file at the final path")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "temp file left behind: ${dir.list()?.toList()}")
        assertTrue(copyWholeFile(dest, payload.size.toLong()) { ByteArrayInputStream(payload) })
        assertContentEquals(payload, dest.readBytes())
    }

    @Test fun a_short_read_without_an_exception_is_still_rejected() {
        val dest = File(dir, "2_b.wav")
        assertFalse(copyWholeFile(dest, payload.size.toLong()) { ByteArrayInputStream(payload, 0, 5_000) })
        assertFalse(dest.exists())
    }

    @Test fun a_truncated_file_left_by_an_older_version_is_replaced() {
        val dest = File(dir, "3_c.wav").also { it.writeBytes(payload.copyOf(1_000)) }
        assertTrue(copyWholeFile(dest, payload.size.toLong()) { ByteArrayInputStream(payload) })
        assertEquals(payload.size.toLong(), dest.length())
    }

    @Test fun a_complete_copy_is_not_read_again() {
        val dest = File(dir, "4_d.wav").also { it.writeBytes(payload) }
        var opened = 0
        assertTrue(copyWholeFile(dest, payload.size.toLong()) { opened++; null })
        assertEquals(0, opened)
    }

    @Test fun an_unreadable_source_fails_without_a_file() {
        assertFalse(copyWholeFile(File(dir, "5_e.wav"), 10L) { null })
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("5_e") })
    }
}
