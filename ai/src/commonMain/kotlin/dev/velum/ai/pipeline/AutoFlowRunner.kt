package dev.velum.ai.pipeline

import dev.velum.ai.engine.JobProgress
import dev.velum.core.autoflow.AutoFlowMatcher
import dev.velum.core.autoflow.AutoFlowPostAction
import dev.velum.core.autoflow.AutoFlowResolve
import dev.velum.core.calendar.CalendarEventProvider
import dev.velum.core.calendar.CalendarLookupResult
import dev.velum.core.calendar.createCalendarEventProvider
import dev.velum.core.data.VelumRepository
import dev.velum.core.db.Autoflow
import dev.velum.core.db.Recording
import dev.velum.core.settings.ApiModelOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local AutoFlow: match rules → [RecordingJobScheduler] / [RecordingProcessor] →
 * optional share / calendar intent.
 * When [jobScheduler] is set, processing goes through that single path (WorkManager on
 * Android) so AutoFlow never double-runs with the UI enqueue.
 */
class AutoFlowRunner(
    private val repository: VelumRepository,
    private val processor: RecordingProcessor,
    private val scope: CoroutineScope,
    val notifyEnabled: () -> Boolean = { true },
    private val calendar: CalendarEventProvider = createCalendarEventProvider(),
    private val nowEpochMs: () -> Long = { epochMsNow() },
    private val jobScheduler: RecordingJobScheduler? = null,
    /**
     * Poll interval while waiting for WorkManager terminal status.
     * Must be >= 1: [delay] of 0 does not suspend, so a miss would busy-spin.
     * Tests inject 1ms; production keeps the 500ms default.
     */
    private val awaitStatusPollDelayMs: Long = DEFAULT_AWAIT_STATUS_POLL_MS,
) {
    init {
        require(awaitStatusPollDelayMs >= 1) {
            "awaitStatusPollDelayMs must be >= 1 (delay(0) does not suspend)"
        }
    }

    private var job: Job? = null
    private val processedIds = mutableSetOf<String>()
    private var seeded = false

    private val _actions = MutableSharedFlow<AutoFlowUiAction>(extraBufferCapacity = 8)
    val actions: SharedFlow<AutoFlowUiAction> = _actions.asSharedFlow()

    private val _passIndex = MutableStateFlow(0)

    /**
     * Test observation hook: increments after each combine emission is fully handled
     * (including the seed pass). Not used by production callers; AutoFlowRunnerTest awaits
     * this instead of wall-clock polls so subscription/seed races stay deterministic.
     */
    internal val passIndex: StateFlow<Int> = _passIndex.asStateFlow()

    private val _lastObservedSnapshotVersion = MutableStateFlow(0L)

    /**
     * Test observation hook: fingerprint of the recordings+autoflows snapshot handled by the
     * latest completed pass (published together with [passIndex]). Read-only observation —
     * never consulted by matching or claim logic. AutoFlowRunnerTest waits for a pass whose
     * observed fingerprint matches the repo fingerprint captured after its mutating [block].
     */
    internal val lastObservedSnapshotVersion: StateFlow<Long> =
        _lastObservedSnapshotVersion.asStateFlow()

    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(
                repository.observeRecordings(),
                repository.observeAutoflows(),
            ) { recordings, flows ->
                recordings to flows
            }
                .distinctUntilChanged()
                .collect { (recordings, flows) ->
                    // Fingerprint the emission before matching so the recorded version
                    // describes this snapshot only; matching / claim behavior is unchanged.
                    val observedSnapshotVersion = snapshotVersion(recordings, flows)
                    if (!seeded) {
                        // Claim only terminal ready/failed. Do NOT claim matchable waiting
                        // statuses (imported) or in-flight orphans (generating/queued) — those
                        // remain eligible for rematch. Terminal rows must not re-ASR.
                        processedIds.addAll(
                            recordings
                                .filter { isStableNonMatch(it) }
                                .map { it.id },
                        )
                        seeded = true
                        publishPassObservation(observedSnapshotVersion)
                        return@collect
                    }
                    val enabledFlows = flows.filter { it.enabled == 1L }
                    for (recording in recordings) {
                        if (recording.id in processedIds) continue
                        maybeApplyCalendarTitle(recording)
                        val match = enabledFlows.firstOrNull { flow ->
                            matches(flow, recording)
                        }
                        if (match == null) {
                            // Do not permanently skip incomplete rows (duration 0 / mid-capture).
                            // Only mark stable terminal recordings that never matched.
                            if (isStableNonMatch(recording)) {
                                processedIds.add(recording.id)
                            }
                            continue
                        }
                        // WorkManager already finished (e.g. after process death) — claim
                        // without re-enqueue / re-ASR. True orphans keep non-terminal status.
                        if (isStableNonMatch(recording)) {
                            processedIds.add(recording.id)
                            continue
                        }
                        val override = AutoFlowResolve.modelOverride(match)
                        val ready = runProcessing(recording.id, match.template_id, override)
                        // Claim only on observed terminal ready/failed. Timeout must leave
                        // the id unclaimed so a late WorkManager finish can still rematch
                        // and fire post-actions.
                        val terminal = repository.getRecording(recording.id)?.status
                        if (terminal == "ready" || terminal == "failed") {
                            processedIds.add(recording.id)
                        }
                        if (ready) {
                            firePostAction(match, recording.id)
                        }
                    }
                    publishPassObservation(observedSnapshotVersion)
                }
        }
    }

    private fun publishPassObservation(observedSnapshotVersion: Long) {
        _lastObservedSnapshotVersion.value = observedSnapshotVersion
        _passIndex.update { it + 1 }
    }


    fun stop() {
        job?.cancel()
        job = null
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
            // Always move off terminal status before enqueue so awaitReadyStatus
            // cannot treat a pre-existing `ready` as "this job finished" (that
            // fired post-actions before WorkManager started). Marking queued also
            // seeds sawInFlight for synchronous test schedulers that finish before
            // the first poll.
            repository.updateStatus(recordingId, "queued")
            scheduler.enqueue(
                recordingId = recordingId,
                templateId = templateId,
                retranscribe = true,
                apiModelOverride = apiModelOverride,
            )
            return awaitReadyStatus(recordingId, alreadyInFlight = true)
        }
        var succeeded = false
        processor.process(
            recordingId = recordingId,
            templateId = templateId,
            retranscribe = true,
            apiModelOverride = apiModelOverride,
        ).collect { progress ->
            if (progress is JobProgress.Succeeded) succeeded = true
        }
        return succeeded || repository.getRecording(recordingId)?.status == "ready"
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
                delay(awaitStatusPollDelayMs)
            }
            null
        }
        return terminal == "ready"
    }

    private suspend fun maybeApplyCalendarTitle(recording: Recording) {
        when (val result = calendar.currentOrNextEvent(nowEpochMs())) {
            is CalendarLookupResult.Ok -> {
                val title = result.currentOrNext?.title?.trim().orEmpty()
                if (title.isNotEmpty() && recording.title.startsWith("Recording ")) {
                    repository.updateRecordingTitle(recording.id, title)
                }
            }
            CalendarLookupResult.PermissionDenied -> {
                _actions.tryEmit(
                    AutoFlowUiAction.BlockedOnPermission(
                        "READ_CALENDAR",
                        "Calendar permission missing — cannot name recording from current event.",
                    ),
                )
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
                val body = buildString {
                    append("# ").append(recording.title).append("\n\n")
                    if (!recording.summary_markdown.isNullOrBlank()) {
                        append(recording.summary_markdown).append("\n\n")
                    }
                    if (!recording.transcript.isNullOrBlank()) {
                        append("## Transcript\n\n").append(recording.transcript)
                    }
                }
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
                transcript = recording.transcript,
            ),
        )

    companion object {
        private const val AWAIT_TERMINAL_MS = 30L * 60L * 1000L
        private const val DEFAULT_AWAIT_STATUS_POLL_MS = 500L

        /** Terminal statuses — seed-claim / skip rematch; never retranscribe these. */
        internal fun isStableNonMatch(recording: Recording): Boolean =
            recording.status == "ready" || recording.status == "failed"

        /**
         * Test helper: fingerprint of a recordings+autoflows snapshot. Stable for equal
         * observable fields; used only by [lastObservedSnapshotVersion] and tests.
         */
        internal fun snapshotVersion(
            recordings: List<Recording>,
            flows: List<Autoflow>,
        ): Long {
            var h = 1L
            for (r in recordings.sortedBy { it.id }) {
                h = h * 31 + r.id.hashCode()
                h = h * 31 + r.status.hashCode()
                h = h * 31 + r.duration_seconds
                h = h * 31 + r.title.hashCode()
                h = h * 31 + (r.local_path?.hashCode() ?: 0)
            }
            for (f in flows.sortedBy { it.id }) {
                h = h * 31 + f.id.hashCode()
                h = h * 31 + f.enabled
                h = h * 31 + f.duration_min_seconds
                h = h * 31 + f.duration_max_seconds
                h = h * 31 + f.source_filter.hashCode()
            }
            return h
        }
    }
}

sealed class AutoFlowUiAction {
    data class ShareMarkdown(val title: String, val body: String) : AutoFlowUiAction()
    data class InsertCalendarEvent(val title: String, val description: String) : AutoFlowUiAction()
    data class ExportMarkdownToFolder(val recordingId: String) : AutoFlowUiAction()
    data class PostWebhook(val recordingId: String) : AutoFlowUiAction()
    data class BlockedOnPermission(val permission: String, val message: String) : AutoFlowUiAction()
}

expect fun epochMsNow(): Long
