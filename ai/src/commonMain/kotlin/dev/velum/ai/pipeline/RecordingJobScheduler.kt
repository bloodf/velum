package dev.velum.ai.pipeline

import kotlinx.coroutines.CoroutineScope
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
    )
}

fun createCoroutineRecordingJobScheduler(
    processor: RecordingProcessor,
    scope: CoroutineScope,
): RecordingJobScheduler = RecordingJobScheduler { recordingId, templateId, retranscribe ->
    scope.launch {
        processor.process(
            recordingId = recordingId,
            templateId = templateId,
            retranscribe = retranscribe,
        ).collect { }
    }
}
