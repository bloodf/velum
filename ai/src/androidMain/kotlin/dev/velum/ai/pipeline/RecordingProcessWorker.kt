package dev.velum.ai.pipeline

import android.content.Context
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.velum.ai.engine.JobProgress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Runs [RecordingProcessor] for one recording under WorkManager with thermal backoff.
 * Checkpoints status + partial transcript/summary via [VelumRepository].
 */
class RecordingProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val templateId = inputData.getString(KEY_TEMPLATE_ID)
        val retranscribe = inputData.getBoolean(KEY_RETRANSCRIBE, true)
        val processor = AiRuntimeHolder.processor
        val repository = AiRuntimeHolder.repository
        if (processor == null || repository == null) {
            return if (runAttemptCount >= MAX_HOLDER_RETRIES) {
                Result.failure(
                    workDataOf(KEY_ERROR to "AiRuntimeHolder not installed after $runAttemptCount retries"),
                )
            } else {
                Result.retry()
            }
        }
        val powerManager = applicationContext.getSystemService(PowerManager::class.java)

        if (ThermalMonitor.isSevereOrCritical(powerManager)) {
            repository.updateProcessingCheckpoint(recordingId, STATUS_PAUSED_THERMAL)
            return Result.retry()
        }

        var lastTranscript: String? = null
        var lastSummary: String? = null
        var failedMessage: String? = null
        var succeeded = false

        try {
            processor.process(
                recordingId = recordingId,
                templateId = templateId,
                retranscribe = retranscribe,
            ).collect { progress ->
                ThermalMonitor.awaitCool(
                    powerManager = powerManager,
                    onPaused = { _ ->
                        repository.updateProcessingCheckpoint(
                            id = recordingId,
                            status = STATUS_PAUSED_THERMAL,
                            transcript = lastTranscript,
                            summaryMarkdown = lastSummary,
                        )
                    },
                )
                when (progress) {
                    is JobProgress.Running -> {
                        val snap = repository.getRecording(recordingId)
                        lastTranscript = snap?.transcript
                        lastSummary = snap?.summary_markdown
                        repository.updateProcessingCheckpoint(
                            id = recordingId,
                            status = "generating",
                            transcript = lastTranscript,
                            summaryMarkdown = lastSummary,
                        )
                    }
                    is JobProgress.PausedThermal -> {
                        repository.updateProcessingCheckpoint(
                            id = recordingId,
                            status = STATUS_PAUSED_THERMAL,
                            transcript = lastTranscript,
                            summaryMarkdown = lastSummary,
                        )
                    }
                    is JobProgress.Succeeded -> {
                        succeeded = true
                        repository.updateStatus(recordingId, "ready")
                    }
                    is JobProgress.Failed -> {
                        failedMessage = progress.message
                        repository.updateStatus(recordingId, "failed")
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) {
                throw t
            }
            return if (ThermalMonitor.isSevereOrCritical(powerManager)) {
                repository.updateProcessingCheckpoint(recordingId, STATUS_PAUSED_THERMAL)
                Result.retry()
            } else {
                repository.updateStatus(recordingId, "failed")
                Result.failure(
                    workDataOf(KEY_ERROR to (t.message ?: t.toString())),
                )
            }
        }

        return when {
            succeeded -> Result.success()
            failedMessage != null -> Result.failure(workDataOf(KEY_ERROR to failedMessage))
            ThermalMonitor.isSevereOrCritical(powerManager) -> {
                repository.updateProcessingCheckpoint(recordingId, STATUS_PAUSED_THERMAL)
                Result.retry()
            }
            else -> Result.success()
        }
    }

    companion object {
        const val UNIQUE_PREFIX = "velum-recording-process-"
        const val KEY_RECORDING_ID = "recordingId"
        const val KEY_TEMPLATE_ID = "templateId"
        const val KEY_RETRANSCRIBE = "retranscribe"
        const val KEY_ERROR = "error"
        const val STATUS_PAUSED_THERMAL = "paused_thermal"
        const val MAX_HOLDER_RETRIES = 5

        fun enqueue(
            context: Context,
            recordingId: String,
            templateId: String? = null,
            retranscribe: Boolean = true,
        ) {
            val data = Data.Builder()
                .putString(KEY_RECORDING_ID, recordingId)
                .putBoolean(KEY_RETRANSCRIBE, retranscribe)
                .apply {
                    if (!templateId.isNullOrBlank()) putString(KEY_TEMPLATE_ID, templateId)
                }
                .build()
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<RecordingProcessWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("recording:$recordingId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_PREFIX + recordingId,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

fun createWorkManagerRecordingJobScheduler(context: Context): RecordingJobScheduler =
    RecordingJobScheduler { recordingId, templateId, retranscribe ->
        RecordingProcessWorker.enqueue(
            context = context.applicationContext,
            recordingId = recordingId,
            templateId = templateId,
            retranscribe = retranscribe,
        )
    }
