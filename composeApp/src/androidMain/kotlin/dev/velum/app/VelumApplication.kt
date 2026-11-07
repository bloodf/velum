package dev.velum.app

import android.app.Application
import android.os.PowerManager
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.local.defaultModelStoreRoot
import dev.velum.ai.pipeline.AiRuntimeHolder
import dev.velum.ai.pipeline.ThermalMonitor
import dev.velum.ai.pipeline.createWorkManagerRecordingJobScheduler
import dev.velum.ai.pipeline.STALE_PCM_SPILL_AGE_MS
import dev.velum.ai.pipeline.sweepOrphanPcmSpill
import dev.velum.app.di.AppContainer
import dev.velum.app.platform.ShareTextHolder
import dev.velum.app.platform.installAppContext
import dev.velum.app.platform.installMicCaptureContext
import dev.velum.app.platform.STALE_DOWNLOAD_PART_AGE_MS
import dev.velum.app.platform.installRecordingFilesDir
import dev.velum.app.platform.migrateCachedImports
import dev.velum.app.platform.sweepStaleDownloadParts
import dev.velum.app.platform.sweepStalePartialTransfers
import dev.velum.app.platform.recoverPartialMicRecordings
import dev.velum.app.platform.recordingsDirectoryPath
import dev.velum.app.platform.sweepOrphanRecordingAudio
import dev.velum.app.platform.sweepStaleImportCopies
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

        val powerManager = getSystemService(PowerManager::class.java)
        val appContext = applicationContext
        appScope.launch {
            try {
                // Off main, where the saved language can be read, so a channel is first created in the app language (#348).
                publishNotificationChannels(appContext)
                // filesDir / models path are disk reads — stay on Dispatchers.IO.
                installRecordingFilesDir(filesDir.absolutePath)
                // Before AiRuntimeHolder.install: WorkManager can re-run a killed job as soon as the
                // holder is set, and it would race this sweep for its own tmp/pcm-<id> (#197).
                runCatching {
                    val spills = sweepOrphanPcmSpill(
                        System.currentTimeMillis() - STALE_PCM_SPILL_AGE_MS,
                        modelsRoot = ModelStoreDir(File(filesDir, "models").absolutePath),
                        defaultRoot = defaultModelStoreRoot(),
                    )
                    if (spills > 0) Log.i(TAG, "Removed $spills orphaned PCM spill dir(s)")
                }.onFailure { t -> Log.w(TAG, "PCM spill sweep failed (${t::class.simpleName})") }
                val modelsDir = File(filesDir, "models")
                // A GGUF Qwen the catalog no longer offers can still sit in models/ (~2.5 GB) with no way to remove it (#273).
                runCatching {
                    val removed = dev.velum.ai.local.RetiredModels.sweep(ModelStoreDir(modelsDir.absolutePath))
                    if (removed.isNotEmpty()) Log.i(TAG, "Removed retired model dir(s): $removed")
                }.onFailure { t -> Log.w(TAG, "Retired model sweep failed (${t::class.simpleName})") }
                // A share copied by a process that died before importing it is offered again (hash dedupe covers a finished one; a half-finished one is #411, a queued row with no job is #415) (#405).
                runCatching {
                    val offered = dev.velum.app.platform.IncomingShare.recoverPending(appContext)
                    if (offered.isNotEmpty()) Log.i(TAG, "Re-offered ${offered.size} pending share(s)")
                }.onFailure { t -> Log.w(TAG, "Pending share recovery failed (${t::class.simpleName})") }
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
                built.repository.onIndexFailure = { cls -> Log.w(TAG, "FTS index write failed, index flagged stale ($cls)") }
                built.repository.ftsRebuildFailure?.let { Log.w(TAG, "FTS rebuild failed, index stale ($it)") }
                // Before the runtime is installed, so a cold-start worker never reads a row whose file is mid-move.
                runCatching {
                    val moved = migrateCachedImports(
                        built.repository, java.io.File(recordingsDirectoryPath()), cacheDir,
                        pendingPaths = dev.velum.app.platform.IncomingShare.pendingPaths(appContext).toSet(),
                    )
                    if (moved > 0) Log.i(TAG, "Moved $moved imported recording(s) out of the cache")
                }.onFailure { t -> Log.w(TAG, "Import migration failed (${t::class.simpleName})") }
                AiRuntimeHolder.install(
                    processor = built.recordingProcessor,
                    repository = built.repository,
                    settings = built.settingsRepository,
                )
                // A cold-process job reads this before the runtime is installed (#348); saved only once settings loaded.
                appScope.launch { dev.velum.ai.pipeline.keepAppLanguageHint(appContext, built.settingsRepository) }
                // Channel names are stored by the system; re-publish them when the in-app language changes.
                appScope.launch {
                    built.settingsRepository.preferences.map { it.appLanguage }.distinctUntilChanged().collect {
                        publishNotificationChannels(appContext)
                    }
                }
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
                        runCatching {
                            val swept = sweepStaleImportCopies(
                                File(appContext.cacheDir, "imports"),
                                c.repository.getAllRecordingFileRefs().map { it.second },
                                // recoverPending ran before the container was built: what is still marked is its to expire.
                                pendingShares = dev.velum.app.platform.IncomingShare.pendingPaths(appContext),
                            )
                            if (swept > 0) Log.i(TAG, "Removed $swept stale import copie(s)")
                        }.onFailure { t -> Log.w(TAG, "Import copy sweep failed (${t::class.simpleName})") }
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
                    val recovered = built.micRecordings.underLock {
                        recoverPartialMicRecordings(
                            recordingsDirPath = recordingsDirectoryPath(),
                            repository = built.repository,
                            protectedRecording = { built.micRecordings.protects(it) || built.recordingProcessor.isDeleting(it) },
                        )
                    }
                    for (id in recovered.recoveredIds) built.runDetached {
                        runCatching { built.enqueueSavedRecording(id) }
                            .onFailure { Log.w(TAG, "Recovered recording enqueue failed (${it::class.simpleName})") }
                    }
                    dev.velum.app.platform.reconcileInterruptedMicDrafts(built, recordingsDirectoryPath())
                }.onFailure { t ->
                    Log.e(TAG, "Partial mic recovery failed", t)
                }
                // A row left queued with no job (the process died, or enqueueing failed, after the row was saved) (#415).
                runCatching { built.runDetached { built.requeueStrandedRecordings() } }
                    .onFailure { t -> Log.w(TAG, "Requeue of stranded recordings failed (${t::class.simpleName})") }
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
