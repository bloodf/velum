package dev.velum.ai.pipeline

import dev.velum.core.settings.ApiModelOverride
import dev.velum.core.templates.BuiltinTemplateCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class EnqueueResult { STARTED_OR_REPLACED, KEPT, REJECTED }

class RecordingEnqueueRejected : IllegalStateException("Recording is missing or being deleted")

/** Submission succeeded, but retained WorkManager history cannot establish which request was inserted. */
class EnqueueOutcomeUnknown(cause: Throwable? = null) : IllegalStateException("Recording enqueue outcome unavailable", cause)

/** Submission acceptance and deletion cancellation share a per-recording lock. */
fun admittingRecordingJobScheduler(processor: RecordingProcessor, scope: CoroutineScope, delegate: RecordingJobScheduler): RecordingJobScheduler {
    // ponytail: one mutex per observed ID until process exit; use reference-counted entries if volume warrants it.
    val locks = MutableStateFlow<Map<String, Mutex>>(emptyMap())
    fun lock(id: String): Mutex {
        locks.update { if (id in it) it else it + (id to Mutex()) }
        return locks.value.getValue(id)
    }
    return object : RecordingJobScheduler {
        override suspend fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?): EnqueueResult =
            enqueueSaved(recordingId, templateId, retranscribe, apiModelOverride)

        override suspend fun enqueueSaved(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?, keepExisting: Boolean): EnqueueResult =
            withContext(Dispatchers.IO) {
                lock(recordingId).withLock {
                    if (!processor.canSchedule(recordingId)) EnqueueResult.REJECTED
                    else withContext(NonCancellable) { delegate.enqueueSaved(recordingId, templateId, retranscribe, apiModelOverride, keepExisting) }
                }
            }

        override suspend fun cancel(recordingId: String) {
            // The caller may stop waiting, but a submitted cancellation must settle before the next enqueue.
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                lock(recordingId).withLock { withContext(NonCancellable + Dispatchers.IO) { delegate.cancel(recordingId) } }
            }.await()
        }
        override fun hasActiveWork(recordingId: String) = delegate.hasActiveWork(recordingId)
        override fun cancelAll() = delegate.cancelAll()
    }
}

/**
 * Schedules [RecordingProcessor] work. Android uses WorkManager; JVM/desktop/iOS fall back
 * to an in-process coroutine so the same call sites stay shared.
 */
fun interface RecordingJobScheduler {
    suspend fun enqueue(
        recordingId: String,
        templateId: String?,
        retranscribe: Boolean,
        apiModelOverride: ApiModelOverride?,
    ): EnqueueResult

    /** Waits for submission acceptance and reports actual insertion, including already completed work. */
    suspend fun enqueueSaved(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?, keepExisting: Boolean = false): EnqueueResult =
        enqueue(recordingId, templateId, retranscribe, apiModelOverride)

    /**
     * True when work for [recordingId] is queued or running. A caller that wants to start work only if none exists
     * (startup recovery) asks first: an enqueue with other parameters would replace the job. Unknown counts as true.
     */
    fun hasActiveWork(recordingId: String): Boolean = false

    /** Drops work that is queued or waiting to retry. A job already running is stopped through its [dev.velum.core.data.WipeGate]. */
    fun cancelAll() = Unit

    /** Drops the queued or retrying work of one recording, which is being deleted. */
    suspend fun cancel(recordingId: String) = Unit
}

/**
 * True when enqueue carries AutoFlow template/model/profile. Such an enqueue replaces queued or running WorkManager
 * work unless that work already has the same parameters ([recordingWorkParamsMatch]); null-only downloads always KEEP.
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
        effectiveTemplate(templateA) == effectiveTemplate(templateB) &&
        overrideA?.modelId.orEmpty() == overrideB?.modelId.orEmpty() &&
        overrideA?.profileId.orEmpty() == overrideB?.profileId.orEmpty()

/**
 * Only a blank template is folded into the default: the processor treats it that way. Aliases are not merged, because
 * an edited `tpl-meeting` is a different template from the built-in `velum.meeting` and must not be kept against it.
 */
private fun effectiveTemplate(templateId: String?): String =
    templateId?.takeIf { it.isNotBlank() } ?: BuiltinTemplateCatalog.DEFAULT_ID

/** Stable tag form of the params, readable from a queued WorkManager item's tags. */
fun recordingWorkParamsTag(
    templateId: String?,
    retranscribe: Boolean,
    apiModelOverride: ApiModelOverride?,
    requiresNetwork: Boolean = false,
): String =
    "velum-params:${effectiveTemplate(templateId)}|${apiModelOverride?.modelId.orEmpty()}|" +
        "${apiModelOverride?.profileId.orEmpty()}|$retranscribe|net=$requiresNetwork"

/**
 * In-process scheduler with per-recording-id dedupe: an enqueue with the same parameters as the still-active job
 * for that id is dropped (the running job is kept); any other enqueue cancels the active job and waits for it to
 * finish before starting, so AutoFlow can replace a premature launch without overlapping ASR/summary work.
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
    return object : RecordingJobScheduler {
        override suspend fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?): EnqueueResult =
            enqueueSaved(recordingId, templateId, retranscribe, apiModelOverride)

        override fun hasActiveWork(recordingId: String): Boolean = inFlight.value[recordingId]?.job?.isActive == true

        override suspend fun enqueueSaved(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?, keepExisting: Boolean): EnqueueResult =
            if (schedule(recordingId, templateId, retranscribe, apiModelOverride, keepExisting)) EnqueueResult.STARTED_OR_REPLACED else EnqueueResult.KEPT

        private fun schedule(
            recordingId: String,
            templateId: String?,
            retranscribe: Boolean,
            apiModelOverride: ApiModelOverride?,
            keepExisting: Boolean,
        ): Boolean {
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
                    (keepExisting || recordingWorkParamsMatch(
                        running.templateId, running.retranscribe, running.apiModelOverride,
                        templateId, retranscribe, apiModelOverride,
                    ))
                if (keepRunning) {
                    current
                } else {
                    previous = running?.job
                    current + (recordingId to InFlight(job, templateId, retranscribe, apiModelOverride))
                }
            }
            // Same work already running: keep it, so ASR does not run twice and nothing cancels mid-flight.
            if (keepRunning) job.cancel() else job.start()
            return !keepRunning
        }

        override suspend fun cancel(recordingId: String) {
            inFlight.value[recordingId]?.job?.cancel()
        }
    }
}
