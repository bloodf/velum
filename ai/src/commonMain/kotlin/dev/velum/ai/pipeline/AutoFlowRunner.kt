package dev.velum.ai.pipeline

import dev.velum.core.autoflow.AutoFlowMatcher
import dev.velum.core.data.VelumRepository
import dev.velum.core.db.Autoflow
import dev.velum.core.db.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Watches recordings and runs [RecordingProcessor] when a new note_pro (or rule-matching)
 * recording appears and an enabled AutoFlow rule matches.
 */
class AutoFlowRunner(
    private val repository: VelumRepository,
    private val processor: RecordingProcessor,
    private val scope: CoroutineScope,
    /** When false, processing still runs; callers may skip UI banners. */
    val notifyEnabled: () -> Boolean = { true },
) {
    private var job: Job? = null
    private val processedIds = mutableSetOf<String>()
    private var seeded = false

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
                        // Avoid reprocessing seed/demo rows on cold start.
                        processedIds.addAll(recordings.map { it.id })
                        seeded = true
                        return@collect
                    }
                    val enabledFlows = flows.filter { it.enabled == 1L }
                    for (recording in recordings) {
                        if (recording.id in processedIds) continue
                        processedIds.add(recording.id)
                        val match = enabledFlows.firstOrNull { flow ->
                            matches(flow, recording)
                        } ?: continue
                        processor.process(
                            recordingId = recording.id,
                            templateId = match.template_id,
                            retranscribe = true,
                        ).collect { }
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Exposed for tests. */
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
}
