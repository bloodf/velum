package dev.velum.ai.pipeline

import dev.velum.core.data.TranscriptMarkers
import dev.velum.ai.engine.JobProgress
import dev.velum.core.autoflow.AutoFlowMatcher
import dev.velum.core.autoflow.AutoFlowPostAction
import dev.velum.core.autoflow.AutoFlowResolve
import dev.velum.core.calendar.CalendarEventProvider
import dev.velum.core.templates.BuiltinTemplateCatalog
import dev.velum.core.time.VelumDateTime
import dev.velum.core.calendar.CalendarLookupResult
import dev.velum.core.calendar.createCalendarEventProvider
import dev.velum.core.data.VelumRepository
import dev.velum.core.db.Autoflow
import dev.velum.core.db.Recording
import dev.velum.core.settings.ApiModelOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local AutoFlow: match rules → [RecordingJobScheduler] / [RecordingProcessor] →
 * optional share / calendar intent.
 * When [jobScheduler] is set, processing goes through that single path (WorkManager on
 * Android) so AutoFlow never double-runs with the UI enqueue.
 */
class AutoFlowRunner internal constructor(
    private val repository: AutoFlowRecordingStore,
    private val processRecording: (String, String?, ApiModelOverride?) -> Flow<JobProgress>,
    private val scope: CoroutineScope,
    val notifyEnabled: () -> Boolean = { true },
    private val calendar: CalendarEventProvider = createCalendarEventProvider(),
    private val nowEpochMs: () -> Long = { epochMsNow() },
    private val jobScheduler: RecordingJobScheduler? = null,
) {
    constructor(
        repository: VelumRepository,
        processor: RecordingProcessor,
        scope: CoroutineScope,
        notifyEnabled: () -> Boolean = { true },
        calendar: CalendarEventProvider = createCalendarEventProvider(),
        nowEpochMs: () -> Long = { epochMsNow() },
        jobScheduler: RecordingJobScheduler? = null,
    ) : this(
        RepositoryAutoFlowRecordingStore(repository),
        { id, template, override -> processor.process(id, template, true, override) },
        scope, notifyEnabled, calendar, nowEpochMs, jobScheduler,
    )

    private val job = MutableStateFlow<Job?>(null)
    private val calendarPermissionAsked = MutableStateFlow(false)
    private val processedIds = mutableSetOf<String>()
    private var seeded = false

    private val _actions = MutableSharedFlow<AutoFlowUiAction>(extraBufferCapacity = 8)
    val actions: SharedFlow<AutoFlowUiAction> = _actions.asSharedFlow()

    // A restart may be requested before a cancelled collector finishes cleanup.
    // Serialize collectors so they never concurrently mutate processedIds/seeded.
    private val collectorMutex = Mutex()
    private val _passIndex = MutableStateFlow(0L)

    /** Test observation hook: number of completed passes, including seed/error/cancel. */
    internal val passIndex = _passIndex.asStateFlow()
    internal val isRunning: Boolean get() = job.value?.isActive == true

    fun start() {
        while (true) {
            val previous = job.value
            // Include a newly registered lazy job, before its owner starts it.
            if (previous != null && !previous.isCompleted && !previous.isCancelled) return
            val next = scope.launch(start = CoroutineStart.LAZY) { collectPasses() }
            if (job.compareAndSet(previous, next)) {
                next.invokeOnCompletion { job.compareAndSet(next, null) }
                next.start()
                return
            }
            next.cancel()
        }
    }

    private suspend fun collectPasses() {
        collectorMutex.withLock {
            try {
                combine(
                    repository.observeRecordings(),
                    repository.observeAutoflows(),
                ) { recordings, flows -> recordings to flows }
                    .distinctUntilChanged()
                    .collect { (recordings, flows) ->
                        try {
                            runPass(recordings, flows)
                        } finally {
                            _passIndex.update { it + 1 }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed upstream subscription has no individual recording.
                // Surface it, then allow start() to replace this completed job.
                _actions.emit(AutoFlowUiAction.ProcessingFailed)
            }
        }
    }

    private suspend fun runPass(recordings: List<Recording>, flows: List<Autoflow>) {
        if (!seeded) {
            processedIds.addAll(recordings.filter { isStableNonMatch(it) }.map { it.id })
            seeded = true
            return
        }
        val enabledFlows = flows.filter { it.enabled == 1L }
        for (recording in recordings) {
            currentCoroutineContext().ensureActive()
            if (recording.id in processedIds) continue
            var processingCompleted = isStableNonMatch(recording)
            try {
                maybeApplyCalendarTitle(recording)
                val match = enabledFlows.firstOrNull { matches(it, recording) }
                if (match == null || isStableNonMatch(recording)) {
                    if (isStableNonMatch(recording)) processedIds.add(recording.id)
                    continue
                }
                val override = AutoFlowResolve.modelOverride(match)
                val ready = runProcessing(recording.id, match.template_id, override)
                processingCompleted = ready
                // Timeout leaves the id unclaimed for a later terminal update.
                val terminal = repository.getRecording(recording.id)?.status
                if (terminal == "ready" || terminal == "failed") processedIds.add(recording.id)
                if (ready) firePostAction(match, recording.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!processingCompleted) {
                    try {
                        repository.updateStatus(recording.id, "failed")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The UI action below is also available when storage fails.
                    }
                }
                currentCoroutineContext().ensureActive()
                // A failed post-action must not destroy a successfully generated note.
                // Never expose dependency exception messages or recording contents.
                _actions.emit(AutoFlowUiAction.ProcessingFailed)
                currentCoroutineContext().ensureActive()
                // Claim poison rows even if persisting failed also fails; otherwise
                // their own DB emissions can repeatedly retry the same failure.
                // Cancellation during failure handling must leave the row retryable.
                processedIds.add(recording.id)
            }
        }
    }

    fun stop() {
        job.value?.cancel()
    }

    suspend fun applyCalendarTitleIfAvailable(recordingId: String) {
        val recording = repository.getRecording(recordingId) ?: return
        maybeApplyCalendarTitle(recording)
    }

    /** @return true when the recording finished as `ready` (post-actions may run). */
    private suspend fun runProcessing(
        recordingId: String,
        templateId: String?,
        apiModelOverride: ApiModelOverride? = null,
    ): Boolean {
        val scheduler = jobScheduler
        if (scheduler != null) {
            // Move off the current status before enqueue so awaitReadyStatus cannot treat a pre-existing `ready` as
            // "this job finished" (that fired post-actions before WorkManager started); "queued" also seeds
            // sawInFlight for synchronous test schedulers that finish before the first poll. The write is
            // conditional: a job that just finished leaves the row ready/failed, and a late "queued" plus a kept
            // enqueue would strand it on queued.
            val before = repository.getRecording(recordingId)
            val priorStatus = before?.status
            if (before?.status == "ready" && !isThisFlowsWork(before, templateId)) {
                // Finished, but by other work (another template): this flow still has to run.
                repository.updateStatus(recordingId, "queued")
            } else if (!repository.markQueuedIfActive(recordingId)) {
                // The job that was running finished before we acted: nothing left to enqueue.
                return repository.getRecording(recordingId)?.status == "ready"
            }
            val started = scheduler.enqueueReportingStart(
                recordingId = recordingId,
                templateId = templateId,
                retranscribe = true,
                apiModelOverride = apiModelOverride,
            )
            // The running job was kept: give the row back the status it had (generating, paused_thermal, ...), not a
            // hardcoded one, since the kept job may not have started yet.
            if (!started && priorStatus != null && priorStatus != "queued") {
                repository.restoreStatusIfQueued(recordingId, priorStatus)
            }
            return awaitReadyStatus(recordingId, alreadyInFlight = true)
        }
        var succeeded = false
        processRecording(recordingId, templateId, apiModelOverride).collect { progress ->
            if (progress is JobProgress.Succeeded) succeeded = true
        }
        return succeeded || repository.getRecording(recordingId)?.status == "ready"
    }

    /** True when the ready row's active summary came from the template this flow asks for. */
    private suspend fun isThisFlowsWork(recording: Recording, flowTemplateId: String?): Boolean {
        val done = repository.activeSummaryTemplateId(recording.id)
        val wanted = flowTemplateId?.takeIf { it.isNotBlank() } ?: BuiltinTemplateCatalog.DEFAULT_ID
        return done == wanted
    }

    private suspend fun awaitReadyStatus(
        recordingId: String,
        alreadyInFlight: Boolean = false,
    ): Boolean {
        var sawInFlight = alreadyInFlight
        val terminal = withTimeoutOrNull(AWAIT_TERMINAL_MS) {
            while (isActive) {
                val status = repository.getRecording(recordingId)?.status
                    ?: return@withTimeoutOrNull null
                when (status) {
                    "generating", "paused_thermal", "queued" -> sawInFlight = true
                    "ready" -> {
                        // Only accept ready after in-flight (forced queued counts).
                        if (sawInFlight) return@withTimeoutOrNull "ready"
                    }
                    "failed" -> if (sawInFlight) return@withTimeoutOrNull "failed"
                }
                delay(500)
            }
            null
        }
        return terminal == "ready"
    }

    /**
     * True while the title is still the one the app generated (`formatFriendlyTitle` of the start time, in either
     * language), so a title the user edited is never overwritten.
     */
    private fun hasAutomaticTitle(recording: Recording): Boolean =
        listOf("pt-BR", "en").any { VelumDateTime.formatFriendlyTitle(recording.started_at, it) == recording.title }

    // Looked up at the recording's own start: downloads keep the device start time, so "now" could be days later.
    private suspend fun maybeApplyCalendarTitle(recording: Recording) {
        when (val result = calendar.currentOrNextEvent(recording.started_at * 1000)) {
            is CalendarLookupResult.Ok -> {
                calendarPermissionAsked.value = false
                val event = result.currentOrNext?.takeIf { it.isOnAt(recording.started_at * 1000) }
                val title = event?.title?.trim().orEmpty()
                if (title.isNotEmpty() && hasAutomaticTitle(recording)) {
                    repository.updateRecordingTitle(recording.id, title)
                }
            }
            CalendarLookupResult.PermissionDenied -> {
                // Every status change runs a new pass: ask once until the answer changes, not on each pass.
                // With no collector (no composition) the action would drop, so leave it for a later pass.
                if (_actions.subscriptionCount.value > 0 && calendarPermissionAsked.compareAndSet(expect = false, update = true)) {
                    _actions.tryEmit(
                        AutoFlowUiAction.BlockedOnPermission(
                            "READ_CALENDAR",
                            "Calendar permission missing — cannot name recording from current event.",
                        ),
                    )
                }
            }
            is CalendarLookupResult.Unavailable -> Unit
        }
    }

    private suspend fun firePostAction(flow: Autoflow, recordingId: String) {
        val recording = repository.getRecording(recordingId) ?: return
        if (recording.status != "ready") return
        when (AutoFlowPostAction.fromStored(flow.post_action)) {
            AutoFlowPostAction.None -> Unit
            AutoFlowPostAction.ShareMarkdown -> {
                val body = shareMarkdownBody(recording.title, recording.summary_markdown, recording.transcript)
                _actions.emit(AutoFlowUiAction.ShareMarkdown(recording.title, body))
            }
            AutoFlowPostAction.CalendarInsert -> {
                when (calendar.currentOrNextEvent(nowEpochMs())) {
                    CalendarLookupResult.PermissionDenied -> {
                        _actions.emit(
                            AutoFlowUiAction.BlockedOnPermission(
                                "READ_CALENDAR",
                                "Calendar permission missing — cannot open calendar insert.",
                            ),
                        )
                    }
                    else -> {
                        _actions.emit(
                            AutoFlowUiAction.InsertCalendarEvent(
                                title = recording.title,
                                description = recording.summary_markdown.orEmpty(),
                            ),
                        )
                    }
                }
            }
            AutoFlowPostAction.FolderExport -> {
                _actions.emit(AutoFlowUiAction.ExportMarkdownToFolder(recordingId))
            }
            AutoFlowPostAction.Webhook -> {
                _actions.emit(AutoFlowUiAction.PostWebhook(recordingId))
            }
        }
    }

    internal fun matches(flow: Autoflow, recording: Recording): Boolean =
        AutoFlowMatcher.matches(
            rule = AutoFlowMatcher.Rule(
                enabled = flow.enabled == 1L,
                wakePhrase = flow.wake_phrase,
                durationMinSeconds = flow.duration_min_seconds,
                durationMaxSeconds = flow.duration_max_seconds,
                sourceFilter = flow.source_filter,
                templateId = flow.template_id,
            ),
            candidate = AutoFlowMatcher.Candidate(
                source = recording.source,
                durationSeconds = recording.duration_seconds,
                title = recording.title,
                transcript = TranscriptMarkers.spoken(recording.transcript),
            ),
        )

    companion object {
        private const val AWAIT_TERMINAL_MS = 30L * 60L * 1000L

        /** Terminal statuses — seed-claim / skip rematch; never retranscribe these. */
        internal fun isStableNonMatch(recording: Recording): Boolean =
            recording.status == "ready" || recording.status == "failed"
    }
}

/** Only operations used by the runner; keeps fault tests independent of SQL/engine internals. */
internal interface AutoFlowRecordingStore {
    fun observeRecordings(): Flow<List<Recording>>
    fun observeAutoflows(): Flow<List<Autoflow>>
    suspend fun getRecording(id: String): Recording?
    suspend fun updateStatus(id: String, status: String)
    /** Marks the row queued unless it is already ready/failed; false when it was terminal. */
    suspend fun markQueuedIfActive(id: String): Boolean
    /** queued -> [status], only while the row is still queued. */
    suspend fun restoreStatusIfQueued(id: String, status: String): Boolean
    /** Template id of the recording's active summary version, or null. */
    suspend fun activeSummaryTemplateId(id: String): String?
    suspend fun updateRecordingTitle(id: String, title: String)
}

internal class RepositoryAutoFlowRecordingStore(private val repository: VelumRepository) : AutoFlowRecordingStore {
    override fun observeRecordings() = repository.observeRecordings()
    override fun observeAutoflows() = repository.observeAutoflows()
    override suspend fun getRecording(id: String) = repository.getRecording(id)
    override suspend fun updateStatus(id: String, status: String) { repository.updateStatus(id, status) }
    override suspend fun markQueuedIfActive(id: String) = repository.updateStatusIfActive(id, "queued")
    override suspend fun restoreStatusIfQueued(id: String, status: String) = repository.updateStatusIfEquals(id, "queued", status)
    override suspend fun activeSummaryTemplateId(id: String) = repository.activeSummaryTemplateId(id)
    override suspend fun updateRecordingTitle(id: String, title: String) { repository.updateRecordingTitle(id, title) }
}

sealed class AutoFlowUiAction {
    data object ProcessingFailed : AutoFlowUiAction()
    data class ShareMarkdown(val title: String, val body: String) : AutoFlowUiAction()
    data class InsertCalendarEvent(val title: String, val description: String) : AutoFlowUiAction()
    data class ExportMarkdownToFolder(val recordingId: String) : AutoFlowUiAction()
    data class PostWebhook(val recordingId: String) : AutoFlowUiAction()
    data class BlockedOnPermission(val permission: String, val message: String) : AutoFlowUiAction()
}

expect fun epochMsNow(): Long

/**
 * Body of the "share as Markdown" post action. Live-capture markers (the user's notes) stay in the body
 * under their own heading; the "Transcript" heading appears only when there is spoken text.
 */
internal fun shareMarkdownBody(title: String, summary: String?, transcript: String?): String = buildString {
    append("# ").append(title).append("\n\n")
    if (!summary.isNullOrBlank()) append(summary).append("\n\n")
    if (transcript.isNullOrBlank()) return@buildString
    val (spoken, markers) = TranscriptFormatter.splitMarkers(transcript)
    if (spoken.isNotBlank()) append("## Transcript\n\n").append(spoken)
    if (markers != null) {
        if (spoken.isNotBlank()) append("\n\n")
        append(markers)
    }
}
