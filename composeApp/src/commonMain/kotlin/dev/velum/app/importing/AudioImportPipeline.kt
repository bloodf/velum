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
            val hash = sha256File(absolutePath)
            val existing = container.repository.getMeta("import_sha256_$hash")
            if (!existing.isNullOrBlank()) {
                return@withContext ImportResult.Duplicate(existing, hash)
            }
            val normalized = normalizer.normalizeToWav(absolutePath)
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
            container.repository.setMeta("import_sha256_${normalized.contentSha256}", id)
            container.enqueueProcessing(id, templateId = null, retranscribe = true)
            ImportResult.Created(id, normalized.wavPath, normalized.contentSha256)
        }.getOrElse { ImportResult.Failed(it.message ?: it::class.simpleName ?: "import_failed") }
    }
}
