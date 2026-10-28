package dev.velum.app.importing

import dev.velum.app.di.AppContainer
import dev.velum.app.platform.AudioNormalizer
import dev.velum.app.platform.ImportAudioFormats
import dev.velum.app.platform.sha256File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class ImportResult {
    data class Created(val recordingId: String, val wavPath: String, val sha256: String) : ImportResult()
    data class Duplicate(val existingRecordingId: String, val sha256: String) : ImportResult()
    data class Failed(val message: String) : ImportResult()
}

/**
 * Copy/normalize external audio into the same recording processor pipeline, with SHA-256 dedupe.
 */
class AudioImportPipeline(
    private val container: AppContainer,
    private val normalizer: AudioNormalizer = AudioNormalizer(),
) {
    suspend fun importFile(
        absolutePath: String,
        source: String = "import",
        titleHint: String? = null,
        startedAtEpochSeconds: Long? = null,
    ): ImportResult = withContext(Dispatchers.Default) {
        runCatching {
            val name = absolutePath.substringAfterLast('/')
            if (!ImportAudioFormats.isSupportedFileName(name) &&
                !name.contains('.') // allow extensionless SAF names; normalizer will fail clearly
            ) {
                // Still try — MIME may have been audio without extension.
            }
            val sourceHash = sha256File(absolutePath)
            val existingBySource = container.repository.getMeta("import_sha256_$sourceHash")
            if (!existingBySource.isNullOrBlank()) {
                return@withContext ImportResult.Duplicate(existingBySource, sourceHash)
            }
            val normalized = normalizer.normalizeToWav(absolutePath)
            val contentHash = normalized.contentSha256
            val existingByContent = container.repository.getMeta("import_sha256_$contentHash")
            if (!existingByContent.isNullOrBlank()) {
                // Same audio after normalize — also record source hash for next lookup.
                container.repository.setMeta("import_sha256_$sourceHash", existingByContent)
                return@withContext ImportResult.Duplicate(existingByContent, contentHash)
            }
            val id = container.createLocalRecording(
                title = titleHint ?: name.substringBeforeLast('.').ifBlank { null },
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
                durationSeconds = (normalized.durationMs / 1000L).coerceAtLeast(0L),
                sizeBytes = 0L,
            )
            // Store both keys so source-path re-imports and content-identical WAVs dedupe.
            container.repository.setMeta("import_sha256_$sourceHash", id)
            if (contentHash != sourceHash) {
                container.repository.setMeta("import_sha256_$contentHash", id)
            }
            container.enqueueProcessing(id, templateId = null, retranscribe = true)
            ImportResult.Created(id, normalized.wavPath, contentHash)
        }.getOrElse { ImportResult.Failed(it.message ?: it::class.simpleName ?: "import_failed") }
    }
}
