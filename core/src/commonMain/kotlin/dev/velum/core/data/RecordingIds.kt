package dev.velum.core.data

import kotlin.random.Random

/**
 * Collision-resistant recording primary keys.
 *
 * SQLDelight `insertRecording` is `INSERT OR REPLACE` — a short birthday space
 * (e.g. `1000..9999`) silently destroys another recording on clash.
 */
object RecordingIds {
    fun newId(
        nowEpochSeconds: Long = epochSecondsNow(),
        random: Random = Random.Default,
    ): String = "rec-$nowEpochSeconds-${random.nextLong(1_000_000_000L, 9_999_999_999L)}"
}
