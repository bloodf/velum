package dev.velum.app.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Counts only: paths and file names carry recording/session identity and must not be logged. */
data class OrphanSweepReport(val quarantined: Int = 0, val purged: Int = 0)

/** Quarantine (not delete) lives inside the recordings dir; the sweep never recurses into it. */
const val ORPHAN_QUARANTINE_DIR = ".orphaned"
private const val HOUR_MS = 60L * 60L * 1000L
const val ORPHAN_MIN_AGE_MS = 24L * HOUR_MS
const val ORPHAN_PURGE_AFTER_MS = 7L * 24L * HOUR_MS

/** Final audio names written by Velum (mic `rec-*.wav`, downloads `rec-*.{ogg,wav,bin}`). */
private val FINAL_AUDIO_NAME = Regex("^rec-[A-Za-z0-9_-]+\\.(wav|ogg|bin)$", RegexOption.IGNORE_CASE)

/**
 * #122 startup sweep. Moves a top-level file of [recordingsDirPath] to [ORPHAN_QUARANTINE_DIR] only if ALL hold:
 * - its name is a Velum final audio name (never `.partial`, `.download-tmp` or unknown files);
 * - no row (trashed included) has its path as `local_path` or its base name as `id` (covers mic drafts whose
 *   path is not persisted yet);
 * - it was not modified for [minAgeMs] (guards writes whose row is not inserted yet).
 * [fileRefs] failing aborts the sweep before any file is touched. Quarantined files are deleted after
 * [purgeAfterMs] in quarantine.
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
    val refs = fileRefs()
    val ids = refs.mapTo(HashSet()) { it.first }
    val paths = refs.mapNotNullTo(HashSet()) { (_, path) -> path?.let { File(it).canonicalPath } }
    val quarantine = File(dir, ORPHAN_QUARANTINE_DIR)
    var quarantined = 0
    for (file in dir.listFiles().orEmpty()) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
        if (!FINAL_AUDIO_NAME.matches(file.name)) continue
        if (file.name.substringBeforeLast('.') in ids || file.canonicalPath in paths) continue
        if (nowMs - file.lastModified() < minAgeMs) continue
        if (!quarantine.isDirectory && !quarantine.mkdirs()) break
        val target = File(quarantine, file.name)
        if (target.exists() || !file.renameTo(target)) continue
        target.setLastModified(nowMs)
        quarantined++
    }
    var purged = 0
    for (file in quarantine.listFiles().orEmpty()) {
        if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            nowMs - file.lastModified() >= purgeAfterMs &&
            file.delete()
        ) {
            purged++
        }
    }
    return OrphanSweepReport(quarantined, purged)
}
