package dev.velum.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import dev.velum.core.db.Glossary_term
import dev.velum.core.db.Recording
import dev.velum.core.db.Speaker
import dev.velum.core.db.Template
import dev.velum.core.db.VelumDatabase
import dev.velum.core.search.FtsIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VelumRepository(
    private val db: VelumDatabase,
    private val driver: SqlDriver,
) {
    private val q get() = db.velumQueries

    constructor(handle: DatabaseHandle) : this(handle.database, handle.driver)

    init {
        FtsIndex.ensureSchema(driver)
        SeedData.maybeSeed(db)
        rebuildFtsIndex()
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

    fun observeGlossary(): Flow<List<Glossary_term>> =
        q.selectGlossary().asFlow().mapToList(Dispatchers.Default)

    suspend fun getRecording(id: String): Recording? = withContext(Dispatchers.Default) {
        q.selectRecordingById(id).executeAsOneOrNull()
    }

    suspend fun getTemplate(id: String): Template? = withContext(Dispatchers.Default) {
        q.selectTemplateById(id).executeAsOneOrNull()
    }

    suspend fun searchRecordingIdsFts(matchQuery: String): List<String> =
        withContext(Dispatchers.Default) {
            FtsIndex.searchRecordingIds(driver, matchQuery)
        }

    suspend fun searchTemplateIdsFts(matchQuery: String): List<String> =
        withContext(Dispatchers.Default) {
            FtsIndex.searchTemplateIds(driver, matchQuery)
        }

    suspend fun setAutoflowEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.Default) {
        q.updateAutoflowEnabled(if (enabled) 1 else 0, id)
    }

    suspend fun setAutoflowNotify(id: String, notify: Boolean) = withContext(Dispatchers.Default) {
        q.updateAutoflowNotify(if (notify) 1 else 0, id)
    }

    suspend fun updateAutoflow(
        id: String,
        name: String,
        enabled: Boolean,
        wakePhrase: String?,
        durationMinSeconds: Long,
        durationMaxSeconds: Long,
        sourceFilter: String,
        templateId: String?,
        autoLabelVoice: Boolean,
        language: String,
        notify: Boolean,
        postAction: String? = null,
    ) = withContext(Dispatchers.Default) {
        q.updateAutoflow(
            name = name,
            enabled = if (enabled) 1 else 0,
            wake_phrase = wakePhrase,
            duration_min_seconds = durationMinSeconds,
            duration_max_seconds = durationMaxSeconds,
            source_filter = sourceFilter,
            template_id = templateId,
            auto_label_voice = if (autoLabelVoice) 1 else 0,
            language = language,
            notify = if (notify) 1 else 0,
            post_action = postAction,
            id = id,
        )
    }

    suspend fun upsertAutoflow(
        id: String,
        name: String,
        enabled: Boolean,
        wakePhrase: String?,
        durationMinSeconds: Long,
        durationMaxSeconds: Long,
        sourceFilter: String,
        templateId: String?,
        autoLabelVoice: Boolean,
        language: String,
        notify: Boolean,
        postAction: String? = null,
    ) = withContext(Dispatchers.Default) {
        q.insertAutoflow(
            id = id,
            name = name,
            enabled = if (enabled) 1 else 0,
            wake_phrase = wakePhrase,
            duration_min_seconds = durationMinSeconds,
            duration_max_seconds = durationMaxSeconds,
            source_filter = sourceFilter,
            template_id = templateId,
            auto_label_voice = if (autoLabelVoice) 1 else 0,
            language = language,
            notify = if (notify) 1 else 0,
            post_action = postAction,
        )
    }

    suspend fun trashRecording(id: String) = withContext(Dispatchers.Default) {
        q.trashRecording(id)
    }

    suspend fun moveRecordingToFolder(id: String, folderId: String?) = withContext(Dispatchers.Default) {
        q.updateRecordingFolder(folderId, id)
    }

    suspend fun updateRecordingTitle(id: String, title: String) = withContext(Dispatchers.Default) {
        q.updateRecordingTitle(title, id)
        reindexRecording(id)
    }

    suspend fun addMemory(id: String, content: String) = withContext(Dispatchers.Default) {
        q.insertMemory(id, content, epochSeconds())
    }

    suspend fun deleteMemory(id: String) = withContext(Dispatchers.Default) {
        q.deleteMemory(id)
    }

    suspend fun listMemories(): List<String> = withContext(Dispatchers.Default) {
        q.selectMemories().executeAsList().map { it.content }
    }

    suspend fun listGlossaryTerms(): List<GlossaryTerm> = withContext(Dispatchers.Default) {
        q.selectGlossary().executeAsList().map {
            GlossaryTerm(id = it.id, term = it.term, definition = it.definition)
        }
    }

    suspend fun addGlossaryTerm(id: String, term: String, definition: String = "") =
        withContext(Dispatchers.Default) {
            q.insertGlossary(id, term.trim(), definition.trim(), epochSeconds())
        }

    suspend fun deleteGlossaryTerm(id: String) = withContext(Dispatchers.Default) {
        q.deleteGlossary(id)
    }

    /**
     * Prompt fragment for ASR initial_prompt and LLM system prompts.
     * Glossary terms first, then memory facts when [includeMemory] is true.
     */
    suspend fun personalizationPromptFragment(includeMemory: Boolean): String =
        withContext(Dispatchers.Default) {
            buildString {
                val glossary = q.selectGlossary().executeAsList()
                if (glossary.isNotEmpty()) {
                    append("Glossary (prefer these spellings): ")
                    append(glossary.joinToString("; ") { g ->
                        if (g.definition.isBlank()) g.term else "${g.term}: ${g.definition}"
                    })
                    append('\n')
                }
                if (includeMemory) {
                    val memories = q.selectMemories().executeAsList()
                    if (memories.isNotEmpty()) {
                        append("User memory:\n")
                        memories.forEach { append("- ").append(it.content).append('\n') }
                    }
                }
            }.trim()
        }

    suspend fun recordingCount(): Long = withContext(Dispatchers.Default) {
        q.countRecordings().executeAsOne()
    }

    suspend fun updateTranscript(id: String, transcript: String) = withContext(Dispatchers.Default) {
        q.updateTranscript(transcript, id)
        reindexRecording(id)
    }

    suspend fun updateSummary(id: String, summaryMarkdown: String) = withContext(Dispatchers.Default) {
        q.updateSummary(summaryMarkdown, id)
        reindexRecording(id)
    }

    suspend fun updateStatus(id: String, status: String) = withContext(Dispatchers.Default) {
        q.updateStatus(status, id)
    }

    /**
     * Persist a local template as name + Markdown/plain prompt JSON body.
     * [systemPrompt] is the LLM instruction; may be plain Markdown or a JSON object string.
     */
    suspend fun upsertTemplate(
        id: String,
        name: String,
        category: String,
        description: String,
        systemPrompt: String,
        author: String = "You",
        builtin: Boolean = false,
        uses: Long = 0,
    ) = withContext(Dispatchers.Default) {
        val n = name.trim()
        val c = category.trim().ifBlank { "General" }
        val d = description.trim()
        q.insertTemplate(
            id = id,
            name = n,
            category = c,
            description = d,
            author = author,
            system_prompt = systemPrompt,
            uses = uses,
            builtin = if (builtin) 1 else 0,
            created_at = epochSeconds(),
        )
        indexTemplate(id, n, d, c, systemPrompt)
    }

    suspend fun deleteTemplate(id: String) = withContext(Dispatchers.Default) {
        q.deleteTemplate(id)
        FtsIndex.deleteTemplate(driver, id)
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
        indexRecording(id, title, transcript, summaryMarkdown)
    }

    /** Reindex every recording/template into FTS (after seed or migration). */
    fun rebuildFtsIndex() {
        db.transaction {
            q.selectAllRecordings().executeAsList().forEach { r ->
                indexRecording(r.id, r.title, r.transcript, r.summary_markdown)
            }
            q.selectTemplates().executeAsList().forEach { t ->
                indexTemplate(t.id, t.name, t.description, t.category, t.system_prompt)
            }
        }
    }

    private fun reindexRecording(id: String) {
        val r = q.selectRecordingById(id).executeAsOneOrNull() ?: return
        indexRecording(r.id, r.title, r.transcript, r.summary_markdown)
    }

    private fun indexRecording(
        id: String,
        title: String,
        transcript: String?,
        summaryMarkdown: String?,
    ) {
        FtsIndex.upsertRecording(driver, id, title, transcript, summaryMarkdown)
    }

    private fun indexTemplate(
        id: String,
        name: String,
        description: String,
        category: String,
        systemPrompt: String,
    ) {
        FtsIndex.upsertTemplate(driver, id, name, description, category, systemPrompt)
    }

    data class SpeakerUpsert(
        val id: String,
        val label: String,
        val displayName: String?,
        val talkSeconds: Long,
    )

    data class GlossaryTerm(
        val id: String,
        val term: String,
        val definition: String,
    )

    fun observeSpeakers(recordingId: String): Flow<List<Speaker>> =
        q.selectSpeakersForRecording(recordingId).asFlow().mapToList(Dispatchers.Default)

    suspend fun getSpeakers(recordingId: String): List<Speaker> = withContext(Dispatchers.Default) {
        q.selectSpeakersForRecording(recordingId).executeAsList()
    }

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

    suspend fun renameSpeaker(
        recordingId: String,
        speakerId: String,
        displayName: String,
    ) = withContext(Dispatchers.Default) {
        val speakers = q.selectSpeakersForRecording(recordingId).executeAsList()
        val target = speakers.firstOrNull { it.id == speakerId } ?: return@withContext
        val previous = target.display_name?.takeIf { it.isNotBlank() }
            ?: "Speaker ${target.label.trimStart('S', 's')}"
        q.updateSpeakerDisplayName(displayName, speakerId)
        val recording = q.selectRecordingById(recordingId).executeAsOneOrNull() ?: return@withContext
        val transcript = recording.transcript
        if (!transcript.isNullOrBlank() && previous != displayName) {
            val updated = transcript
                .replace("**$previous**", "**$displayName**")
                .replace("**${target.label}**", "**$displayName**")
            if (updated != transcript) {
                q.updateTranscript(updated, recordingId)
            }
        }
    }

    private fun epochSeconds(): Long = epochSecondsNow()
}

expect fun epochSecondsNow(): Long
