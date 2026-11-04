package dev.velum.ai.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
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
import dev.velum.ai.ApiErrorSanitizer
import dev.velum.ai.R
import dev.velum.ai.engine.JobProgress
import dev.velum.core.model.ComputeMode
import dev.velum.core.settings.ApiModelOverride
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
        var promoted = true
        try {
            setForeground(buildForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android 12+ refuses a foreground start from the background (ForegroundServiceStartNotAllowedException).
            // Failing here would leave the row `queued` forever (#330), so the job runs unpromoted.
            promoted = false
            android.util.Log.w(TAG, "Foreground start refused (${e::class.java.simpleName})")
            notifyRefusedStart()
        }
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val templateId = inputData.getString(KEY_TEMPLATE_ID)
        val retranscribe = inputData.getBoolean(KEY_RETRANSCRIBE, true)
        val overrideModel = inputData.getString(KEY_MODEL_ID)?.takeIf { it.isNotBlank() }
        val overrideProfile = inputData.getString(KEY_PROFILE_ID)?.takeIf { it.isNotBlank() }
        val apiOverride = if (overrideModel == null && overrideProfile == null) {
            null
        } else {
            ApiModelOverride(profileId = overrideProfile, modelId = overrideModel)
        }
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
        // Unpromoted work is stopped after about 10 minutes (WorkManager's limit for non-foreground workers), the
        // processor restores the row, and WorkManager reruns it from the start: a long local job would loop without
        // progress or an error. The unpromoted run itself is recorded; only a run that follows a stopped unpromoted
        // run fails (once, with a message). Thermal, holder and backoff retries do not count.
        val workId = id.toString()
        val mustFail = failUnpromotedRerun(
            promoted = promoted,
            apiOnly = usesApiOnly(),
            // Same work id = a retry/reschedule of this run; a REPLACE enqueue gets a new id and starts clean.
            wasUnpromoted = { runCatching { repository.unpromotedRunOwner(recordingId) }.getOrNull() == workId },
            setFlag = { on -> runCatching { repository.setUnpromotedRun(recordingId, if (on) workId else null) } },
        )
        if (mustFail) {
            // A stable text: the UI shows it in the app's language, which the system language may not match (#348).
            val message = PROCESSING_STOPPED_MESSAGE
            // Status first: it is what ends the `queued` state; each write is independent.
            runCatching { repository.updateStatus(recordingId, "failed") }
            runCatching { repository.setProcessingError(recordingId, message) }
            return Result.failure(workDataOf(KEY_ERROR to message))
        }
        val result = processJob(recordingId, templateId, retranscribe, apiOverride, processor, repository)
        // The run ended on its own (even as a retry); only a stop leaves the flag set, by throwing past here.
        runCatching { repository.clearUnpromotedRunIfOwner(recordingId, workId) }
        return result
    }

    private suspend fun processJob(
        recordingId: String,
        templateId: String?,
        retranscribe: Boolean,
        apiOverride: ApiModelOverride?,
        processor: RecordingProcessor,
        repository: dev.velum.core.data.VelumRepository,
    ): Result {
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
                apiModelOverride = apiOverride,
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
                    workDataOf(
                        KEY_ERROR to ApiErrorSanitizer.sanitize(t.message ?: t.toString()),
                    ),
                )
            }
        }

        return when {
            succeeded -> Result.success()
            failedMessage != null -> Result.failure(
                workDataOf(KEY_ERROR to ApiErrorSanitizer.sanitize(failedMessage)),
            )
            ThermalMonitor.isSevereOrCritical(powerManager) -> {
                repository.updateProcessingCheckpoint(recordingId, STATUS_PAUSED_THERMAL)
                Result.retry()
            }
            else -> Result.success()
        }
    }

    private fun usesApiOnly(): Boolean {
        val cfg = AiRuntimeHolder.settings?.settings?.value ?: return false
        return cfg.transcriptionMode == ComputeMode.Api && cfg.summaryMode == ComputeMode.Api &&
            cfg.diarizationMode == ComputeMode.Api
    }

    /** A plain notification (no foreground service needed) so the user knows why a long job may stop. */
    private fun notifyRefusedStart() {
        runCatching {
            ensureProcessingChannel(applicationContext)
            val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
            val n = NotificationCompat.Builder(applicationContext, PROCESSING_CHANNEL_ID)
                .setContentTitle(applicationContext.inAppLanguage().getString(R.string.velum_processing_refused_title))
                .setContentText(applicationContext.inAppLanguage().getString(R.string.velum_processing_refused_body))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(PROCESSING_REFUSED_NOTIFICATION_ID, n)
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        ensureProcessingChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, PROCESSING_CHANNEL_ID)
            .setContentTitle(applicationContext.inAppLanguage().getString(R.string.velum_processing_notification_title))
            .setContentText(applicationContext.inAppLanguage().getString(R.string.velum_processing_notification_body))
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
        const val KEY_MODEL_ID = "modelId"
        const val KEY_PROFILE_ID = "profileId"
        const val KEY_ERROR = "error"
        const val STATUS_PAUSED_THERMAL = "paused_thermal"
        const val MAX_HOLDER_RETRIES = 5
        private const val TAG = "RecordingWorker"
        const val PROCESSING_REFUSED_NOTIFICATION_ID = 0x56454C03
        const val PROCESSING_CHANNEL_ID = "velum_recording_processing"
        const val PROCESSING_NOTIFICATION_ID = 0x56454C02

        fun enqueue(
            context: Context,
            recordingId: String,
            templateId: String? = null,
            retranscribe: Boolean = true,
            apiModelOverride: ApiModelOverride? = null,
        ): Boolean {
            val data = Data.Builder()
                .putString(KEY_RECORDING_ID, recordingId)
                .putBoolean(KEY_RETRANSCRIBE, retranscribe)
                .apply {
                    if (!templateId.isNullOrBlank()) putString(KEY_TEMPLATE_ID, templateId)
                    apiModelOverride?.modelId?.takeIf { it.isNotBlank() }
                        ?.let { putString(KEY_MODEL_ID, it) }
                    apiModelOverride?.profileId?.takeIf { it.isNotBlank() }
                        ?.let { putString(KEY_PROFILE_ID, it) }
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
                .addTag(recordingWorkParamsTag(templateId, retranscribe, apiModelOverride, requiresNetworkForApiModes()))
                .build()
            // KEEP drops a later AutoFlow enqueue (template/model) after download/import
            // enqueued with nulls. REPLACE upgrades pending work when AutoFlow params arrive;
            // null-only enqueues still KEEP so we do not cancel an in-flight job.
            // An enqueue for the same template, model and retranscribe flag as the work already queued or running
            // keeps it (#324): REPLACE would cancel a job mid-ASR and run ASR twice.
            val manager = WorkManager.getInstance(context)
            val sameParams = activeWorkHasParams(
                manager,
                recordingId,
                recordingWorkParamsTag(templateId, retranscribe, apiModelOverride, requiresNetworkForApiModes()),
            )
            val policy = if (!recordingWorkHasAutoFlowParams(templateId, apiModelOverride) || sameParams) {
                ExistingWorkPolicy.KEEP
            } else {
                ExistingWorkPolicy.REPLACE
            }
            manager.enqueueUniqueWork(
                UNIQUE_PREFIX + recordingId,
                policy,
                request,
            )
            // Reports "started or replaced"; KEEP over queued/running work did neither.
            return !(policy == ExistingWorkPolicy.KEEP && hasActiveWork(manager, recordingId, excluding = request.id))
        }

        private fun hasActiveWork(manager: WorkManager, recordingId: String, excluding: java.util.UUID): Boolean {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return false
            return runCatching {
                manager.getWorkInfosForUniqueWork(UNIQUE_PREFIX + recordingId).get(2, TimeUnit.SECONDS)
                    .any { !it.state.isFinished && it.id != excluding }
            }.getOrElse {
                Log.w(TAG, "active-work lookup failed; reporting the enqueue as started", it)
                false
            }
        }

        /**
         * True when the unique work for [recordingId] is queued or running and carries [paramsTag]. The params live in
         * the work's tags, so they survive a process restart. A main-thread caller (the lookup blocks briefly) or a
         * failed lookup answers false, which falls back to REPLACE.
         */
        private fun activeWorkHasParams(manager: WorkManager, recordingId: String, paramsTag: String): Boolean {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Log.w(TAG, "params lookup skipped on the main thread; same-params enqueue falls back to REPLACE")
                return false
            }
            return runCatching {
                manager.getWorkInfosForUniqueWork(UNIQUE_PREFIX + recordingId).get(2, TimeUnit.SECONDS).any { info ->
                    !info.state.isFinished && paramsTag in info.tags
                }
            }.getOrElse {
                Log.w(TAG, "params lookup failed; same-params enqueue falls back to REPLACE", it)
                false
            }
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
            nm.createNotificationChannel(
                NotificationChannel(
                    PROCESSING_CHANNEL_ID,
                    context.inAppLanguage().getString(R.string.velum_processing_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}

fun createWorkManagerRecordingJobScheduler(context: Context): RecordingJobScheduler =
    object : RecordingJobScheduler {
        override fun enqueue(recordingId: String, templateId: String?, retranscribe: Boolean, apiModelOverride: ApiModelOverride?) {
            enqueueReportingStart(recordingId, templateId, retranscribe, apiModelOverride)
        }

        override fun enqueueReportingStart(
            recordingId: String,
            templateId: String?,
            retranscribe: Boolean,
            apiModelOverride: ApiModelOverride?,
        ): Boolean = RecordingProcessWorker.enqueue(
            context = context.applicationContext,
            recordingId = recordingId,
            templateId = templateId,
            retranscribe = retranscribe,
            apiModelOverride = apiModelOverride,
        )

        override fun cancel(recordingId: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(RecordingProcessWorker.UNIQUE_PREFIX + recordingId)
        }

        // WorkManager tags every request with its worker class.
        override fun cancelAll() {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(RecordingProcessWorker::class.java.name)
        }
    }
