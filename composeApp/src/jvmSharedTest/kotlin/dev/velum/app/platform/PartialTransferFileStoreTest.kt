package dev.velum.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** #94: what survives a process death is exactly what was checkpointed. Synthetic serial and bytes. */
class PartialTransferFileStoreTest {
    private val root: File = Files.createTempDirectory("velum-partial-synthetic").toFile()
    private val now = 1_800_000_000_000L

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun partialFiles() = root.listFiles()!!.filter { it.name.endsWith(".partial") || it.name.endsWith(".offset") }

    private fun open(session: Long = 1L, serial: String? = "SYNTH-SN-1", checkpointBytes: Int = 1_000) =
        PartialTransferFileStore.open(root, session, serial, checkpointBytes)

    @Test fun a_writer_still_unwinding_after_a_delete_leaves_nothing_a_later_open_uses() {
        val writer = open()
        writer.write(ByteArray(10))
        writer.length()
        open().delete()
        writer.write(ByteArray(5_000)) // past the checkpoint size: it writes a new .offset for the removed file
        writer.close()
        assertTrue(File(root.listFiles()!!.first { it.name.endsWith(".offset") }.path).readText().toLong() > 0)

        val next = open()
        assertEquals(0L, next.length(), "a late .offset must be ignored")
        assertEquals(emptyList(), root.listFiles()!!.filter { it.name.endsWith(".offset") }, "and removed, so a later crash can't trust it")
        next.close()
    }

    @Test fun length_reports_all_written_bytes_because_it_flushes() {
        val store = open()
        store.write(ByteArray(10) { 1 })
        assertEquals(10L, store.length())
        store.close()
    }

    @Test fun process_death_keeps_only_checkpointed_bytes() {
        val store = open(checkpointBytes = 1_000)
        val data = ByteArray(1_500) { it.toByte() }
        store.write(data.copyOf(1_200)) // past the checkpoint size: flushed
        store.write(data.copyOfRange(1_200, 1_500)) // not flushed, and the process dies here
        val reopened = open()
        assertEquals(1_200L, reopened.length())
        assertContentEquals(data.copyOf(1_200), reopened.read(0, 1_200))
        reopened.write(byteArrayOf(9))
        assertEquals(1_201L, reopened.length())
        assertEquals(9, reopened.read(1_200, 1)[0])
        reopened.close()
    }

    @Test fun missing_or_damaged_checkpoint_restarts_from_zero() {
        val store = open()
        store.write(ByteArray(50))
        assertEquals(50L, store.length())
        store.close()
        val sidecar = partialFiles().single { it.name.endsWith(".offset") }
        sidecar.writeText("not a number")
        assertEquals(0L, open().length())
        sidecar.writeText("999999") // claims more than the file holds
        assertEquals(0L, open().length())
    }

    @Test fun crc_mismatch_mark_is_kept_with_the_data_and_dropped_when_the_data_restarts() {
        val store = open()
        store.write(ByteArray(50))
        store.setCrcMismatch(true)
        store.length()
        store.close()
        assertTrue(open().also { it.close() }.crcMismatch(), "a valid checkpoint keeps the verdict")
        partialFiles().single { it.name.endsWith(".offset") }.writeText("not a number")
        val restarted = open()
        assertEquals(0L, restarted.length())
        assertFalse(restarted.crcMismatch(), "bytes restarting from zero must not inherit the old verdict")
        restarted.close()
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".crcmismatch") })
    }

    @Test fun read_failure_count_survives_a_reopen_and_is_dropped_with_restarted_data() {
        val store = open()
        store.write(ByteArray(50))
        store.setReadFailures(1)
        store.length()
        store.close()
        assertEquals(1, open().also { it.close() }.readFailures())
        partialFiles().single { it.name.endsWith(".offset") }.writeText("not a number")
        val restarted = open()
        assertEquals(0, restarted.readFailures(), "bytes restarting from zero have no read history")
        restarted.close()
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".readfail") })
    }

    @Test fun truncate_and_delete() {
        val store = open()
        store.write(ByteArray(100))
        store.truncate(40)
        assertEquals(40L, store.length())
        assertEquals(40L, open().length())
        store.delete()
        assertTrue(partialFiles().isEmpty(), "no partial data left")
    }

    @Test fun files_are_keyed_by_session_and_serial_and_never_name_the_serial() {
        val a = open(session = 1L, serial = "SYNTH-SN-1")
        a.write(ByteArray(5)); a.length()
        assertEquals(0L, open(session = 2L, serial = "SYNTH-SN-1").length())
        assertEquals(0L, open(session = 1L, serial = "SYNTH-SN-2").length())
        assertEquals(5L, open(session = 1L, serial = "SYNTH-SN-1").length())
        assertFalse(root.list()!!.any { "SYNTH" in it }, "serial must not appear in file names")
        assertNotEquals(0, root.list()!!.size)
    }

    @Test fun serial_key_is_salted_per_install_and_stable_within_one() {
        open().apply { write(ByteArray(5)); length(); close() }
        val first = partialFiles().map { it.name }.toSet()
        open().close()
        assertEquals(first, partialFiles().map { it.name }.toSet(), "the same install finds its partial again")
        val otherInstall = File(root, "other").apply { mkdirs() }
        PartialTransferFileStore.open(otherInstall, 1L, "SYNTH-SN-1").close()
        val otherNames = otherInstall.listFiles()!!.map { it.name }.filter { it.endsWith(".partial") }
        assertFalse(otherNames.any { it in first }, "another install's salt gives another key")
    }

    @Test fun delete_all_removes_partials_and_salt_so_nothing_resumes() {
        open().apply { write(ByteArray(5)); length(); close() }
        assertTrue(File(root, "serial-key.salt").isFile)
        PartialTransferFileStore.deleteAll(root)
        assertFalse(root.exists())
        PartialTransferFileStore.deleteAll(root) // nothing there is fine
        val reopened = open()
        assertEquals(0L, reopened.length())
        reopened.close()
    }

    @Test fun without_a_real_serial_there_is_no_resume() {
        val a = open(serial = null)
        a.write(ByteArray(5)); assertEquals(5L, a.length()); a.close()
        assertEquals(0L, open(serial = null).length())
    }

    @Test fun sweep_removes_only_stale_partial_files() {
        val store = open()
        store.write(ByteArray(5)); store.length(); store.close()
        val files = partialFiles()
        val other = File(root, "unrelated.txt").apply { writeText("x"); setLastModified(now - 10 * STALE_PARTIAL_TRANSFER_MS) }
        files.forEach { it.setLastModified(now - STALE_PARTIAL_TRANSFER_MS + 1_000) }
        assertEquals(0, PartialTransferFileStore.sweepStale(root, now))
        assertTrue(files.all { it.isFile })
        files.forEach { it.setLastModified(now - STALE_PARTIAL_TRANSFER_MS) }
        assertEquals(files.size, PartialTransferFileStore.sweepStale(root, now))
        assertTrue(other.isFile)
        assertEquals(0, PartialTransferFileStore.sweepStale(File(root, "missing"), now))
    }
}
