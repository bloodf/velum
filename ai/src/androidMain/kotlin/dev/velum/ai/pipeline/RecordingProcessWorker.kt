package dev.velum.ai.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.velum.ai.R
import dev.velum.ai.engine.JobProgress
import dev.velum.core.model.ComputeMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Runs [RecordingProcessor] for one recording under WorkManager with thermal backoff.
 * Checkpoints status + partial transcript/summary via [VelumRepository].
 * Uses a dataSync foreground notification for long on-device jobs.
 */
class RecordingProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo()

    override suspend fun doWork(): Result {
        setForeground(buildForegroundInfo())
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

    private fun buildForegroundInfo(): ForegroundInfo {
        ensureProcessingChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, PROCESSING_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.velum_processing_notification_title))
            .setContentText(applicationContext.getString(R.string.velum_processing_notification_body))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                PROCESSING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(PROCESSING_NOTIFICATION_ID, notification)
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
        const val PROCESSING_CHANNEL_ID = "velum_recording_processing"
        const val PROCESSING_NOTIFICATION_ID = 0x56454C02

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
                .apply {
                    if (requiresNetworkForApiModes()) {
                        setRequiredNetworkType(NetworkType.CONNECTED)
                    }
                }
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

        /** Y18: API transcription/summary/diarization needs connectivity. */
        fun requiresNetworkForApiModes(): Boolean {
            val cfg = AiRuntimeHolder.settings?.settings?.value ?: return false
            return cfg.transcriptionMode == ComputeMode.Api ||
                cfg.summaryMode == ComputeMode.Api ||
                cfg.diarizationMode == ComputeMode.Api
        }

        fun ensureProcessingChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(PROCESSING_CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    PROCESSING_CHANNEL_ID,
                    context.getString(R.string.velum_processing_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
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
