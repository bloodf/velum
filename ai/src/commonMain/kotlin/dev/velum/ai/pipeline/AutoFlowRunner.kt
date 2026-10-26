package dev.velum.ai.pipeline

import dev.velum.core.autoflow.AutoFlowMatcher
import dev.velum.core.autoflow.AutoFlowPostAction
import dev.velum.core.calendar.CalendarEventProvider
import dev.velum.core.calendar.CalendarLookupResult
import dev.velum.core.calendar.createCalendarEventProvider
import dev.velum.core.data.VelumRepository
import dev.velum.core.db.Autoflow
import dev.velum.core.db.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
) {
    private var job: Job? = null
    private val processedIds = mutableSetOf<String>()
    private var seeded = false

    private val _actions = MutableSharedFlow<AutoFlowUiAction>(extraBufferCapacity = 8)
    val actions: SharedFlow<AutoFlowUiAction> = _actions.asSharedFlow()

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
                    if (!seeded) {
                        processedIds.addAll(recordings.map { it.id })
                        seeded = true
                        return@collect
                    }
                    val enabledFlows = flows.filter { it.enabled == 1L }
                    for (recording in recordings) {
                        if (recording.id in processedIds) continue
                        processedIds.add(recording.id)
                        maybeApplyCalendarTitle(recording)
                        val match = enabledFlows.firstOrNull { flow ->
                            matches(flow, recording)
                        } ?: continue
                        runProcessing(recording.id, match.template_id)
                        firePostAction(match, recording.id)
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun applyCalendarTitleIfAvailable(recordingId: String) {
        val recording = repository.getRecording(recordingId) ?: return
        maybeApplyCalendarTitle(recording)
    }

    private suspend fun runProcessing(recordingId: String, templateId: String?) {
        val scheduler = jobScheduler
        if (scheduler != null) {
            scheduler.enqueue(
                recordingId = recordingId,
                templateId = templateId,
                retranscribe = true,
            )
            awaitTerminalStatus(recordingId)
        } else {
            processor.process(
                recordingId = recordingId,
                templateId = templateId,
                retranscribe = true,
            ).collect { }
        }
    }

    private suspend fun awaitTerminalStatus(recordingId: String) {
        var sawWork = false
        withTimeoutOrNull(AWAIT_TERMINAL_MS) {
            while (isActive) {
                val status = repository.getRecording(recordingId)?.status ?: return@withTimeoutOrNull
                when (status) {
                    "generating", "paused_thermal" -> sawWork = true
                    "ready", "failed" -> if (sawWork) return@withTimeoutOrNull
                }
                delay(500)
            }
        }
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
    }
}

sealed class AutoFlowUiAction {
    data class ShareMarkdown(val title: String, val body: String) : AutoFlowUiAction()
    data class InsertCalendarEvent(val title: String, val description: String) : AutoFlowUiAction()
    data class BlockedOnPermission(val permission: String, val message: String) : AutoFlowUiAction()
}

expect fun epochMsNow(): Long
