package dev.velum.app.platform

import dev.velum.core.data.VelumRepository
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Earlier versions kept the only copy of an imported recording in cacheDir/imports, which Android may evict at any
 * time. Moves each such row's audio into [recordingsDir] and repoints the row. Safe to run on every start and to be
 * killed at any step:
 * - the file is copied through a temp file that is fsynced, size-checked and renamed before the row is touched;
 * - the cache file is deleted only after the row points at the new file;
 * - an existing destination is reused only if it is byte-identical to the source (hash), else the row is left alone;
 * - a source that is already gone (moved before a crash, or evicted) with a valid destination present just repoints;
 * - rows that share one cache file each get their own copy, and the source goes once all of them succeeded;
 * Returns the number of rows repointed.
 */
suspend fun migrateCachedImports(
    repository: VelumRepository,
    recordingsDir: File,
    cacheDir: File,
    nowMs: Long = System.currentTimeMillis(),
    /** Incoming copies the app is about to import (a share or pick still pending): never swept. */
    pendingPaths: Set<String> = emptySet(),
): Int {
    val importsRoot = File(cacheDir, "imports").canonicalFile
    fun inImports(f: File) = runCatching { f.canonicalPath.startsWith(importsRoot.path + File.separator) }.getOrDefault(false)
    recordingsDir.mkdirs()
    var moved = 0
    val bySource = repository.getAllRecordingFileRefs()
        .filter { (_, path) -> !path.isNullOrBlank() && inImports(File(path)) }
        .groupBy({ File(it.second!!).canonicalPath }, { it.first })
    for ((sourcePath, ids) in bySource) {
        val source = File(sourcePath)
        val results = ids.map { id ->
            runCatching { migrateRow(repository, id, source, recordingsDir)?.let { true } }
        }
        moved += results.count { it.getOrNull() == true }
        // Delete the cache file once every row that used it points elsewhere.
        if (source.isFile && results.all { it.isSuccess && it.getOrNull() != null }) source.delete()
    }
    val referenced = repository.getAllRecordingFileRefs().mapNotNullTo(HashSet()) { (_, path) ->
        path?.let { runCatching { File(it).canonicalPath }.getOrNull() }
    }
    sweepResidue(recordingsDir, importsRoot, referenced, pendingPaths, nowMs - DAY_MS)
    return moved
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** true = repointed, false = nothing to do (source evicted, no destination), throws = left untouched. */
private suspend fun migrateRow(
    repository: VelumRepository,
    id: String,
    source: File,
    recordingsDir: File,
): File? {
    val row = repository.getRecording(id) ?: return null
    val ext = source.extension.ifBlank { "wav" }
    val dest = File(recordingsDir, "$id.$ext")
    when {
        source.isFile -> placeFile(source, dest)
        // Moved before a crash (or evicted): a complete destination is the rescued copy, so the row just follows it.
        dest.isFile && dest.length() > 0L -> Unit
        else -> return null
    }
    repository.updateLocalPath(id, dest.absolutePath, row.duration_seconds, row.size_bytes)
    return dest
}

/** Makes [dest] hold exactly [source]'s bytes, durably, without ever leaving a partial file at [dest]. */
private fun placeFile(source: File, dest: File) {
    if (dest.exists()) {
        // A copy from an earlier, interrupted run is reused only if it is the same audio.
        check(dest.isFile && dest.length() == source.length() && sha256File(dest.absolutePath) == sha256File(source.absolutePath)) {
            "unexpected file at destination"
        }
        return
    }
    val length = source.length()
    // A copy needs room, and low space is exactly when the OS evicts caches, so check first.
    check(dest.parentFile.usableSpace > length + SPARE_BYTES) { "not enough space to move the file" }
    val tmp = File(dest.parentFile, dest.name + ".tmp")
    try {
        FileOutputStream(tmp).use { out ->
            source.inputStream().use { it.copyTo(out) }
            out.fd.sync()
        }
        check(tmp.length() == length) { "short copy" }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(dest.parentFile)
    } finally {
        tmp.delete()
    }
}

private const val SPARE_BYTES = 8L * 1024 * 1024

/**
 * Leftovers a crash can leave, all older than a day (an import in progress is minutes old):
 * - `import_*` normalized outputs and `*.tmp` of ours that no row references (the recordings sweep only takes
 *   `rec-*` names, so these would stay forever);
 * - raw incoming copies in the cache that no row uses and that are not pending (the old code never deleted them).
 * A referenced file is never removed.
 */
private fun sweepResidue(recordingsDir: File, importsRoot: File, referenced: Set<String>, pending: Set<String>, cutoffMs: Long) {
    fun old(f: File) = f.lastModified() in 1 until cutoffMs
    recordingsDir.listFiles().orEmpty()
        .filter { it.isFile && (it.name.startsWith("import_") || it.name.endsWith(".tmp")) }
        .filter { old(it) && it.canonicalPath !in referenced }
        .forEach { it.delete() }
    val pendingCanonical = pending.mapNotNullTo(HashSet()) { runCatching { File(it).canonicalPath }.getOrNull() }
    // imports/watched holds the watched-folder copies the import deliberately keeps until their recording goes.
    val watchedRoot = File(importsRoot, "watched").path + File.separator
    importsRoot.walkBottomUp()
        .filter { it.isFile && old(it) && it.canonicalPath !in referenced && it.canonicalPath !in pendingCanonical }
        .filter { !it.canonicalPath.startsWith(watchedRoot) }
        .forEach { it.delete() }
}
