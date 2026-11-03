package dev.velum.app.platform

import dev.velum.core.data.VelumRepository
import java.io.File

/**
 * Earlier versions kept the only copy of an imported recording in cacheDir/imports, which Android may evict at any
 * time. Moves each such row's audio into [recordingsDir] and repoints the row, then removes the raw copies nothing
 * references. A row is repointed only after its file is safely in place, and the cache file is deleted only after
 * the row points at the new one, so a failure at any step loses nothing. Safe to run on every start.
 * Returns the number of rows moved.
 */
suspend fun migrateCachedImports(repository: VelumRepository, recordingsDir: File, cacheDir: File): Int {
    val importsRoot = File(cacheDir, "imports").canonicalFile
    fun inImports(f: File) = f.canonicalPath.startsWith(importsRoot.path + File.separator)
    var moved = 0
    val stillReferenced = HashSet<String>()
    for ((id, path) in repository.getAllRecordingFileRefs()) {
        if (path.isNullOrBlank()) continue
        val source = File(path)
        if (!runCatching { inImports(source) }.getOrDefault(false)) continue
        if (!source.isFile) continue // already evicted: nothing to save
        val ext = source.extension.ifBlank { "wav" }
        val dest = File(recordingsDir.apply { mkdirs() }, "$id.$ext")
        val ok = runCatching {
            if (!dest.exists()) {
                val tmp = File(recordingsDir, dest.name + ".tmp")
                try {
                    source.copyTo(tmp, overwrite = true)
                    check(tmp.length() == source.length()) { "short copy" }
                    check(tmp.renameTo(dest)) { "rename failed" }
                } finally {
                    tmp.delete()
                }
            }
            val row = repository.getRecording(id)
            repository.updateLocalPath(id, dest.absolutePath, row?.duration_seconds ?: 0L, row?.size_bytes ?: 0L)
            source.delete()
        }.isSuccess
        if (ok) moved++ else stillReferenced += source.canonicalPath
    }
    // Raw copies of incoming files that no row uses (the old code never deleted them).
    importsRoot.walkBottomUp().filter { it.isFile && it.canonicalPath !in stillReferenced }.forEach { it.delete() }
    return moved
}
