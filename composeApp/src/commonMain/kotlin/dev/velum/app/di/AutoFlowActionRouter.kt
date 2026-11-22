package dev.velum.app.di

import dev.velum.ai.integrations.WebhookFailure
import dev.velum.ai.pipeline.AutoFlowUiAction
import dev.velum.app.platform.ExportFolderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What a screen shows or launches for an AutoFlow post-action. */
sealed class AutoFlowUiEvent {
    /** Needs an Activity: share sheet, calendar editor, permission prompt, or the failure snackbar. */
    data class Launch(val action: AutoFlowUiAction) : AutoFlowUiEvent()

    /** The webhook was already posted; [detail] is `HTTP <n>` or a [WebhookFailure] code, null when not configured. */
    data class WebhookPosted(val detail: String?) : AutoFlowUiEvent()

    /** The note was already exported; null when the recording is gone. */
    data class FolderExported(val result: ExportFolderResult?) : AutoFlowUiEvent()
}

/**
 * The single, process-lifetime consumer of [dev.velum.ai.pipeline.AutoFlowRunner.actions].
 *
 * AutoFlow runs for the life of the process, so a WorkManager job can finish a recording while no screen exists. The
 * runner's SharedFlow drops what nobody collects, and only a screen used to collect it: a configured webhook or folder
 * export then silently never happened. Those two need no screen and run here as soon as the action arrives; everything
 * else (and the outcome of those two, for the snackbar) waits in a bounded queue until a screen drains [uiEvents].
 * A queue, not a replayed flow: each event reaches one screen once, not again after every recreation.
 */
class AutoFlowActionRouter(
    private val postWebhook: suspend (recordingId: String) -> String?,
    private val exportToFolder: suspend (recordingId: String) -> ExportFolderResult?,
    capacity: Int = 32,
) {
    private val pending = Channel<AutoFlowUiEvent>(capacity, BufferOverflow.DROP_OLDEST)

    /** Drained by the visible screen; events produced while none exists wait here. */
    val uiEvents: Flow<AutoFlowUiEvent> = pending.receiveAsFlow()

    /**
     * Subscribes to [actions] before returning (undispatched start), so nothing the runner emits after this call is
     * dropped for lack of a collector. Start it before the runner.
     */
    fun start(scope: CoroutineScope, actions: Flow<AutoFlowUiAction>): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            actions.collect { action ->
                when (action) {
                    // Network / storage work runs beside the collector: a webhook that takes its full timeout must not
                    // hold back the next recording's post-action.
                    is AutoFlowUiAction.PostWebhook, is AutoFlowUiAction.ExportMarkdownToFolder -> launch { handle(action) }
                    else -> handle(action)
                }
            }
        }

    suspend fun handle(action: AutoFlowUiAction) {
        val event = when (action) {
            is AutoFlowUiAction.PostWebhook -> AutoFlowUiEvent.WebhookPosted(
                failSafe(WebhookFailure.CONNECTION) { postWebhook(action.recordingId) },
            )
            is AutoFlowUiAction.ExportMarkdownToFolder -> AutoFlowUiEvent.FolderExported(
                failSafe(ExportFolderResult.Failed("export failed")) { exportToFolder(action.recordingId) },
            )
            else -> AutoFlowUiEvent.Launch(action)
        }
        pending.send(event)
    }

    /** A failed post-action must not end the collector: later recordings still get theirs. Cancellation propagates. */
    private suspend fun <T> failSafe(onFailure: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onFailure
    }
}
