package dev.velum.app

import android.content.Context
import android.util.Log
import dev.velum.ai.pipeline.RecordingProcessWorker
import dev.velum.app.platform.DownloadProgressNotifier
import dev.velum.app.platform.MicRecordingService
import kotlinx.coroutines.CancellationException

/**
 * Creates or renames the notification channels in the current in-app language. A failure is logged and goes no
 * further: these run in startup coroutines, where an exception would end the process.
 */
internal fun publishNotificationChannels(context: Context) {
    runCatching {
        MicRecordingService.ensureNotificationChannel(context)
        DownloadProgressNotifier.ensureChannel()
        RecordingProcessWorker.ensureProcessingChannel(context)
    }.onFailure { t -> Log.w("VelumStartup", "Notification channel setup failed (${t::class.simpleName})") }
}

/**
 * #119: startup cleanup must finish before the container is published. Nothing can start a
 * download/import/mic draft until [publish] runs, so cleanup never races user work.
 * A failing cleanup is reported and publish still happens; cancellation skips publish.
 */
suspend fun <T> cleanupThenPublish(
    built: T,
    cleanup: suspend (T) -> Unit,
    publish: (T) -> Unit,
    onCleanupFailure: (Throwable) -> Unit = {},
) {
    try {
        cleanup(built)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        onCleanupFailure(t)
    }
    publish(built)
}
