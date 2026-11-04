package dev.velum.app.ui.recording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the screen does once the player's `prepare` returns. `prepare` runs on IO, and under the Compose test
 * dispatcher the caller can resume inline on that thread, so the seek (which publishes the player's position) and the
 * error state are applied on Main (#345).
 */
internal suspend fun afterPrepare(ok: Boolean, seekMs: Long, seekTo: (Long) -> Unit, onUnavailable: () -> Unit) {
    withContext(Dispatchers.Main.immediate) {
        if (!ok) onUnavailable() else if (seekMs > 0) seekTo(seekMs)
    }
}
