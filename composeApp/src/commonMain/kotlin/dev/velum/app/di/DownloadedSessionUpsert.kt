package dev.velum.app.di

import dev.velum.app.platform.deleteFileAtPath
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.writeBytesAtomically
import dev.velum.app.ui.DownloadedSessionAudio
import dev.velum.core.data.RecordingIds
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.epochSecondsNow

/**
 * Idempotent device-session → library recording upsert (#113).
 *
 * Keyed on stable device session identity (not a fresh [RecordingIds.newId] each time).
 * Re-download replaces audio atomically and keeps transcript / summary / status / title.
 */
internal object DownloadedSessionUpsert {
    data class Result(
        val recordingId: String,
        /** True when a new library row was inserted (first download). */
        val created: Boolean,
    )

    suspend fun persist(
        repository: VelumRepository,
        session: DownloadedSessionAudio,
        formatTitle: (startedAtEpochSeconds: Long) -> String,
        writeAudio: (absolutePath: String, bytes: ByteArray) -> Unit = { path, bytes ->
            writeBytesAtomically(path, bytes)
        },
        deleteAudio: (absolutePath: String) -> Boolean = ::deleteFileAtPath,
    ): Result {
        val existing = repository.findRecordingByDeviceSessionId(session.sessionId)
        val id = existing?.id ?: RecordingIds.newId()
        val ext = when {
            looksLikeOgg(session.decryptedBytes) -> "ogg"
            looksLikeWav(session.decryptedBytes) -> "wav"
            else -> "bin"
        }
        val path = "${recordingsDirectoryPath()}/$id.$ext"
        val previousPath = existing?.local_path?.takeIf { it.isNotBlank() }

        writeAudio(path, session.decryptedBytes)

        val sizeBytes = session.decryptedBytes.size.toLong()
        try {
            if (existing == null) {
                val started = if (session.startedAtEpochSeconds > 1_000_000_000L) {
                    session.startedAtEpochSeconds
                } else {
                    epochSecondsNow()
                }
                repository.insertRecording(
                    id = id,
                    sessionId = session.sessionId,
                    folderId = null,
                    title = formatTitle(started),
                    source = "note_pro",
                    deviceSerial = session.deviceSerial,
                    startedAt = started,
                    durationSeconds = 0,
                    sizeBytes = sizeBytes,
                    localPath = path,
                    transcript = null,
                    summaryMarkdown = null,
                    status = "queued",
                    exclusive = true,
                )
            } else {
                repository.updateLocalPath(
                    id = id,
                    localPath = path,
                    durationSeconds = existing.duration_seconds,
                    sizeBytes = sizeBytes,
                )
                if (previousPath != null && previousPath != path) {
                    deleteAudio(previousPath)
                }
            }
        } catch (t: Throwable) {
            if (existing == null) {
                deleteAudio(path)
            } else if (previousPath != null && previousPath != path) {
                // New-extension write failed to land in DB — drop the new file, keep previous.
                deleteAudio(path)
            }
            throw t
        }
        return Result(recordingId = id, created = existing == null)
    }

    private fun looksLikeOgg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()

    private fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
}
