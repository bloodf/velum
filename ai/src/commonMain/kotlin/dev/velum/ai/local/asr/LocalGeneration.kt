package dev.velum.ai.local.asr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keeps native resources owned until the final callback, including after cancellation. */
internal suspend fun awaitLocalText(
    start: (onText: (String) -> Unit, onDone: () -> Unit, onError: (Throwable) -> Unit) -> Unit,
    cancel: () -> Unit,
): String {
    val text = StringBuilder()
    val finished = CompletableDeferred<Unit>()
    currentCoroutineContext().ensureActive()
    start({ text.append(it) }, { finished.complete(Unit) }, { finished.completeExceptionally(it) })
    try {
        finished.await()
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            try {
                cancel()
            } finally {
                // A cancellation request is not proof that the native worker has stopped using its handles.
                runCatching { finished.await() }
            }
        }
        throw cancelled
    }
    currentCoroutineContext().ensureActive()
    return text.toString().trim()
}
