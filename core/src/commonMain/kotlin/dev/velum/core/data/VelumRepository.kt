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
import dev.velum.core.templates.PlaudTemplateAliases
import dev.velum.core.templates.PlaudTemplateCatalog
import dev.velum.core.templates.TemplateTagVocabulary
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import app.cash.sqldelight.db.QueryResult
import dev.velum.core.search.FtsQuery
import dev.velum.core.templates.DiscoverFacets
import dev.velum.core.templates.DiscoverFilter
import dev.velum.core.templates.DiscoverSearchMode
import dev.velum.core.templates.DiscoverTemplateQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow

class VelumRepository(
    private val db: VelumDatabase,
    private val driver: SqlDriver,
) {
    private val q get() = db.velumQueries

    /**
     * Every suspend call runs here, one at a time. The JVM in-memory driver has a single connection and a
     * single shared Transaction object: two threads in a transaction (or a raw BEGIN against another
     * thread's transaction) fail with "cannot start a transaction within a transaction" or a
     * ConcurrentModificationException (#208). Serializing also means an observer reacting to a new row
     * runs only after the insert call, including its index attempt, has returned. Reads share this
     * dispatcher too, so they no longer run in parallel.
     */
    private val dbContext = Dispatchers.Default.limitedParallelism(1)

    /** False on hosts without SQLite FTS5 (e.g. Robolectric). Search falls back to ListSearch. */
    val ftsAvailable: Boolean

    /** Why FTS is unavailable (exception class only), for platform logging; null when available. */
    val ftsFailure: String?

    /**
     * Called with the exception class name (never row text) when an index write fails after its row was
     * committed. The index is then flagged stale and rebuilt on the next FTS launch.
     */
    var onIndexFailure: ((String) -> Unit)? = null

    /** Exception class of a failed fts_dirty rebuild (index stays stale, flag kept, retried next open); else null. */
    var ftsRebuildFailure: String? = null
        private set

    constructor(handle: DatabaseHandle) : this(handle.database, handle.driver)

    init {
        val fts = FtsIndex.ensureSchema(driver)
        ftsAvailable = fts.available
        ftsFailure = fts.failure
        // Writes made now skip the index; the next open with FTS available must rebuild it.
        if (!ftsAvailable) q.setMeta(META_FTS_DIRTY, "1")
        val seeded = SeedData.maybeSeed(db)
        reconcileRemovedTemplateDuplicates()
        backfillSummaryVersionsFromRecording()
        if (ftsAvailable) {
            val dirty = q.getMeta(META_FTS_DIRTY).executeAsOneOrNull() != null
            when {
                // Full rebuild after seed, template_fts schema upgrade, or a run without FTS. Only a rebuild
                // for the dirty flag may fail soft (keep the flag, open on the stale index, retry next open).
                fts.templateSchemaRecreated || seeded || dirty -> try {
                    rebuildFtsIndex()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!dirty) throw e
                    ftsRebuildFailure = e::class.simpleName ?: "Exception"
                }
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
            // Only after the rebuild committed: a crash before this line just rebuilds again.
            if (dirty && ftsRebuildFailure == null) q.deleteMeta(META_FTS_DIRTY)
        }
    }

    /**
     * #99: drop builtin rows removed as exact-body duplicates (left by a pre-dedupe "All"
     * import) and repoint AutoFlows to the survivor. User templates (builtin = 0) are never
     * touched; summary versions keep their historical id and resolve via
     * [dev.velum.core.templates.BuiltinTemplateCatalog.resolveId].
     */
    private fun reconcileRemovedTemplateDuplicates() {
        // Alias map only grows, so its size identifies what has been reconciled already.
        val version = PlaudTemplateAliases.RemovedDuplicates.size.toString()
        if (q.getMeta(META_TEMPLATE_ALIASES_RECONCILED).executeAsOneOrNull() == version) return
        db.transaction {
            PlaudTemplateAliases.RemovedDuplicates.forEach { (removed, survivor) ->
                val row = q.selectTemplateById(removed).executeAsOneOrNull()
                if (row != null && row.builtin == 0L) return@forEach
                q.repointAutoflowTemplate(survivor, removed)
                if (row != null) {
                    q.deleteBuiltinTemplate(removed)
                    if (ftsAvailable) FtsIndex.deleteTemplate(driver, removed)
                }
            }
            q.setMeta(META_TEMPLATE_ALIASES_RECONCILED, version)
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

    suspend fun listAutoflows(): List<Autoflow> = withContext(dbContext) {
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
    ): Autoflow? = withContext(dbContext) {
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

    suspend fun getRecording(id: String): Recording? = withContext(dbContext) {
        q.selectRecordingById(id).executeAsOneOrNull()
    }

    /** Compare the complete snapshot in one transaction; never overwrite an advanced job/import. */
    suspend fun failInterruptedPhoneDraft(expected: Recording): Boolean = withContext(dbContext) {
        db.transactionWithResult {
            val current = q.selectRecordingById(expected.id).executeAsOneOrNull()
            if (current == null || current != expected || current.source != "phone" || current.status != "recording" ||
                current.duration_seconds != 0L || current.size_bytes != 0L ||
                // Markers saved while recording are not content: the interrupted draft must still be failed.
                !TranscriptMarkers.spoken(current.transcript).isNullOrBlank() || !current.summary_markdown.isNullOrBlank()) false
            else { q.updateStatus("failed", current.id); true }
        }
    }

    suspend fun getAllRecordings(): List<Recording> = withContext(dbContext) {
        q.selectAllRecordings().executeAsList()
    }

    /**
     * #113 dedup key: device session id + real device serial. Includes trashed rows. Without a serial
     * dedup is off (returns null), so sessions from different devices can never merge.
     */
    suspend fun findDeviceSessionRecording(sessionId: Long, deviceSerial: String?): Recording? =
        withContext(dbContext) {
            val serial = deviceSerial?.takeIf { it.isNotBlank() } ?: return@withContext null
            q.selectRecordingByDeviceSession(sessionId, serial).executeAsOneOrNull()
        }

    /** Subset of [sessionIds] already in the library for this device (one query; empty without a serial). */
    suspend fun librarySessionIds(sessionIds: List<Long>, deviceSerial: String?): Set<Long> =
        withContext(dbContext) {
            val serial = deviceSerial?.takeIf { it.isNotBlank() }
            if (serial == null || sessionIds.isEmpty()) return@withContext emptySet()
            // SQLite's default host-parameter limit is 999 on API 26-29: chunk the IN list.
            sessionIds.distinct().chunked(LIBRARY_SESSION_CHUNK).flatMapTo(HashSet()) { chunk ->
                q.selectLibrarySessionIds(serial, chunk).executeAsList().mapNotNull { it.session_id }
            }
        }

    /** Fails (never replaces) when the device session already has a row. */
    suspend fun insertDeviceSessionRecording(row: Recording) = withContext(dbContext) {
        q.insertDeviceSessionRecording(row)
        tryIndex { indexRecording(row.id, row.title, row.transcript, row.summary_markdown) }
    }

    /** Point an existing device-session row at re-downloaded audio; title/transcript/summary are kept. */
    suspend fun replaceDeviceSessionAudio(id: String, localPath: String, sizeBytes: Long, processingWarning: String? = null) =
        withContext(dbContext) { q.replaceDeviceSessionAudio(localPath, sizeBytes, processingWarning, id) }

    /** Every row (trashed included) as (id, local_path) for orphan-file reconciliation. */
    suspend fun getAllRecordingFileRefs(): List<Pair<String, String?>> = withContext(dbContext) {
        q.selectAllRecordingFileRefs().executeAsList().map { it.id to it.local_path }
    }

    suspend fun getTemplate(id: String): Template? = withContext(dbContext) {
        q.selectTemplateById(id).executeAsOneOrNull()
    }

    suspend fun searchRecordingIdsFts(matchQuery: String): List<String> =
        withContext(dbContext) {
            if (!ftsAvailable) emptyList() else FtsIndex.searchRecordingIds(driver, matchQuery)
        }

    suspend fun searchTemplateIdsFts(matchQuery: String): List<String> =
        withContext(dbContext) {
            if (!ftsAvailable) emptyList() else FtsIndex.searchTemplateIds(driver, matchQuery)
        }

    // ---- Discover filters (#102): SQL pushdown, separate from the template CRUD ----

    suspend fun queryDiscoverTemplates(
        filter: DiscoverFilter,
        limit: Int,
        offset: Int = 0,
    ): List<Template> = withContext(dbContext) {
        discoverQuery(filter) { mode, match ->
            val built = DiscoverTemplateQuery.page(filter, mode, match)
            driver.executeQuery(
                identifier = null,
                sql = built.sql,
                mapper = { c ->
                    val rows = mutableListOf<Template>()
                    while (c.next().value) {
                        rows += Template(
                            id = c.getString(0)!!,
                            name = c.getString(1)!!,
                            category = c.getString(2)!!,
                            description = c.getString(3)!!,
                            author = c.getString(4)!!,
                            system_prompt = c.getString(5)!!,
                            uses = c.getLong(6)!!,
                            builtin = c.getLong(7)!!,
                            created_at = c.getLong(8)!!,
                            featured = c.getLong(9)!!,
                            tags = c.getString(10)!!,
                            lang = c.getString(11)!!,
                            preferred_model = c.getString(12),
                            preferred_endpoint_profile_id = c.getString(13),
                        )
                    }
                    QueryResult.Value(rows)
                },
                parameters = built.args.size + 2,
            ) {
                built.args.forEachIndexed { i, a -> bindString(i, a) }
                bindLong(built.args.size, limit.toLong())
                bindLong(built.args.size + 1, offset.toLong())
            }.value
        }
    }

    suspend fun countDiscoverTemplates(filter: DiscoverFilter): Long = withContext(dbContext) {
        discoverQuery(filter) { mode, match ->
            val built = DiscoverTemplateQuery.count(filter, mode, match)
            driver.executeQuery(
                identifier = null,
                sql = built.sql,
                mapper = { c ->
                    c.next()
                    QueryResult.Value(c.getLong(0) ?: 0L)
                },
                parameters = built.args.size,
            ) { built.args.forEachIndexed { i, a -> bindString(i, a) } }.value
        }
    }

    suspend fun discoverFacets(filter: DiscoverFilter): DiscoverFacets = withContext(dbContext) {
        fun column(built: DiscoverTemplateQuery.Built): List<String> = driver.executeQuery(
            identifier = null,
            sql = built.sql,
            mapper = { c ->
                val out = mutableListOf<String>()
                while (c.next().value) c.getString(0)?.let { out += it }
                QueryResult.Value(out)
            },
            parameters = built.args.size,
        ) { built.args.forEachIndexed { i, a -> bindString(i, a) } }.value
        discoverQuery(filter) { mode, match ->
            DiscoverFacets(
                categories = column(DiscoverTemplateQuery.categories(filter, mode, match)),
                tags = column(DiscoverTemplateQuery.tagColumns(filter, mode, match))
                    .flatMap { TemplateTagVocabulary.decode(it) }.distinct().sorted(),
                langs = column(DiscoverTemplateQuery.langs(filter, mode, match))
                    .filter { it.isNotBlank() && it != "und" }.distinct().sorted(),
            )
        }
    }

    /**
     * Same search semantics as before: FTS hits (ranked) when FTS yields any, else substring
     * fallback. A MATCH that SQLite rejects (or a missing FTS table) is detected by a probe query
     * with a trivial mapper and falls back to substring mode; later query errors are not swallowed.
     */
    private fun <T> discoverQuery(filter: DiscoverFilter, run: (DiscoverSearchMode?, String?) -> T): T {
        val match = if (ftsAvailable && filter.search.isNotBlank()) FtsQuery.fromUserInput(filter.search) else null
        if (match == null) return run(DiscoverSearchMode.Like, null)
        val ftsHasHits = try {
            driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM template_fts WHERE template_fts MATCH ?",
                mapper = { c -> c.next(); QueryResult.Value((c.getLong(0) ?: 0L) > 0L) },
                parameters = 1,
            ) { bindString(0, match) }.value
        } catch (_: Exception) {
            false
        }
        if (!ftsHasHits) return run(DiscoverSearchMode.Like, null)
        // MATCH syntax was validated above, so errors from here on are real bugs and must surface.
        return run(DiscoverSearchMode.Fts, match)
    }

    /** Single-row observe for the template detail screen (no full-table load). */
    fun observeTemplate(id: String): Flow<Template?> =
        q.selectTemplateById(id).asFlow().mapToOneOrNull(Dispatchers.Default)

    /** Emits once per template table notification (edits, use counts, imports), not per row count. */
    fun observeTemplateChanges(): Flow<Long> = flow {
        var n = 0L
        q.countTemplates().asFlow().collect { emit(++n) }
    }

    suspend fun setAutoflowEnabled(id: String, enabled: Boolean) = withContext(dbContext) {
        q.updateAutoflowEnabled(if (enabled) 1 else 0, id)
    }

    suspend fun setAutoflowNotify(id: String, notify: Boolean) = withContext(dbContext) {
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
    ) = withContext(dbContext) {
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
    ) = withContext(dbContext) {
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

    suspend fun deleteAutoflow(id: String) = withContext(dbContext) {
        q.deleteAutoflow(id)
    }

    suspend fun trashRecording(id: String) = withContext(dbContext) {
        q.trashRecording(id)
    }

    suspend fun restoreRecording(id: String) = withContext(dbContext) {
        q.restoreRecording(id)
    }

    suspend fun moveRecordingToFolder(id: String, folderId: String?) = withContext(dbContext) {
        q.updateRecordingFolder(folderId, id)
    }

    suspend fun updateRecordingTitle(id: String, title: String) = withContext(dbContext) {
        q.updateRecordingTitle(title, id)
        tryIndex { reindexRecording(id) }
    }

    suspend fun addMemory(id: String, content: String) = withContext(dbContext) {
        q.insertMemory(id, content, epochSeconds())
    }

    suspend fun deleteMemory(id: String) = withContext(dbContext) {
        q.deleteMemory(id)
    }

    suspend fun listMemories(): List<String> = withContext(dbContext) {
        q.selectMemories().executeAsList().map { it.content }
    }

    suspend fun listGlossaryTerms(): List<GlossaryTerm> = withContext(dbContext) {
        q.selectGlossary().executeAsList().map {
            GlossaryTerm(id = it.id, term = it.term, definition = it.definition)
        }
    }

    suspend fun addGlossaryTerm(id: String, term: String, definition: String = "") =
        withContext(dbContext) {
            q.insertGlossary(id, term.trim(), definition.trim(), epochSeconds())
        }

    suspend fun deleteGlossaryTerm(id: String) = withContext(dbContext) {
        q.deleteGlossary(id)
    }

    /**
     * Prompt fragment for ASR initial_prompt and LLM system prompts.
     * Glossary terms first, then memory facts when [includeMemory] is true.
     */
    suspend fun personalizationPromptFragment(includeMemory: Boolean): String =
        withContext(dbContext) {
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

    suspend fun recordingCount(): Long = withContext(dbContext) {
        q.countRecordings().executeAsOne()
    }

    suspend fun updateTranscript(id: String, transcript: String) = withContext(dbContext) {
        q.updateTranscript(transcript, id)
        tryIndex { reindexRecording(id) }
    }

    suspend fun updateSummary(id: String, summaryMarkdown: String) = withContext(dbContext) {
        q.updateSummary(summaryMarkdown, id)
        tryIndex { reindexRecording(id) }
    }

    suspend fun updateStatus(id: String, status: String) = withContext(dbContext) {
        q.updateStatus(status, id)
    }

    /** [code] is a [dev.velum.core.model.ProcessingWarning] constant, or null to clear. */
    suspend fun setProcessingWarning(id: String, code: String?) = withContext(dbContext) {
        q.updateProcessingWarning(code, id)
    }

    suspend fun updateLocalPath(
        id: String,
        localPath: String,
        durationSeconds: Long,
        sizeBytes: Long,
    ) = withContext(dbContext) {
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
    ) = withContext(dbContext) {
        val changed = db.transactionWithResult {
            val current = q.selectRecordingById(id).executeAsOneOrNull() ?: return@transactionWithResult false
            val nextTranscript = transcript ?: current.transcript
            val nextSummary = summaryMarkdown ?: current.summary_markdown
            q.updateProcessingCheckpoint(status, nextTranscript, nextSummary, id)
            true
        }
        if (changed) tryIndex { reindexRecording(id) }
    }

    suspend fun getMeta(key: String): String? = withContext(dbContext) {
        q.getMeta(key).executeAsOneOrNull()
    }

    suspend fun setMeta(key: String, value: String) = withContext(dbContext) {
        q.setMeta(key, value)
    }

    suspend fun deleteMeta(key: String) = withContext(dbContext) {
        q.deleteMeta(key)
    }

    /** Last processing failure for [recordingId] (already sanitized), or null. Previous notes are never touched. */
    suspend fun getProcessingError(recordingId: String): String? =
        getMeta(processingErrorKey(recordingId))?.takeIf { it.isNotBlank() }

    /** Null removes the row; provider error text must not outlive the recording or a wipe. */
    suspend fun setProcessingError(recordingId: String, message: String?) = withContext(dbContext) {
        if (message == null) q.deleteMeta(processingErrorKey(recordingId)) else q.setMeta(processingErrorKey(recordingId), message)
    }

    /**
     * The work id of a run that could not be promoted to the foreground and has not ended on its own, or null.
     * A stop leaves it set; a retry keeps the id, a REPLACE enqueue gets a new one (so it is not mistaken for a rerun).
     */
    suspend fun unpromotedRunOwner(recordingId: String): String? = getMeta("$UNPROMOTED_RUN_PREFIX$recordingId")

    /** Null clears it. */
    suspend fun setUnpromotedRun(recordingId: String, workId: String?) = withContext(dbContext) {
        if (workId != null) q.setMeta("$UNPROMOTED_RUN_PREFIX$recordingId", workId) else q.deleteMeta("$UNPROMOTED_RUN_PREFIX$recordingId")
    }

    /** Clears the flag only if [workId] still owns it, so a newer run's flag is never removed by an older one. */
    suspend fun clearUnpromotedRunIfOwner(recordingId: String, workId: String) = withContext(dbContext) {
        val key = "$UNPROMOTED_RUN_PREFIX$recordingId"
        db.transaction {
            if (q.getMeta(key).executeAsOneOrNull() == workId) q.deleteMeta(key)
        }
    }

    private fun processingErrorKey(recordingId: String) = "$PROCESSING_ERROR_PREFIX$recordingId"

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
    ) = withContext(dbContext) {
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
        tryIndex { indexTemplate(id, n, d, c, systemPrompt, tagsEncoded) }
    }

    /**
     * Lazy-import the gzipped full Plaud catalog once (Discover "All").
     * Streams gunzip+JSONL line-by-line (no full ~29MB String). Preserves existing
     * [Template.uses] on id collision; sets featured from JSON.
     *
     * The "imported" meta flag is set only after a successful FTS rebuild so a
     * process kill mid-import cannot leave All templates present but unsearchable.
     * Cancellation is checked per row and rolls the whole import back (#100). A user-edited
     * builtin (same id, builtin = 0) is never overwritten by a re-import (#100).
     * @return number of rows upserted, or 0 if already imported / asset empty.
     */
    suspend fun ensureFullTemplatesImported(): Int = withContext(dbContext) {
        if (q.getMeta(SeedData.META_FULL_TEMPLATES_IMPORTED).executeAsOneOrNull() == "1") {
            return@withContext 0
        }
        val job = coroutineContext[Job]
        val now = epochSeconds()
        var upserted = 0
        var sawAny = false
        db.transaction {
            PlaudFullTemplateAsset.forEachLine { line ->
                job?.ensureActive()
                val entry = PlaudTemplateCatalog.parseJsonlLine(line) ?: return@forEachLine
                sawAny = true
                val existing = q.selectTemplateById(entry.id).executeAsOneOrNull()
                if (existing != null && existing.builtin == 0L) return@forEachLine
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

    suspend fun deleteTemplate(id: String) = withContext(dbContext) {
        q.deleteTemplate(id)
        tryIndex { if (ftsAvailable) FtsIndex.deleteTemplate(driver, id) }
    }

    suspend fun deleteRecordingHard(id: String) = withContext(dbContext) {
        q.deleteVersionsForRecording(id)
        q.deleteSpeakersForRecording(id)
        q.deleteRecordingHard(id)
        q.deleteMeta(processingErrorKey(id))
        q.deleteMeta("$UNPROMOTED_RUN_PREFIX$id")
        // Import dedupe keys (import_sha256_<hash> -> id) become tombstones: a manual import of the file works
        // again, but a watched folder does not bring the deleted recording back (#327).
        q.selectMetaKeysWithPrefixAndValue(globPrefix(IMPORT_HASH_PREFIX), id).executeAsList().forEach { key ->
            q.setMeta(IMPORT_DELETED_PREFIX + key.removePrefix(IMPORT_HASH_PREFIX), "1")
            q.deleteMeta(key)
        }
        tryIndex { if (ftsAvailable) FtsIndex.deleteRecording(driver, id) }
    }

    /**
     * Local wipe: recordings, custom templates, AutoFlows, memory, glossary. Built-ins reseed.
     * [deleteLocalFile] removes on-disk audio for each recording's `local_path` (privacy).
     */
    suspend fun wipeAllUserData(
        deleteLocalFile: (absolutePath: String) -> Unit = {},
    ) = withContext(dbContext) {
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
            tryIndex { if (ftsAvailable) FtsIndex.deleteRecording(driver, id) }
        }
        q.deleteAllCustomTemplates()
        q.deleteAllAutoflows()
        q.deleteAllMemories()
        q.deleteAllGlossary()
        q.deleteMetaWithPrefix(globPrefix(PROCESSING_ERROR_PREFIX))
        q.deleteMetaWithPrefix(globPrefix(UNPROMOTED_RUN_PREFIX))
        q.deleteMetaWithPrefix(globPrefix(IMPORT_DELETED_PREFIX))
        // Clear seed marker so maybeSeed re-inserts default AutoFlow + builtins.
        SeedData.clearSeedMarker(db)
        SeedData.maybeSeed(db)
        tryIndex { if (ftsAvailable) rebuildFtsIndex() }
        audioPaths.forEach { path ->
            runCatching { deleteLocalFile(path) }
        }
    }

    data class StorageUsage(
        val recordingCount: Long,
        val bytesClaimed: Long,
    )

    /** Sum of recording `size_bytes` for non-trashed rows (library claim, not free disk). */
    suspend fun storageUsage(): StorageUsage = withContext(dbContext) {
        val rows = q.selectAllRecordings().executeAsList()
        StorageUsage(
            recordingCount = rows.size.toLong(),
            bytesClaimed = rows.sumOf { it.size_bytes.coerceAtLeast(0) },
        )
    }

    /** Clear local audio path after optional file delete (keeps transcript/summary). */
    suspend fun clearLocalAudioPath(id: String) = withContext(dbContext) {
        val rec = q.selectRecordingById(id).executeAsOneOrNull() ?: return@withContext
        q.updateLocalPath(null, rec.duration_seconds, 0, id)
    }

    /** Plain-text backup of local library metadata + notes (no audio blobs). */
    suspend fun exportLibraryBackupText(): String = withContext(dbContext) {
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
    ) = withContext(dbContext) {
        // Commit the row first; a failed index write must not undo it (see tryIndex).
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
        tryIndex { indexRecording(id, title, transcript, summaryMarkdown) }
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
                    // Table was cleared above: plain inserts keep the rebuild linear (#100).
                    FtsIndex.upsertTemplate(
                        driver, t.id, t.name, t.description, t.category, t.system_prompt, t.tags,
                        replaceExisting = false,
                    )
                }
            }
        }
    }

    /**
     * For index writes made after their row was committed (outside any transaction). The row is the
     * only copy of the user's data, so an index failure must not roll it back or fail the caller: flag
     * the index stale instead, so the next FTS launch rebuilds it.
     */
    private fun tryIndex(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            q.setMeta(META_FTS_DIRTY, "1")
            onIndexFailure?.invoke(e::class.simpleName ?: "Exception")
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
        db.transaction { FtsIndex.upsertRecording(driver, id, title, TranscriptMarkers.spoken(transcript), summaryMarkdown) }
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
        db.transaction { FtsIndex.upsertTemplate(driver, id, name, description, category, systemPrompt, tags) }
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
        withContext(dbContext) {
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
    ): String = withContext(dbContext) {
        val resolvedVersionId = versionId ?: newSummaryVersionId(recordingId)
        val saved = db.transactionWithResult {
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
            resolvedVersionId
        }
        tryIndex { reindexRecording(recordingId) }
        saved
    }

    suspend fun activateSummaryVersion(recordingId: String, versionId: String) =
        withContext(dbContext) {
            val changed = db.transactionWithResult {
                val version = q.getSummaryVersionById(versionId, recordingId).executeAsOneOrNull()
                    ?: return@transactionWithResult false
                q.clearActiveVersionsForRecording(recordingId)
                q.setVersionActive(versionId, recordingId)
                q.updateSummary(version.markdown, recordingId)
                true
            }
            if (changed) tryIndex { reindexRecording(recordingId) }
        }

    suspend fun deleteSummaryVersion(recordingId: String, versionId: String) =
        withContext(dbContext) {
            val summaryChanged = db.transactionWithResult {
                val version = q.getSummaryVersionById(versionId, recordingId).executeAsOneOrNull()
                    ?: return@transactionWithResult false
                val wasActive = version.is_active != 0L
                q.deleteSummaryVersion(versionId, recordingId)
                if (!wasActive) return@transactionWithResult false
                val remaining = q.selectVersionsForRecording(recordingId).executeAsList()
                val fallback = remaining.firstOrNull()
                if (fallback != null) {
                    q.clearActiveVersionsForRecording(recordingId)
                    q.setVersionActive(fallback.id, recordingId)
                    q.updateSummary(fallback.markdown, recordingId)
                } else {
                    q.updateSummary(null, recordingId)
                }
                true
            }
            if (summaryChanged) tryIndex { reindexRecording(recordingId) }
        }

    /** Snapshot current [Recording.summary_markdown] if non-blank and no versions exist yet. */
    suspend fun ensureBaselineVersion(recordingId: String) = withContext(dbContext) {
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

    suspend fun getSpeakers(recordingId: String): List<Speaker> = withContext(dbContext) {
        q.selectSpeakersForRecording(recordingId).executeAsList()
    }

    suspend fun updateSpeakers(recordingId: String, speakers: List<SpeakerUpsert>) =
        withContext(dbContext) {
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
    ) = withContext(dbContext) {
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
        internal const val LIBRARY_SESSION_CHUNK = 500
        const val META_TEMPLATE_ALIASES_RECONCILED = "template_aliases_reconciled"
        const val META_FTS_DIRTY = "fts_dirty"
        private const val PROCESSING_ERROR_PREFIX = "processing_error:"
        private const val UNPROMOTED_RUN_PREFIX = "unpromoted_run:"
        /** Audio import dedupe: import_sha256_<hash> -> recording id. */
        const val IMPORT_HASH_PREFIX = "import_sha256_"

        /** A GLOB pattern matching keys that start with [prefix] (case-sensitive; GLOB metacharacters escaped). */
        internal fun globPrefix(prefix: String): String =
            prefix.map { if (it == '*' || it == '?' || it == '[') "[$it]" else it.toString() }.joinToString("") + "*"
        /** import_deleted_<hash>: that file's recording was permanently deleted; watched folders skip it. */
        const val IMPORT_DELETED_PREFIX = "import_deleted_"
    }
}

expect fun epochSecondsNow(): Long
