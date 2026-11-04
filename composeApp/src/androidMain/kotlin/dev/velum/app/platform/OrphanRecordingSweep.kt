package dev.velum.app.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Counts only: paths and file names carry recording/session identity and must not be logged. */
data class OrphanSweepReport(val quarantined: Int = 0, val purged: Int = 0, val staleTemps: Int = 0, val failedMoves: Int = 0)

/** Quarantine (not delete) lives inside the recordings dir; the sweep never recurses into it. */
const val ORPHAN_QUARANTINE_DIR = ".orphaned"
private const val HOUR_MS = 60L * 60L * 1000L
const val ORPHAN_MIN_AGE_MS = 24L * HOUR_MS
const val ORPHAN_PURGE_AFTER_MS = 7L * 24L * HOUR_MS

/** Final audio names written by Velum (mic `rec-*.wav`, downloads `rec-*.{ogg,wav,bin}`). */
private val FINAL_AUDIO_NAME = Regex("^rec-[A-Za-z0-9_-]+\\.(wav|ogg|bin)$", RegexOption.IGNORE_CASE)

/** Temps left by the pre-#86 atomic download write; #86 `.part` files are swept by [sweepStaleDownloadParts]. */
private val OWN_DOWNLOAD_TEMP_NAME = Regex("^rec-[A-Za-z0-9_-]+\\.(wav|ogg|bin)\\.download-tmp$", RegexOption.IGNORE_CASE)

private class Refs(rows: List<Pair<String, String?>>) {
    // A base-name match protects only rows whose path is not persisted yet (mic drafts). Once a row has
    // a path, only that exact file is protected, so a leaked old-extension copy stays collectable.
    private val pendingIds = rows.filter { it.second == null }.mapTo(HashSet()) { it.first }
    private val paths = rows.mapNotNullTo(HashSet()) { (_, path) -> path?.let { File(it).canonicalPath } }
    fun protects(original: File) = original.name.substringBeforeLast('.') in pendingIds || original.canonicalPath in paths
}

private fun File.isPlainFile() = Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

/** Unknown mtime (0) counts as fresh: never old enough to quarantine, purge or delete. */
private fun File.olderThan(ageMs: Long, nowMs: Long): Boolean {
    val modified = lastModified()
    return modified > 0L && nowMs - modified >= ageMs
}

/**
 * #122 startup sweep over top-level files of [recordingsDirPath]. A file moves to [ORPHAN_QUARANTINE_DIR]
 * only if ALL hold:
 * - its name is a Velum final audio name (never `.partial`, `.part`, `.download-tmp` or unknown files);
 * - no row (trashed included) references it: its canonical path is a `local_path`, or its base name is the
 *   id of a row whose `local_path` is still null (mic drafts);
 * - its mtime is known and older than [minAgeMs] (guards writes whose row is not inserted yet).
 * [fileRefs] failing aborts the sweep before any file is touched; refs are read again before purging, and a
 * quarantined file that became referenced is moved back. Quarantined files are deleted after [purgeAfterMs].
 * Velum's own `*.download-tmp` files older than [minAgeMs] are deleted (an interrupted atomic write never
 * replaced the final file).
 */
suspend fun sweepOrphanRecordingAudio(
    recordingsDirPath: String,
    fileRefs: suspend () -> List<Pair<String, String?>>,
    nowMs: Long = System.currentTimeMillis(),
    minAgeMs: Long = ORPHAN_MIN_AGE_MS,
    purgeAfterMs: Long = ORPHAN_PURGE_AFTER_MS,
): OrphanSweepReport {
    val dir = File(recordingsDirPath).canonicalFile
    if (!dir.isDirectory) return OrphanSweepReport()
    val refs = Refs(fileRefs())
    val quarantine = File(dir, ORPHAN_QUARANTINE_DIR)
    var quarantined = 0
    var staleTemps = 0
    var failedMoves = 0
    for (file in dir.listFiles().orEmpty()) {
        if (!file.isPlainFile() || !file.olderThan(minAgeMs, nowMs)) continue
        if (OWN_DOWNLOAD_TEMP_NAME.matches(file.name)) {
            if (file.delete()) staleTemps++
            continue
        }
        if (!FINAL_AUDIO_NAME.matches(file.name) || refs.protects(file)) continue
        if (!quarantine.isDirectory && !quarantine.mkdirs()) break
        val target = File(quarantine, file.name)
        if (target.exists()) continue
        // The purge clock is the quarantine mtime. Stamp it BEFORE the move (rename keeps mtime), so no undo
        // path exists: a failed stamp skips the file; a failed rename only leaves it fresh, i.e. kept for 24h.
        if (!file.setLastModified(nowMs) || !file.renameTo(target)) { failedMoves++; continue }
        quarantined++
    }
    val purge = purgeQuarantine(dir, quarantine, Refs(fileRefs()), nowMs, purgeAfterMs)
    return OrphanSweepReport(quarantined, purge.first, staleTemps, failedMoves + purge.second)
}

/** @return (purged, failed restores). A failed restore stays in quarantine and is retried next start. */
private fun purgeQuarantine(dir: File, quarantine: File, refs: Refs, nowMs: Long, purgeAfterMs: Long): Pair<Int, Int> {
    var purged = 0
    var failedRestores = 0
    for (file in quarantine.listFiles().orEmpty()) {
        if (!file.isPlainFile()) continue
        val original = File(dir, file.name)
        // Referenced again: never purge; move back when the original slot is free.
        if (refs.protects(original)) {
            if (!original.exists() && !file.renameTo(original)) failedRestores++
            continue
        }
        if (file.olderThan(purgeAfterMs, nowMs) && file.delete()) purged++
    }
    return purged to failedRestores
}

/** A copy younger than this may belong to an import still running. */
const val STALE_IMPORT_COPY_AGE_MS: Long = 24L * 60L * 60L * 1000L

/**
 * Removes picked or shared copies left in [importsDir] by older versions, cancelled imports or a crash: top-level
 * regular files older than [minAgeMs] that no recording points at. watched/ and normalized/ are never entered.
 * Returns how many were removed.
 */
fun sweepStaleImportCopies(
    importsDir: File,
    localPaths: List<String?>,
    nowMs: Long = System.currentTimeMillis(),
    minAgeMs: Long = STALE_IMPORT_COPY_AGE_MS,
): Int {
    val referenced = localPaths.filterNotNull().map { File(it).canonicalFile }.toSet()
    return importsDir.listFiles().orEmpty().count { file ->
        file.isPlainFile() && file.olderThan(minAgeMs, nowMs) && file.canonicalFile !in referenced && file.delete()
    }
}
