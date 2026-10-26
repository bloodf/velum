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

    private fun epochSeconds(): Long = epochSecondsNow()
}

expect fun epochSecondsNow(): Long
