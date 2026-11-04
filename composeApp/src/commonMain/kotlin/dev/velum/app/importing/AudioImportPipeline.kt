package dev.velum.app.importing

import dev.velum.ai.pipeline.resolveTemplateForEnqueue
import dev.velum.app.di.AppContainer
import dev.velum.core.autoflow.AutoFlowResolve
import dev.velum.app.platform.AudioNormalizer
import dev.velum.app.platform.NormalizedAudio
import dev.velum.app.platform.ImportAudioFormats
import dev.velum.app.di.WipeInProgressException
import dev.velum.app.platform.deleteFileAtPath
import dev.velum.app.platform.deleteFileIfPresent
import dev.velum.app.platform.isImportCacheCopy
import dev.velum.app.platform.sha256Bytes
import dev.velum.app.platform.isSamePath
import dev.velum.app.platform.sha256File
import dev.velum.core.data.VelumRepository.Companion.IMPORT_DELETED_PREFIX
import dev.velum.core.data.epochSecondsNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class ImportResult {
    data class Created(val recordingId: String, val wavPath: String, val sha256: String) : ImportResult()
    data class Duplicate(val existingRecordingId: String, val sha256: String) : ImportResult()
    data class Failed(val message: String) : ImportResult()
    /** Watched-folder sync only: the user permanently deleted this file's recording. */
    data class Deleted(val sha256: String) : ImportResult()
}

/**
 * Copy/normalize external audio into the same recording processor pipeline, with SHA-256 dedupe.
 */
