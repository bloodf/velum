package dev.velum.app.platform

import android.util.Log
import androidx.annotation.VisibleForTesting
import dev.velum.ai.audio.DownloadedSessionAudioPersist
import dev.velum.core.data.VelumRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException

/**
 * #122: delete recording-directory audio files that no `recording.local_path` references
 * (including trashed rows). Complements #113's SQL dedupe, which drops duplicate rows but
 * cannot unlink files.
 *
 * Never deletes #86 partial/temp/`.part` artifacts — those belong to
 * [cleanupStaleDownloadArtifacts] / [PartialRecordingRecovery]. Never follows symlinks or
 * `..` outside the recordings directory. Logs counts only (no paths / session ids).
 */
@VisibleForTesting
internal fun isProtectedOrphanSweepName(fileName: String): Boolean {
    val name = fileName.lowercase()
    if (name == DownloadedSessionAudioPersist.DOWNLOAD_TEMP_DIR_NAME) return true
    if (DownloadedSessionAudioPersist.isDownloadTempName(name)) return true
    // Mic + legacy partials, HTTP `.part`, and generic temps — owned by #86 / in-flight I/O.
    if (name.endsWith(".partial")) return true
    if (name.endsWith(".part")) return true
    if (name.endsWith(".tmp") || name.endsWith(".temp")) return true
    return false
}

/**
 * Returns whether [candidate] resolves under [recordingsRoot] after canonicalization.
 * Symlinks / `..` that escape the root return false.
 */
@VisibleForTesting
internal fun isCanonicalPathUnderRecordingsDir(candidate: File, recordingsRoot: File): Boolean {
    val root = runCatching { recordingsRoot.canonicalFile }.getOrNull() ?: return false
    val cand = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
    val rootPath = root.path
    val candPath = cand.path
    return candPath == rootPath || candPath.startsWith(rootPath + File.separator)
}

/**
 * Build the keep-set of absolute paths under [recordingsRoot] referenced by DB rows.
 * Paths that escape the recordings directory (symlink / `..`) are ignored — the sweep
 * must never use them as a reason to touch files outside the root.
 */
@VisibleForTesting
internal fun referencedPathsInsideRecordingsDir(
    referencedLocalPaths: Collection<String>,
    recordingsRoot: File,
): Set<String> {
    val root = runCatching { recordingsRoot.canonicalFile }.getOrNull() ?: return emptySet()
    val keep = linkedSetOf<String>()
    for (raw in referencedLocalPaths) {
        if (raw.isBlank()) continue
        val file = File(raw)
        // Keep exact absolute forms as stored (in-progress import may not be canonical yet).
        if (file.isAbsolute) {
            val normalized = file.absoluteFile.normalize()
            if (normalized.path == root.path ||
                normalized.path.startsWith(root.path + File.separator)
            ) {
                keep.add(normalized.path)
            }
        }
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: continue
        if (isCanonicalPathUnderRecordingsDir(canonical, root)) {
            keep.add(canonical.path)
        }
    }
    return keep
}

/**
 * Delete a recordings-dir entry without following a symlink target outside the root.
 */
@VisibleForTesting
internal fun safeDeleteRecordingDirEntry(file: File, recordingsRoot: File): Boolean {
    return runCatching {
        val path = file.toPath()
        val root = recordingsRoot.canonicalFile
        val normalized = file.absoluteFile.normalize()
        if (normalized.path != root.path &&
            !normalized.path.startsWith(root.path + File.separator)
        ) {
            return@runCatching false
        }
        if (Files.isSymbolicLink(path)) {
            // Unlink the directory entry only — never delete the symlink target.
            return@runCatching Files.deleteIfExists(path)
        }
        val canonical = file.canonicalFile
        if (!isCanonicalPathUnderRecordingsDir(canonical, root)) return@runCatching false
        file.delete()
    }.getOrDefault(false)
}

/**
 * Delete unreferenced regular files directly under [recordingsDirPath].
 *
 * @return number of files deleted
 */
@VisibleForTesting
internal fun sweepOrphanRecordingAudioFiles(
    recordingsDirPath: String,
    referencedLocalPaths: Collection<String>,
    listChildren: (File) -> Array<File>? = { it.listFiles() },
    deleteEntry: (File, File) -> Boolean = { file, root -> safeDeleteRecordingDirEntry(file, root) },
): Int {
    val dir = File(recordingsDirPath)
    if (!dir.isDirectory) return 0
    val root = runCatching { dir.canonicalFile }.getOrNull() ?: return 0
    val keep = referencedPathsInsideRecordingsDir(referencedLocalPaths, root)
    var removed = 0
    for (child in listChildren(dir).orEmpty()) {
        val name = child.name
        if (name == "." || name == ".." || name.contains("..")) continue
        if (isProtectedOrphanSweepName(name)) continue
        // Do not recurse: `.download-tmp` and any other subdir stays untouched (#86).
        if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) continue

        val absoluteNormalized = child.absoluteFile.normalize()
        if (absoluteNormalized.path != root.path &&
            !absoluteNormalized.path.startsWith(root.path + File.separator)
        ) {
            continue
        }

        val link = runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)
        if (link) {
            // Symlink: keep if the link path itself is referenced; never resolve/delete target.
            if (absoluteNormalized.path in keep) continue
            if (deleteEntry(child, root)) removed++
            continue
        }

        if (!child.isFile) continue
        val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
        if (!isCanonicalPathUnderRecordingsDir(canonical, root)) continue
        if (canonical.path in keep || absoluteNormalized.path in keep) continue
        if (deleteEntry(child, root)) removed++
    }
    return removed
}

/**
 * Load referenced paths (including trash) and sweep. May throw; callers that must not
 * fail startup should use [sweepOrphanRecordingAudioAfterPublish].
 *
 * @return deleted file count
 */
suspend fun sweepOrphanRecordingAudio(
    recordingsDirPath: String,
    repository: VelumRepository,
): Int {
    val referenced = repository.listRecordingLocalPaths()
    return sweepOrphanRecordingAudioFiles(
        recordingsDirPath = recordingsDirPath,
        referencedLocalPaths = referenced,
    )
}

/**
 * Post-publish startup hook (#122). Never throws into [dev.velum.app.VelumApplication]
 * init success path (cancel still propagates). Call only after the container is published
 * so readiness is never blocked by the sweep.
 */
@VisibleForTesting
internal suspend fun sweepOrphanRecordingAudioAfterPublish(
    recordingsDirPath: String,
    repository: VelumRepository,
    sweep: suspend (recordingsDirPath: String, repository: VelumRepository) -> Int =
        { path, repo -> sweepOrphanRecordingAudio(path, repo) },
): Int {
    return runCatching {
        val removed = sweep(recordingsDirPath, repository)
        if (removed > 0) {
            Log.i(TAG, "Removed $removed orphan recording audio file(s)")
        }
        removed
    }.onFailure { t ->
        if (t is CancellationException) throw t
        Log.e(TAG, "Orphan recording audio sweep failed", t)
    }.getOrDefault(0)
}

private const val TAG = "VelumOrphanSweep"
