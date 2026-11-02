package dev.velum.app.platform

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume

/** #122: orphan sweep is data-safe. Synthetic ids/files only. */
class OrphanRecordingSweepTest {
    private val root: File = Files.createTempDirectory("velum-sweep-synthetic").toFile()
    private val now = 1_800_000_000_000L
    private val old = now - ORPHAN_MIN_AGE_MS - 1

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun file(name: String, modified: Long = old) = File(root, name).apply { writeText("synthetic"); setLastModified(modified) }
    private fun sweep(refs: List<Pair<String, String?>> = emptyList(), at: Long = now) =
        runBlocking { sweepOrphanRecordingAudio(root.path, { refs }, nowMs = at) }
    private fun quarantined(name: String) = File(File(root, ORPHAN_QUARANTINE_DIR), name)

    @Test fun orphan_is_quarantined_and_second_run_is_a_noop() {
        val orphan = file("rec-1-1.ogg")
        assertEquals(OrphanSweepReport(quarantined = 1), sweep())
        assertFalse(orphan.exists()); assertTrue(quarantined("rec-1-1.ogg").isFile)
        assertEquals(OrphanSweepReport(), sweep())
    }

    @Test fun files_referenced_by_path_or_row_id_are_kept_including_trashed_and_mic_drafts() {
        val byPath = file("rec-2-1.ogg")
        val byId = file("rec-2-2.wav") // mic draft row whose local_path is not persisted yet
        val moved = file("rec-2-3.bin") // referenced by a row with another id
        val refs = listOf("rec-x" to byPath.path, "rec-2-2" to null, "rec-y" to moved.path)
        assertEquals(OrphanSweepReport(), sweep(refs))
        assertTrue(byPath.isFile && byId.isFile && moved.isFile)
    }

    @Test fun partials_temps_unknown_names_young_files_and_subdirs_are_never_touched() {
        val kept = listOf(
            file("rec-3-1.wav.partial"),
            file("rec-3-2.ogg.download-tmp", modified = now - 1_000),
            file("rec-3-5.part"),
            file("notes.txt"),
            file("REC.ogg"),
            file("rec-3-3.ogg", modified = now - 1_000),
        )
        File(root, "rec-3-4.ogg").mkdirs()
        assertEquals(OrphanSweepReport(), sweep())
        assertTrue(kept.all { it.isFile }); assertTrue(File(root, "rec-3-4.ogg").isDirectory)
    }

    @Test fun missing_or_empty_dir_is_a_noop() {
        assertEquals(OrphanSweepReport(), sweep())
        root.deleteRecursively()
        assertEquals(OrphanSweepReport(), sweep())
    }

    @Test fun row_read_failure_aborts_before_touching_any_file() {
        val orphan = file("rec-4-1.ogg")
        assertFailsWith<IOException> {
            runBlocking { sweepOrphanRecordingAudio(root.path, { throw IOException("synthetic db failure") }, nowMs = now) }
        }
        assertTrue(orphan.isFile)
    }

    @Test fun quarantine_is_purged_only_after_grace_period() {
        file("rec-5-1.ogg"); sweep()
        assertEquals(OrphanSweepReport(), sweep(at = now + ORPHAN_PURGE_AFTER_MS - 1))
        assertEquals(OrphanSweepReport(purged = 1), sweep(at = now + ORPHAN_PURGE_AFTER_MS))
        assertFalse(quarantined("rec-5-1.ogg").exists())
    }

    @Test fun id_protection_applies_only_while_the_row_has_no_path_so_a_leaked_old_extension_is_collected() {
        val current = file("rec-6-1.wav")
        val leaked = file("rec-6-1.ogg")
        assertEquals(OrphanSweepReport(quarantined = 1), sweep(listOf("rec-6-1" to current.path)))
        assertTrue(current.isFile); assertTrue(quarantined("rec-6-1.ogg").isFile); assertFalse(leaked.exists())
    }

    @Test fun unknown_mtime_is_never_quarantined_or_purged() {
        val unknown = file("rec-7-1.ogg", modified = 0)
        assertEquals(OrphanSweepReport(), sweep())
        assertTrue(unknown.isFile)
        val q = File(root, ORPHAN_QUARANTINE_DIR).apply { mkdirs() }
        val inQuarantine = File(q, "rec-7-2.ogg").apply { writeText("synthetic"); setLastModified(0) }
        assertEquals(OrphanSweepReport(), sweep(at = now + 10 * ORPHAN_PURGE_AFTER_MS))
        assertTrue(inQuarantine.isFile)
    }

    @Test fun purge_rechecks_rows_and_restores_a_file_that_became_referenced() {
        file("rec-8-1.ogg"); sweep()
        var calls = 0
        val report = runBlocking {
            sweepOrphanRecordingAudio(root.path, { if (calls++ == 0) emptyList() else listOf("rec-8-1" to null) }, nowMs = now + ORPHAN_PURGE_AFTER_MS)
        }
        assertEquals(OrphanSweepReport(), report)
        assertTrue(File(root, "rec-8-1.ogg").isFile); assertFalse(quarantined("rec-8-1.ogg").exists())
    }

    @Test fun own_stale_download_temps_are_deleted_but_young_temps_and_parts_are_kept() {
        val stale = file("rec-9-1.ogg.download-tmp")
        val young = file("rec-9-2.ogg.download-tmp", modified = now - 1_000)
        val part = file("rec-9-3.part")
        assertEquals(OrphanSweepReport(staleTemps = 1), sweep())
        assertFalse(stale.exists()); assertTrue(young.isFile); assertTrue(part.isFile)
    }

    @Test fun failed_restore_is_counted_and_the_file_stays_quarantined_never_purged() {
        file("rec-10-1.ogg"); sweep()
        root.setWritable(false)
        try {
            // Root ignores directory permissions; the scenario cannot be produced there.
            Assume.assumeFalse(root.canWrite())
            val report = runBlocking {
                sweepOrphanRecordingAudio(root.path, { listOf("rec-10-1" to null) }, nowMs = now + 10 * ORPHAN_PURGE_AFTER_MS)
            }
            assertEquals(OrphanSweepReport(failedMoves = 1), report)
        } finally {
            root.setWritable(true)
        }
        assertTrue(quarantined("rec-10-1.ogg").isFile)
    }
}