class AudioImportPipeline(
    private val container: AppContainer,
    private val normalizer: AudioNormalizer = AudioNormalizer(),
    /** The normalize step; tests replace it. */
    private val normalize: suspend (String) -> NormalizedAudio = { normalizer.normalizeToWav(it) },
) {
    suspend fun importFile(
        absolutePath: String,
        source: String = "import",
        titleHint: String? = null,
        startedAtEpochSeconds: Long? = null,
        /** Set for a share re-offered after a restart: a recording deleted since then must not come back. */
        recoveredAtMs: Long? = null,
    ): ImportResult {
        var result: ImportResult? = null
        var normalizedPath: String? = null
        try {
            // A wipe in progress refuses or stops the import before it creates a recording.
            result = container.wipeGate.guard {
                importNow(absolutePath, source, titleHint, startedAtEpochSeconds, recoveredAtMs) { normalizedPath = it }
            } ?: ImportResult.Failed(WIPE_IN_PROGRESS)
            return result
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                discardSourceCopy(absolutePath, source, result)
                discardUnusedOutput(normalizedPath, absolutePath, result)
            }
        }
    }

    /** Runs [block] so that a wipe refuses or stops it, like an import. Null when it did. */
    suspend fun <T> underWipeGate(block: suspend () -> T): T? = container.wipeGate.guard(block)

    /**
     * The app's copy of the source has done its job once the import ends, however it ended ([result] is null when
     * cancelled). A watched folder's copy stays while it is useful, or every sync would copy the whole file again;
     * it goes with its recording.
     */
    private fun discardSourceCopy(path: String, source: String, result: ImportResult?) {
        val refused = result is ImportResult.Deleted || result == ImportResult.Failed(WIPE_IN_PROGRESS)
        val keep = source == WATCHED_SOURCE && !refused
        if (!keep && isImportCacheCopy(path)) deleteFileIfPresent(path)
    }

    /**
     * A failed or cancelled import leaves the WAV it wrote, unless a row already points at it: a throw after the row
     * exists (disk full, enqueue) or a cancel must not delete the only copy of a recording that is in the library.
     * (A duplicate or deleted result already discarded its WAV in [discardNormalized].)
     */
    private suspend fun discardUnusedOutput(wavPath: String?, sourcePath: String, result: ImportResult?) {
        if (wavPath == null || wavPath == sourcePath || !(result == null || result is ImportResult.Failed)) return
        val inUse = runCatching {
            container.repository.getAllRecordingFileRefs().any { (_, path) -> path != null && isSamePath(path, wavPath) }
        }.getOrDefault(true) // unknown: keep it
        if (!inUse) deleteFileIfPresent(wavPath)
    }

    /** A file shared into the app. Its cache copy goes when the import ends, like a picked one. */
    suspend fun importShared(copyPath: String, recoveredAtMs: Long? = null): ImportResult =
        importFile(copyPath, source = "share", recoveredAtMs = recoveredAtMs)

    /**
     * [importShared] owned by the app, not the caller: if the screen that received the share is recreated mid-import the
     * import still finishes and the recording exists; only this wait is cancelled. A wipe still stops it through the gate.
     */
    suspend fun importSharedDetached(copyPath: String): ImportResult = startSharedDetached(copyPath).await()

    /**
     * Starts the owned import and returns it. Imports of shares are serialized by the container's mutex, so the same file
     * shared twice, even from two activity instances, is a Duplicate rather than two recordings and two processing jobs.
     */
    fun startSharedDetached(copyPath: String, recoveredAtMs: Long? = null): kotlinx.coroutines.Deferred<ImportResult> =
        container.runDetached {
            try {
                container.shareImportMutex.withLock {
                    // Counted before any work, durably: a native decoder crash or a kill never reaches the finally below.
                    dev.velum.app.platform.markShareAttempt(copyPath)
                    importShared(copyPath, recoveredAtMs)
                }
            } finally {
                // Only reached when this process lives to the end of the import: a death before it leaves the marker for the next start.
                dev.velum.app.platform.markShareHandled(copyPath)
            }
        }

    private suspend fun importNow(
        absolutePath: String,
        source: String,
        titleHint: String?,
        startedAtEpochSeconds: Long?,
        recoveredAtMs: Long?,
        onNormalized: (String) -> Unit,
    ): ImportResult = withContext(Dispatchers.Default) {
        runCatching {
            val name = absolutePath.substringAfterLast('/')
            if (!ImportAudioFormats.isSupportedFileName(name) &&
                !name.contains('.') // allow extensionless SAF names; normalizer will fail clearly
            ) {
                // Still try — MIME may have been audio without extension.
            }
            val sourceHash = sha256File(absolutePath)
            val existingBySource = importedRecording(sourceHash)
            if (existingBySource != null) {
                rememberWatchedCopy(source, existingBySource, absolutePath)
                return@withContext ImportResult.Duplicate(existingBySource, sourceHash)
            }
            if (source == WATCHED_SOURCE && wasDeleted(sourceHash)) return@withContext ImportResult.Deleted(sourceHash)
            if (recoveredAtMs != null && deletedSince(sourceHash, recoveredAtMs)) return@withContext ImportResult.Deleted(sourceHash)
            val normalized = normalize(absolutePath)
            onNormalized(normalized.wavPath)
            val contentHash = normalized.contentSha256
            if (recoveredAtMs != null && deletedSince(contentHash, recoveredAtMs)) {
                discardNormalized(normalized.wavPath, keptBy = null)
                return@withContext ImportResult.Deleted(contentHash)
            }
            val existingByContent = importedRecording(contentHash)
            if (existingByContent != null) {
                // Removed before the key is written: with the key, no later import would come back to clean this up.
                discardNormalized(normalized.wavPath, keptBy = existingByContent)
                // Same audio after normalize — also record source hash for next lookup.
                container.repository.setMeta("import_sha256_$sourceHash", existingByContent)
                rememberWatchedCopy(source, existingByContent, absolutePath)
                return@withContext ImportResult.Duplicate(existingByContent, contentHash)
            }
            if (source == WATCHED_SOURCE && wasDeleted(contentHash)) {
                // Tombstoned by source too: later syncs skip this copy without decoding it, and a codec update
                // that changes the hash can't bring the deleted audio back. Removed first: a crash in between only
                // decodes and removes it once more.
                discardNormalized(normalized.wavPath, keptBy = null)
                container.repository.setMeta(IMPORT_DELETED_PREFIX + sourceHash, epochSecondsNow().toString())
                return@withContext ImportResult.Deleted(contentHash)
            }
            val title = titleHint ?: ImportTitles.forImport(absolutePath, name)
            val durationSeconds = (normalized.durationMs / 1000L).coerceAtLeast(0L)
            val id = container.createLocalRecording(
                title = title,
                source = source,
                localPath = normalized.wavPath,
                status = "queued",
            )
            if (startedAtEpochSeconds != null) {
                // Title already set; duration/size updated below.
            }
            container.repository.updateLocalPath(
                id = id,
                localPath = normalized.wavPath,
                durationSeconds = durationSeconds,
                sizeBytes = 0L,
            )
            // Store both keys so source-path re-imports and content-identical WAVs dedupe.
            container.repository.setMeta("import_sha256_$sourceHash", id)
            if (contentHash != sourceHash) {
                container.repository.setMeta("import_sha256_$contentHash", id)
            }
            // A file the user chose again is wanted again: its watched-folder tombstone goes.
            container.repository.deleteMeta(IMPORT_DELETED_PREFIX + sourceHash)
            container.repository.deleteMeta(IMPORT_DELETED_PREFIX + contentHash)
            rememberWatchedCopy(source, id, absolutePath)
            // Resolve AutoFlow before first enqueue so KEEP cannot drop template/model.
            val match = container.repository.findMatchingAutoflow(
                source = source,
                durationSeconds = durationSeconds,
                title = title.orEmpty(),
            )
            container.enqueueProcessing(
                recordingId = id,
                templateId = resolveTemplateForEnqueue(container.repository, match?.template_id),
                retranscribe = true,
                apiModelOverride = match?.let { AutoFlowResolve.modelOverride(it) },
            )
            ImportResult.Created(id, normalized.wavPath, contentHash)
        }.getOrElse {
            // A cancelled import is not a failed one: let the cancellation through (and the normalizer clean up).
            if (it is kotlinx.coroutines.CancellationException) throw it
            if (it is WipeInProgressException) return@getOrElse ImportResult.Failed(WIPE_IN_PROGRESS)
            ImportResult.Failed(it.message ?: it::class.simpleName ?: "import_failed")
        }
    }

    /**
     * Removes a normalized copy no recording will use. Its name carries the source hash, so it can only be another
     * recording's file if both hashes match, which a platform hashing the source could do: [keptBy] is checked anyway.
     */
    private suspend fun discardNormalized(wavPath: String, keptBy: String?) {
        val keptPath = keptBy?.let { container.repository.getRecording(it)?.local_path }
        // The string check too: isSamePath is false when it can't resolve a path, and the file must not go then.
        if (keptPath != null && (keptPath == wavPath || isSamePath(keptPath, wavPath))) return
        withContext(Dispatchers.IO) { deleteFileAtPath(wavPath) }
    }

    private suspend fun rememberWatchedCopy(source: String, recordingId: String, path: String) {
        if (source == WATCHED_SOURCE && isImportCacheCopy(path)) {
            container.repository.addImportCopy(recordingId, sha256Bytes(path.encodeToByteArray()), path)
        }
    }

    /** True when [hash] was deleted at or after [sinceMs] (tombstone values are epoch seconds). */
    private suspend fun deletedSince(hash: String, sinceMs: Long): Boolean {
        val at = container.repository.getMeta(IMPORT_DELETED_PREFIX + hash)?.toLongOrNull() ?: return false
        return at * 1000 >= sinceMs / 1000 * 1000
    }

    private suspend fun wasDeleted(hash: String) = container.repository.getMeta(IMPORT_DELETED_PREFIX + hash) != null

    /** The recording this audio was imported as, or null when there is none or it no longer exists (#327). */
    private suspend fun importedRecording(hash: String): String? =
        container.repository.getMeta("import_sha256_$hash")
            ?.takeIf { it.isNotBlank() && container.repository.getRecording(it) != null }
}

private const val WIPE_IN_PROGRESS = "wipe_in_progress"

/** Source of watched-folder imports, the only imports that skip files whose recording was deleted. */
internal const val WATCHED_SOURCE = "watched"
