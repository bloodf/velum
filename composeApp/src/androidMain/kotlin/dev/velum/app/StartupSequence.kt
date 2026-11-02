package dev.velum.app

import kotlinx.coroutines.CancellationException

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
