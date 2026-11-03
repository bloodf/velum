package dev.velum.ai.pipeline

import dev.velum.core.settings.ApiModelOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Schedules [RecordingProcessor] work. Android uses WorkManager; JVM/desktop/iOS fall back
 * to an in-process coroutine so the same call sites stay shared.
 */
fun interface RecordingJobScheduler {
    fun enqueue(
        recordingId: String,
        templateId: String?,
        retranscribe: Boolean,
        apiModelOverride: ApiModelOverride?,
    )
}

/**
 * True when enqueue carries AutoFlow template/model/profile that must not lose to KEEP.
 * WorkManager uses UPDATE for these; null-only downloads keep KEEP.
 */
fun recordingWorkHasAutoFlowParams(
    templateId: String?,
    apiModelOverride: ApiModelOverride?,
): Boolean =
    !templateId.isNullOrBlank() ||
        !apiModelOverride?.modelId.isNullOrBlank() ||
        !apiModelOverride?.profileId.isNullOrBlank()

/**
 * True when two enqueues for the same recording ask for the same work: same template, model and profile (blank equals
 * absent) and the same retranscribe flag. A matching enqueue must keep the job already running instead of cancelling
 * it, or ASR would run twice and the cancelled job's unwinding could race the replacement's status (#324).
 */
fun recordingWorkParamsMatch(
    templateA: String?,
    retranscribeA: Boolean,
    overrideA: ApiModelOverride?,
    templateB: String?,
    retranscribeB: Boolean,
    overrideB: ApiModelOverride?,
): Boolean =
    retranscribeA == retranscribeB &&
        templateA.orEmpty() == templateB.orEmpty() &&
        overrideA?.modelId.orEmpty() == overrideB?.modelId.orEmpty() &&
        overrideA?.profileId.orEmpty() == overrideB?.profileId.orEmpty()

/** Stable tag form of the params, readable from a queued WorkManager item's tags. */
fun recordingWorkParamsTag(templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?): String =
    "velum-params:${templateId.orEmpty()}|${apiModelOverride?.modelId.orEmpty()}|${apiModelOverride?.profileId.orEmpty()}|$retranscribe"

/**
 * In-process scheduler with per-recording-id dedupe: a new enqueue cancels any still-active
 * job for the same id and waits for it to finish before starting, so AutoFlow can replace a
 * premature null-template launch without overlapping ASR/summary work.
 */
fun createCoroutineRecordingJobScheduler(
    processor: RecordingProcessor,
    scope: CoroutineScope,
): RecordingJobScheduler {
    class InFlight(
        val job: Job,
        val templateId: String?,
        val retranscribe: Boolean,
        val apiModelOverride: ApiModelOverride?,
    )
    // Atomic CAS map (common; `synchronized` is JVM-only). The job starts only after it is
    // registered, so its own cleanup can never run before the registration.
    val inFlight = MutableStateFlow<Map<String, InFlight>>(emptyMap())
    return RecordingJobScheduler { recordingId, templateId, retranscribe, apiModelOverride ->
        var previous: Job? = null
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.cancelAndJoin()
            try {
                processor.process(
                    recordingId = recordingId,
                    templateId = templateId,
                    retranscribe = retranscribe,
                    apiModelOverride = apiModelOverride,
                ).collect { }
            } finally {
                inFlight.update { if (it[recordingId]?.job === job) it - recordingId else it }
            }
        }
        var keepRunning = false
        inFlight.update { current ->
            val running = current[recordingId]
            keepRunning = running != null && running.job.isActive &&
                recordingWorkParamsMatch(
                    running.templateId, running.retranscribe, running.apiModelOverride,
                    templateId, retranscribe, apiModelOverride,
                )
            if (keepRunning) {
                current
            } else {
                previous = running?.job
                current + (recordingId to InFlight(job, templateId, retranscribe, apiModelOverride))
            }
        }
        // Same work already running: keep it, so ASR does not run twice and nothing cancels mid-flight.
        if (keepRunning) job.cancel() else job.start()
    }
}
