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
            file("rec-3-2.ogg.download-tmp"),
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
}
