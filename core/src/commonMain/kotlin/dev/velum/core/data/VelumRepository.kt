package dev.velum.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.velum.core.db.Recording
import dev.velum.core.db.Template
import dev.velum.core.db.VelumDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VelumRepository(private val db: VelumDatabase) {
    private val q get() = db.velumQueries

    init {
        SeedData.maybeSeed(db)
    }

    fun observeRecordings(): Flow<List<Recording>> =
        q.selectAllRecordings().asFlow().mapToList(Dispatchers.Default)

    fun observeTrashed(): Flow<List<Recording>> =
        q.selectTrashedRecordings().asFlow().mapToList(Dispatchers.Default)

    fun observeRecording(id: String): Flow<Recording?> =
        q.selectRecordingById(id).asFlow().mapToOneOrNull(Dispatchers.Default)

    fun observeTemplates(): Flow<List<Template>> =
        q.selectTemplates().asFlow().mapToList(Dispatchers.Default)

    fun observeAutoflows() =
        q.selectAutoflows().asFlow().mapToList(Dispatchers.Default)

    fun observeFolders() =
        q.selectAllFolders().asFlow().mapToList(Dispatchers.Default)

    fun observeMemories() =
        q.selectMemories().asFlow().mapToList(Dispatchers.Default)

    suspend fun getRecording(id: String): Recording? = withContext(Dispatchers.Default) {
        q.selectRecordingById(id).executeAsOneOrNull()
    }

    suspend fun getTemplate(id: String): Template? = withContext(Dispatchers.Default) {
        q.selectTemplateById(id).executeAsOneOrNull()
    }

    suspend fun setAutoflowEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.Default) {
        q.updateAutoflowEnabled(if (enabled) 1 else 0, id)
    }

    suspend fun trashRecording(id: String) = withContext(Dispatchers.Default) {
        q.trashRecording(id)
    }

    suspend fun addMemory(id: String, content: String) = withContext(Dispatchers.Default) {
        q.insertMemory(id, content, epochSeconds())
    }

    suspend fun recordingCount(): Long = withContext(Dispatchers.Default) {
        q.countRecordings().executeAsOne()
    }

    suspend fun updateTranscript(id: String, transcript: String) = withContext(Dispatchers.Default) {
        q.updateTranscript(transcript, id)
    }

    suspend fun updateSummary(id: String, summaryMarkdown: String) = withContext(Dispatchers.Default) {
        q.updateSummary(summaryMarkdown, id)
    }

    suspend fun updateStatus(id: String, status: String) = withContext(Dispatchers.Default) {
        q.updateStatus(status, id)
    }

    suspend fun insertRecording(
        id: String,
        sessionId: Long?,
        folderId: String?,
        title: String,
        source: String,
        deviceSerial: String?,
        startedAt: Long,
        durationSeconds: Long,
        sizeBytes: Long,
        localPath: String?,
        transcript: String?,
        summaryMarkdown: String?,
        status: String,
        trashed: Long = 0,
        createdAt: Long = epochSeconds(),
    ) = withContext(Dispatchers.Default) {
        q.insertRecording(
            id = id,
            session_id = sessionId,
            folder_id = folderId,
            title = title,
            source = source,
            device_serial = deviceSerial,
            started_at = startedAt,
            duration_seconds = durationSeconds,
            size_bytes = sizeBytes,
            local_path = localPath,
            transcript = transcript,
            summary_markdown = summaryMarkdown,
            status = status,
            trashed = trashed,
            created_at = createdAt,
        )
    }

    data class SpeakerUpsert(
        val id: String,
        val label: String,
        val displayName: String?,
        val talkSeconds: Long,
    )

    suspend fun updateSpeakers(recordingId: String, speakers: List<SpeakerUpsert>) =
        withContext(Dispatchers.Default) {
            db.transaction {
                q.deleteSpeakersForRecording(recordingId)
                speakers.forEach { s ->
                    q.insertSpeaker(
                        id = s.id,
                        recording_id = recordingId,
                        label = s.label,
                        display_name = s.displayName,
                        talk_seconds = s.talkSeconds,
                    )
                }
            }
        }

    private fun epochSeconds(): Long = epochSecondsNow()
}

expect fun epochSecondsNow(): Long
