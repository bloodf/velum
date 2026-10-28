package dev.velum.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import dev.velum.core.autoflow.AutoFlowMatcher
import dev.velum.core.autoflow.AutoFlowResolve
import dev.velum.core.db.Autoflow
import dev.velum.core.db.Glossary_term
import dev.velum.core.db.Recording
import dev.velum.core.db.Speaker
import dev.velum.core.db.Summary_version
import dev.velum.core.db.Template
import dev.velum.core.db.VelumDatabase
import dev.velum.core.search.FtsIndex
import dev.velum.core.templates.PlaudFullTemplateAsset
import dev.velum.core.templates.PlaudTemplateCatalog
import dev.velum.core.templates.TemplateTagVocabulary
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VelumRepository(
    private val db: VelumDatabase,
    private val driver: SqlDriver,
) {
    private val q get() = db.velumQueries

    /** False on hosts without SQLite FTS5 (e.g. Robolectric). Search falls back to ListSearch. */
    val ftsAvailable: Boolean

    constructor(handle: DatabaseHandle) : this(handle.database, handle.driver)

    init {
        val fts = FtsIndex.ensureSchema(driver)
        ftsAvailable = fts.available
        val seeded = SeedData.maybeSeed(db)
        val bodyDeduped = SeedData.maybeReconcileBodyDedupes(db)
        backfillSummaryVersionsFromRecording()
        if (ftsAvailable) {
            when {
                // Full rebuild after seed, template_fts schema upgrade, or body-dedupe reconcile.
                fts.templateSchemaRecreated || seeded || bodyDeduped -> rebuildFtsIndex()
                else -> {
                    // Empty library makes recording_fts empty — only rebuild the empty side
                    // so we do not rewrite every template on every cold start.
                    val emptiness = FtsIndex.emptiness(driver)
                    if (emptiness.recordingsEmpty || emptiness.templatesEmpty) {
                        rebuildFtsIndex(
                            recordings = emptiness.recordingsEmpty,
                            templates = emptiness.templatesEmpty,
                        )
                    }
                }
            }
        }
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

    suspend fun listAutoflows(): List<Autoflow> = withContext(Dispatchers.Default) {
        q.selectAutoflows().executeAsList()
    }

    /**
     * First enabled AutoFlow matching [source]/duration]/title]/transcript].
     * Call at download/import enqueue so template/model ride the first WorkManager request.
     */
    suspend fun findMatchingAutoflow(
        source: String,
        durationSeconds: Long,
        title: String = "",
        transcript: String? = null,
    ): Autoflow? = withContext(Dispatchers.Default) {
        AutoFlowResolve.firstMatch(
            flows = q.selectAutoflows().executeAsList(),
            candidate = AutoFlowMatcher.Candidate(
                source = source,
                durationSeconds = durationSeconds,
                title = title,
                transcript = transcript,
            ),
        )
    }

    fun observeFolders() =
        q.selectAllFolders().asFlow().mapToList(Dispatchers.Default)

    fun observeMemories() =
        q.selectMemories().asFlow().mapToList(Dispatchers.Default)

    fun observeGlossary(): Flow<List<Glossary_term>> =
        q.selectGlossary().asFlow().mapToList(Dispatchers.Default)

    suspend fun getRecording(id: String): Recording? = withContext(Dispatchers.Default) {
        q.selectRecordingById(id).executeAsOneOrNull()
    }

    suspend fun getAllRecordings(): List<Recording> = withContext(Dispatchers.Default) {
        q.selectAllRecordings().executeAsList()
    }

    suspend fun getTemplate(id: String): Template? = withContext(Dispatchers.Default) {
        q.selectTemplateById(id).executeAsOneOrNull()
    }

    suspend fun searchRecordingIdsFts(matchQuery: String): List<String> =
        withContext(Dispatchers.Default) {
            if (!ftsAvailable) emptyList() else FtsIndex.searchRecordingIds(driver, matchQuery)
        }

    suspend fun searchTemplateIdsFts(matchQuery: String): List<String> =
        withContext(Dispatchers.Default) {
            if (!ftsAvailable) emptyList() else FtsIndex.searchTemplateIds(driver, matchQuery)
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
        modelId: String? = null,
        endpointProfileId: String? = null,
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
            model_id = modelId?.takeIf { it.isNotBlank() },
            endpoint_profile_id = endpointProfileId?.takeIf { it.isNotBlank() },
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
        modelId: String? = null,
        endpointProfileId: String? = null,
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
            model_id = modelId?.takeIf { it.isNotBlank() },
            endpoint_profile_id = endpointProfileId?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun deleteAutoflow(id: String) = withContext(Dispatchers.Default) {
        q.deleteAutoflow(id)
    }

    suspend fun trashRecording(id: String) = withContext(Dispatchers.Default) {
        q.trashRecording(id)
    }

    suspend fun restoreRecording(id: String) = withContext(Dispatchers.Default) {
        q.restoreRecording(id)
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

    suspend fun updateLocalPath(
        id: String,
        localPath: String,
        durationSeconds: Long,
        sizeBytes: Long,
    ) = withContext(Dispatchers.Default) {
        q.updateLocalPath(localPath, durationSeconds, sizeBytes, id)
    }

    /**
     * Checkpoint mid-job (status + optional partial transcript/summary).
     * Null transcript/summary leaves the previous column value unchanged via coalesce in callers.
     */
    suspend fun updateProcessingCheckpoint(
        id: String,
        status: String,
        transcript: String? = null,
        summaryMarkdown: String? = null,
    ) = withContext(Dispatchers.Default) {
        db.transaction {
            val current = q.selectRecordingById(id).executeAsOneOrNull() ?: return@transaction
            val nextTranscript = transcript ?: current.transcript
            val nextSummary = summaryMarkdown ?: current.summary_markdown
            q.updateProcessingCheckpoint(status, nextTranscript, nextSummary, id)
            reindexRecording(id)
        }
    }

    suspend fun getMeta(key: String): String? = withContext(Dispatchers.Default) {
        q.getMeta(key).executeAsOneOrNull()
    }

    suspend fun setMeta(key: String, value: String) = withContext(Dispatchers.Default) {
        q.setMeta(key, value)
    }

    suspend fun isFirstRunDone(): Boolean = getMeta(META_FIRST_RUN_DONE) == "1"

    suspend fun markFirstRunDone() = setMeta(META_FIRST_RUN_DONE, "1")

    /**
     * Persist a local template as name + Markdown/plain prompt JSON body.
     * [systemPrompt] is the LLM instruction; may be plain Markdown or a JSON object string.
     * Custom templates use [featured]=false; Featured builtins use true.
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
        featured: Boolean = false,
        tags: List<String> = emptyList(),
        lang: String = "und",
        preferredModel: String? = null,
        preferredEndpointProfileId: String? = null,
    ) = withContext(Dispatchers.Default) {
        val n = name.trim()
        val c = category.trim().ifBlank { "General" }
        val d = description.trim()
        val tagsEncoded = TemplateTagVocabulary.encode(tags)
        val langNorm = lang.trim().ifBlank { "und" }
        q.insertTemplate(
            id = id,
            name = n,
            category = c,
            description = d,
            author = author,
            system_prompt = systemPrompt,
            uses = uses,
            builtin = if (builtin) 1 else 0,
            featured = if (featured) 1 else 0,
            tags = tagsEncoded,
            lang = langNorm,
            preferred_model = preferredModel?.takeIf { it.isNotBlank() },
            preferred_endpoint_profile_id = preferredEndpointProfileId?.takeIf { it.isNotBlank() },
            created_at = epochSeconds(),
        )
        indexTemplate(id, n, d, c, systemPrompt, tagsEncoded)
    }

    /**
     * Lazy-import the gzipped full Plaud catalog once (Discover "All").
     * Streams gunzip+JSONL line-by-line (no full ~29MB String). Preserves existing
     * [Template.uses] on id collision; sets featured from JSON.
     *
     * The "imported" meta flag is set only after a successful FTS rebuild so a
     * process kill mid-import cannot leave All templates present but unsearchable.
     * @return number of rows upserted, or 0 if already imported / asset empty.
     */
    suspend fun ensureFullTemplatesImported(): Int = withContext(Dispatchers.Default) {
        if (q.getMeta(SeedData.META_FULL_TEMPLATES_IMPORTED).executeAsOneOrNull() == "1") {
            return@withContext 0
        }
        val now = epochSeconds()
        var upserted = 0
        var sawAny = false
        db.transaction {
            PlaudFullTemplateAsset.forEachLine { line ->
                val entry = PlaudTemplateCatalog.parseJsonlLine(line) ?: return@forEachLine
                sawAny = true
                val existing = q.selectTemplateById(entry.id).executeAsOneOrNull()
                val uses = existing?.uses ?: entry.uses
                val createdAt = existing?.created_at ?: now
                val tagsEncoded = TemplateTagVocabulary.encode(
                    entry.tags.ifEmpty {
                        TemplateTagVocabulary.defaultsForCategory(entry.category)
                    },
                )
                val langNorm = entry.lang.ifBlank { "und" }
                q.insertTemplate(
                    id = entry.id,
                    name = entry.name,
                    category = entry.category,
                    description = entry.description,
                    author = "Velum",
                    system_prompt = entry.systemPrompt(),
                    uses = uses,
                    builtin = 1,
                    featured = if (entry.featured) 1 else 0,
                    tags = tagsEncoded,
                    lang = langNorm,
                    preferred_model = entry.preferredModel?.takeIf { it.isNotBlank() },
                    preferred_endpoint_profile_id =
                        entry.preferredEndpointProfileId?.takeIf { it.isNotBlank() },
                    created_at = createdAt,
                )
                upserted++
            }
        }
        if (upserted > 0 && ftsAvailable) rebuildFtsIndex()
        if (sawAny) {
            q.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
        }
        upserted
    }

    suspend fun deleteTemplate(id: String) = withContext(Dispatchers.Default) {
        q.deleteTemplate(id)
        if (ftsAvailable) FtsIndex.deleteTemplate(driver, id)
    }

    suspend fun deleteRecordingHard(id: String) = withContext(Dispatchers.Default) {
        q.deleteVersionsForRecording(id)
        q.deleteSpeakersForRecording(id)
        q.deleteRecordingHard(id)
        if (ftsAvailable) FtsIndex.deleteRecording(driver, id)
    }

    /**
     * Local wipe: recordings, custom templates, AutoFlows, memory, glossary. Built-ins reseed.
     * [deleteLocalFile] removes on-disk audio for each recording's `local_path` (privacy).
     */
    suspend fun wipeAllUserData(
        deleteLocalFile: (absolutePath: String) -> Unit = {},
    ) = withContext(Dispatchers.Default) {
        val audioPaths = q.selectAllRecordingIds().executeAsList().mapNotNull { id ->
            q.selectRecordingById(id).executeAsOneOrNull()?.local_path?.takeIf { it.isNotBlank() }
        }
        // Delete files BEFORE DB wipe so a crash mid-transaction cannot leave audio orphaned
        // after rows are gone (privacy). Second pass after DB is a safety net.
        audioPaths.forEach { path ->
            runCatching { deleteLocalFile(path) }
        }
        q.selectAllRecordingIds().executeAsList().forEach { id ->
            q.deleteVersionsForRecording(id)
            q.deleteSpeakersForRecording(id)
            q.deleteRecordingHard(id)
            if (ftsAvailable) FtsIndex.deleteRecording(driver, id)
        }
        q.deleteAllCustomTemplates()
        q.deleteAllAutoflows()
        q.deleteAllMemories()
        q.deleteAllGlossary()
        // Clear seed marker so maybeSeed re-inserts default AutoFlow + builtins.
        SeedData.clearSeedMarker(db)
        SeedData.maybeSeed(db)
        if (ftsAvailable) rebuildFtsIndex()
        audioPaths.forEach { path ->
            runCatching { deleteLocalFile(path) }
        }
    }

    data class StorageUsage(
        val recordingCount: Long,
        val bytesClaimed: Long,
    )

    /** Sum of recording `size_bytes` for non-trashed rows (library claim, not free disk). */
    suspend fun storageUsage(): StorageUsage = withContext(Dispatchers.Default) {
        val rows = q.selectAllRecordings().executeAsList()
        StorageUsage(
            recordingCount = rows.size.toLong(),
            bytesClaimed = rows.sumOf { it.size_bytes.coerceAtLeast(0) },
        )
    }

    /** Clear local audio path after optional file delete (keeps transcript/summary). */
    suspend fun clearLocalAudioPath(id: String) = withContext(Dispatchers.Default) {
        val rec = q.selectRecordingById(id).executeAsOneOrNull() ?: return@withContext
        q.updateLocalPath(null, rec.duration_seconds, 0, id)
    }

    /** Plain-text backup of local library metadata + notes (no audio blobs). */
    suspend fun exportLibraryBackupText(): String = withContext(Dispatchers.Default) {
        val recordings = q.selectAllRecordings().executeAsList()
        buildString {
            appendLine("# Velum local backup")
            appendLine("recordings=${recordings.size}")
            appendLine()
            recordings.forEach { r ->
                appendLine("## ${r.title.ifBlank { r.id }}")
                appendLine("id: ${r.id}")
                appendLine("source: ${r.source}")
                appendLine("status: ${r.status}")
                appendLine("duration_seconds: ${r.duration_seconds}")
                if (!r.transcript.isNullOrBlank()) {
                    appendLine()
                    appendLine("### Transcript")
                    appendLine(r.transcript)
                }
                if (!r.summary_markdown.isNullOrBlank()) {
                    appendLine()
                    appendLine("### Notes")
                    appendLine(r.summary_markdown)
                }
                appendLine()
            }
        }
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

    /**
     * Reindex recordings and/or templates into FTS (after seed, migration, or one-sided empty).
     * Prefer rebuilding only the empty side on cold start so an empty library does not
     * rewrite every template row.
     */
    fun rebuildFtsIndex(
        recordings: Boolean = true,
        templates: Boolean = true,
    ) {
        if (!ftsAvailable) return
        if (!recordings && !templates) return
        db.transaction {
            if (recordings && templates) {
                FtsIndex.clearAll(driver)
            } else if (recordings) {
                FtsIndex.clearRecordings(driver)
            } else {
                FtsIndex.clearTemplates(driver)
            }
            if (recordings) {
                q.selectAllRecordings().executeAsList().forEach { r ->
                    indexRecording(r.id, r.title, r.transcript, r.summary_markdown)
                }
            }
            if (templates) {
                q.selectTemplates().executeAsList().forEach { t ->
                    indexTemplate(t.id, t.name, t.description, t.category, t.system_prompt, t.tags)
                }
            }
        }
    }

    private fun reindexRecording(id: String) {
        if (!ftsAvailable) return
        val r = q.selectRecordingById(id).executeAsOneOrNull() ?: return
        indexRecording(r.id, r.title, r.transcript, r.summary_markdown)
    }

    private fun indexRecording(
        id: String,
        title: String,
        transcript: String?,
        summaryMarkdown: String?,
    ) {
        if (!ftsAvailable) return
        FtsIndex.upsertRecording(driver, id, title, transcript, summaryMarkdown)
    }

    private fun indexTemplate(
        id: String,
        name: String,
        description: String,
        category: String,
        systemPrompt: String,
        tags: String = "",
    ) {
        if (!ftsAvailable) return
        FtsIndex.upsertTemplate(driver, id, name, description, category, systemPrompt, tags)
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

    fun observeSummaryVersions(recordingId: String): Flow<List<Summary_version>> =
        q.selectVersionsForRecording(recordingId).asFlow().mapToList(Dispatchers.Default)

    suspend fun getSummaryVersions(recordingId: String): List<Summary_version> =
        withContext(Dispatchers.Default) {
            q.selectVersionsForRecording(recordingId).executeAsList()
        }

    /**
     * Persist a newly generated summary as the active version.
     * When the recording already has non-blank notes that are not yet versioned,
     * snapshots that text first ([ensureBaselineVersion]) so regenerate keeps history.
     */
    suspend fun saveSummaryVersion(
        recordingId: String,
        markdown: String,
        templateId: String?,
        templateName: String,
        versionId: String? = null,
        createdAt: Long = epochSeconds(),
    ): String = withContext(Dispatchers.Default) {
        val resolvedVersionId = versionId ?: newSummaryVersionId(recordingId)
        db.transactionWithResult {
            ensureBaselineVersionLocked(recordingId)
            q.clearActiveVersionsForRecording(recordingId)
            q.insertSummaryVersion(
                id = resolvedVersionId,
                recording_id = recordingId,
                template_id = templateId,
                template_name = templateName,
                markdown = markdown,
                created_at = createdAt,
                is_active = 1,
            )
            q.updateSummary(markdown, recordingId)
            reindexRecording(recordingId)
            resolvedVersionId
        }
    }

    suspend fun activateSummaryVersion(recordingId: String, versionId: String) =
        withContext(Dispatchers.Default) {
            db.transaction {
                val version = q.getSummaryVersionById(versionId, recordingId).executeAsOneOrNull()
                    ?: return@transaction
                q.clearActiveVersionsForRecording(recordingId)
                q.setVersionActive(versionId, recordingId)
                q.updateSummary(version.markdown, recordingId)
                reindexRecording(recordingId)
            }
        }

    suspend fun deleteSummaryVersion(recordingId: String, versionId: String) =
        withContext(Dispatchers.Default) {
            db.transaction {
                val version = q.getSummaryVersionById(versionId, recordingId).executeAsOneOrNull()
                    ?: return@transaction
                val wasActive = version.is_active != 0L
                q.deleteSummaryVersion(versionId, recordingId)
                if (!wasActive) return@transaction
                val remaining = q.selectVersionsForRecording(recordingId).executeAsList()
                val fallback = remaining.firstOrNull()
                if (fallback != null) {
                    q.clearActiveVersionsForRecording(recordingId)
                    q.setVersionActive(fallback.id, recordingId)
                    q.updateSummary(fallback.markdown, recordingId)
                } else {
                    q.updateSummary(null, recordingId)
                }
                reindexRecording(recordingId)
            }
        }

    /** Snapshot current [Recording.summary_markdown] if non-blank and no versions exist yet. */
    suspend fun ensureBaselineVersion(recordingId: String) = withContext(Dispatchers.Default) {
        db.transaction {
            ensureBaselineVersionLocked(recordingId)
        }
    }

    /**
     * After schema v3→v4 (or any open of a pre-versions DB), create an active version
     * row for every recording that already has notes but no version history.
     * Includes trashed rows so restore after upgrade keeps Versions history.
     */
    private fun backfillSummaryVersionsFromRecording() {
        db.transaction {
            q.selectAllRecordingIds().executeAsList().forEach { id ->
                ensureBaselineVersionLocked(id)
            }
        }
    }

    private fun ensureBaselineVersionLocked(recordingId: String) {
        val count = q.countVersionsForRecording(recordingId).executeAsOne()
        if (count > 0L) return
        val recording = q.selectRecordingById(recordingId).executeAsOneOrNull() ?: return
        val prior = recording.summary_markdown?.takeIf { it.isNotBlank() } ?: return
        q.insertSummaryVersion(
            id = newSummaryVersionId(recordingId),
            recording_id = recordingId,
            template_id = null,
            template_name = "",
            markdown = prior,
            created_at = recording.created_at.coerceAtMost(epochSeconds() - 1),
            // Active: this is the live summary until the user regenerates.
            is_active = 1,
        )
    }

    /**
     * Collision-resistant id: recording-scoped + epoch + wide random.
     * Never use INSERT OR REPLACE with a short birthday-collision id space.
     */
    private fun newSummaryVersionId(recordingId: String): String =
        "sv-$recordingId-${epochSeconds()}-${Random.nextInt(100_000, 999_999)}"

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

    companion object {
        const val META_FIRST_RUN_DONE = "first_run_done"
    }
}


expect fun epochSecondsNow(): Long
