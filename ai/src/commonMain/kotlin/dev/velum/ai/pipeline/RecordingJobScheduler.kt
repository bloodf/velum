package dev.velum.ai.pipeline

import dev.velum.core.settings.ApiModelOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
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
 * In-process scheduler with per-recording-id dedupe: a new enqueue cancels any still-active
 * job for the same id and waits for it to finish before starting, so AutoFlow can replace a
 * premature null-template launch without overlapping ASR/summary work.
 */
fun createCoroutineRecordingJobScheduler(
    processor: RecordingProcessor,
    scope: CoroutineScope,
): RecordingJobScheduler {
    val lock = Any()
    val inFlight = mutableMapOf<String, Job>()
    return RecordingJobScheduler { recordingId, templateId, retranscribe, apiModelOverride ->
        synchronized(lock) {
            val previous = inFlight[recordingId]
            lateinit var job: Job
            job = scope.launch {
                previous?.cancelAndJoin()
                try {
                    processor.process(
                        recordingId = recordingId,
                        templateId = templateId,
                        retranscribe = retranscribe,
                        apiModelOverride = apiModelOverride,
                    ).collect { }
                } finally {
                    synchronized(lock) {
                        if (inFlight[recordingId] === job) {
                            inFlight.remove(recordingId)
                        }
                    }
                }
            }
            inFlight[recordingId] = job
        }
    }
}
