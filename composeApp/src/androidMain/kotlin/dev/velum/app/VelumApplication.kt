package dev.velum.app

import android.app.Application
import android.os.PowerManager
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.ThermalMonitor
import dev.velum.ai.pipeline.createWorkManagerRecordingJobScheduler
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.DownloadProgressNotifier
import dev.velum.app.platform.MicRecordingService
import dev.velum.app.platform.ShareTextHolder
import dev.velum.app.platform.installAppContext
import dev.velum.app.platform.installMicCaptureContext
import dev.velum.app.platform.STALE_DOWNLOAD_PART_AGE_MS
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.sweepStaleDownloadParts
import dev.velum.app.platform.sweepStalePartialTransfers
import dev.velum.app.platform.recoverPartialMicRecordings
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.sweepOrphanRecordingAudio
import dev.velum.core.calendar.installCalendarContext
import dev.velum.core.data.DriverFactory
import dev.velum.core.integrations.IntegrationConfig
import dev.velum.core.security.AndroidSecureStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-scoped application graph.
 *
 * Heavy work (SQLDelight open, Featured template seed ~1500 rows, FTS rebuild) runs on
 * [Dispatchers.IO] so [Application.onCreate] does not ANR. [MainActivity] keeps the
 * splash screen up until [isContainerReady] (or a fatal init error surfaces).
 */
class VelumApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = AtomicBoolean(false)
    private val containerDeferred = CompletableDeferred<AppContainer>()
    private val initFailure = AtomicReference<Throwable?>(null)

    override fun onCreate() {
        super.onCreate()
        // Context installs only — no filesDir / SharedPreferences / File I/O on main
        // (device-walk StrictMode penaltyDeath is active before Application.onCreate).
        ShareTextHolder.context = applicationContext
        installAppContext(applicationContext)
        installCalendarContext(applicationContext)
        installMicCaptureContext(applicationContext)
        dev.velum.ai.local.installDeviceComputeContext(applicationContext)
        MicRecordingService.ensureNotificationChannel(this)
        DownloadProgressNotifier.ensureChannel()

        val powerManager = getSystemService(PowerManager::class.java)
        val appContext = applicationContext
        appScope.launch {
            try {
                // filesDir / models path are disk reads — stay on Dispatchers.IO.
                installRecordingFilesDir(filesDir.absolutePath)
                val modelsDir = File(filesDir, "models")
                val built = AppContainer(
                    secureStore = AndroidSecureStore(appContext),
                    driverFactory = DriverFactory(appContext),
                    modelStoreRoot = ModelStoreDir(modelsDir.absolutePath),
                    integrationConfig = IntegrationConfig.fromEnv { key -> System.getenv(key) },
                    recordingJobSchedulerFactory = { _, _ ->
                        createWorkManagerRecordingJobScheduler(appContext)
                    },
                    thermalPolicy = ThermalMonitor.asPolicy(powerManager),
                )
                container = built
                if (!built.repository.ftsAvailable) {
                    // Search falls back to a full list scan; class name only, never SQL or user data.
                    Log.w(TAG, "Full-text search unavailable (${built.repository.ftsFailure})")
                }
                AiRuntimeHolder.install(
                    processor = built.recordingProcessor,
                    repository = built.repository,
                    settings = built.settingsRepository,
                )
                cleanupThenPublish(
                    built = built,
                    cleanup = { c ->
                        // Runs before publish, so no download can be in flight (#86).
                        runCatching {
                            val swept = sweepStaleDownloadParts(
                                recordingsDirectoryPath(),
                                System.currentTimeMillis() - STALE_DOWNLOAD_PART_AGE_MS,
                            )
                            if (swept.isNotEmpty()) Log.i(TAG, "Removed ${swept.size} stale download part(s)")
                        }.onFailure { t -> Log.w(TAG, "Stale download sweep failed (${t::class.simpleName})") }
                        runCatching {
                            val swept = sweepStalePartialTransfers(System.currentTimeMillis())
                            if (swept > 0) Log.i(TAG, "Removed $swept stale partial download file(s)")
                        }.onFailure { t -> Log.w(TAG, "Partial download sweep failed (${t::class.simpleName})") }
                        val report = sweepOrphanRecordingAudio(recordingsDirectoryPath(), c.repository::getAllRecordingFileRefs)
                        Log.i(TAG, "Orphan audio sweep: quarantined=${report.quarantined} purged=${report.purged} staleTemps=${report.staleTemps} failedMoves=${report.failedMoves}")
                    },
                    publish = { c ->
                        // Complete the deferred before flipping ready so splash dismiss
                        // cannot race ahead of awaitContainer (#106 minifiedTest).
                        containerDeferred.complete(c)
                        ready.set(true)
                        Log.i(TAG, "AppContainer ready")
                    },
                    onCleanupFailure = { t -> Log.w(TAG, "Orphan audio sweep skipped (${t::class.simpleName})") },
                )
                runCatching {
                    recoverPartialMicRecordings(
                        recordingsDirPath = recordingsDirectoryPath(),
                        repository = built.repository,
                        protectedRecording = built.micRecordings::protects,
                        onRecovered = { recordingId ->
                            built.enqueueProcessing(
                                recordingId = recordingId,
                                templateId = null,
                                retranscribe = true,
                            )
                        },
                    )
                    dev.velum.app.platform.reconcileInterruptedMicDrafts(built, recordingsDirectoryPath())
                }.onFailure { t ->
                    Log.e(TAG, "Partial mic recovery failed", t)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AppContainer init failed", t)
                initFailure.set(t)
                // Unblock splash + MainActivity; they show a fatal error UI.
                containerDeferred.completeExceptionally(t)
                ready.set(true)
                Log.e(TAG, "AppContainer ready=fatal")
            }
        }
    }

    fun isContainerReady(): Boolean = ready.get()

    fun containerInitError(): Throwable? = initFailure.get()

    /** Kept for device-walk instrumentation (seed import via AppContainer). */
    @Keep
    @VisibleForTesting
    suspend fun awaitContainer(): AppContainer = containerDeferred.await()

    companion object {
        private const val TAG = "VelumApp"

        @Volatile
        lateinit var container: AppContainer
            private set
    }
}
