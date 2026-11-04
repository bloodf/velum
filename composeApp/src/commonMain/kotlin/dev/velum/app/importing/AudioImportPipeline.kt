package dev.velum.app.importing

import dev.velum.ai.pipeline.resolveTemplateForEnqueue
import dev.velum.app.di.AppContainer
import dev.velum.core.autoflow.AutoFlowResolve
import dev.velum.app.platform.AudioNormalizer
import dev.velum.app.platform.NormalizedAudio
import dev.velum.app.platform.ImportAudioFormats
import dev.velum.app.platform.deleteFileAtPath
import dev.velum.app.platform.isSamePath
import dev.velum.app.platform.sha256File
import dev.velum.core.data.VelumRepository.Companion.IMPORT_DELETED_PREFIX
import dev.velum.core.data.epochSecondsNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
    ): ImportResult = container.wipeGate.guard { importNow(absolutePath, source, titleHint, startedAtEpochSeconds) }
        // A wipe in progress refuses or stops the import before it creates a recording.
        ?: ImportResult.Failed(WIPE_IN_PROGRESS)

    /** Runs [block] so that a wipe refuses or stops it, like an import. Null when it did. */
    suspend fun <T> underWipeGate(block: suspend () -> T): T? = container.wipeGate.guard(block)

    /** A file shared into the app: its cache copy is deleted when a wipe refuses the import, so it can't outlive the wipe. */
    suspend fun importShared(copyPath: String): ImportResult = importFile(copyPath, source = "share").also { result ->
        if (result == ImportResult.Failed(WIPE_IN_PROGRESS)) withContext(Dispatchers.IO) { deleteFileAtPath(copyPath) }
    }

    private suspend fun importNow(
        absolutePath: String,
        source: String,
        titleHint: String?,
        startedAtEpochSeconds: Long?,
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
                return@withContext ImportResult.Duplicate(existingBySource, sourceHash)
            }
            if (source == WATCHED_SOURCE && wasDeleted(sourceHash)) return@withContext ImportResult.Deleted(sourceHash)
            val normalized = normalize(absolutePath)
            val contentHash = normalized.contentSha256
            val existingByContent = importedRecording(contentHash)
            if (existingByContent != null) {
                // Same audio after normalize — also record source hash for next lookup.
                container.repository.setMeta("import_sha256_$sourceHash", existingByContent)
                discardNormalized(normalized.wavPath, keptBy = existingByContent)
                return@withContext ImportResult.Duplicate(existingByContent, contentHash)
            }
            if (source == WATCHED_SOURCE && wasDeleted(contentHash)) {
                // Tombstoned by source too: later syncs skip this copy without decoding it, and a codec update
                // that changes the hash can't bring the deleted audio back.
                container.repository.setMeta(IMPORT_DELETED_PREFIX + sourceHash, epochSecondsNow().toString())
                discardNormalized(normalized.wavPath, keptBy = null)
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
            ImportResult.Failed(it.message ?: it::class.simpleName ?: "import_failed")
        }
    }

    /**
     * Removes a normalized copy no recording will use. Its name carries the source hash, so it can only be another
     * recording's file if both hashes match, which a platform hashing the source could do: [keptBy] is checked anyway.
     */
    private suspend fun discardNormalized(wavPath: String, keptBy: String?) {
        val keptPath = keptBy?.let { container.repository.getRecording(it)?.local_path }
        if (keptPath != null && isSamePath(keptPath, wavPath)) return
        withContext(Dispatchers.IO) { deleteFileAtPath(wavPath) }
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
