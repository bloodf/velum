package dev.velum.app.platform

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/** #86 finalize + startup sweep for recorder download `.part` files (synthetic bytes only). */
class DownloadPartFilesTest {
    private val dir: File = Files.createTempDirectory("velum-86-parts").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun streams_chunks_and_atomically_replaces_existing_target() {
        val target = File(dir, "rec-1-a.ogg").apply { writeBytes(ByteArray(5) { 9 }) }
        val part = File(dir, "rec-1-a.part")
        val chunks = listOf("Og".encodeToByteArray(), "gS".encodeToByteArray(), ByteArray(70_000) { 3 })
        var head = ByteArray(0)
        val (path, size) = streamToFileAtomically(part.path, 12, { h -> head = h; target.path }) { sink ->
            chunks.forEach(sink)
        }
        assertEquals(target.path, path)
        assertEquals(70_004L, size)
        assertContentEquals("OggS".encodeToByteArray() + ByteArray(8) { 3 }, head)
        assertContentEquals(chunks.reduce { a, b -> a + b }, target.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun failure_deletes_part_and_keeps_existing_target() {
        val target = File(dir, "rec-2-b.ogg").apply { writeBytes(ByteArray(5) { 9 }) }
        val part = File(dir, "rec-2-b.part")
        assertFailsWith<IOException> {
            streamToFileAtomically(part.path, 12, { target.path }) { sink ->
                sink(ByteArray(1024))
                throw IOException("No space left on device")
            }
        }
        assertFalse(part.exists())
        assertContentEquals(ByteArray(5) { 9 }, target.readBytes())
    }

    @Test
    fun sweep_removes_only_stale_download_parts() {
        val now = System.currentTimeMillis()
        val old = now - STALE_DOWNLOAD_PART_AGE_MS - 60_000
        fun file(name: String, modified: Long) = File(dir, name).apply { writeBytes(ByteArray(1)); setLastModified(modified) }
        val stale = file("rec-1793588244-5198613539.part", old)
        val fresh = file("rec-1793588245-1.part", now)
        val audio = file("rec-1793588244-5198613539.ogg", old)
        val foreign = file("notes.part", old)
        val removed = sweepStaleDownloadParts(dir.path, now - STALE_DOWNLOAD_PART_AGE_MS)
        assertEquals(listOf(stale.name), removed)
        assertFalse(stale.exists())
        assertTrue(fresh.exists() && audio.exists() && foreign.exists())
        assertEquals(emptyList(), sweepStaleDownloadParts(File(dir, "missing").path, now))
    }
}
