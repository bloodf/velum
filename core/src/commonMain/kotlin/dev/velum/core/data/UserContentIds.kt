package dev.velum.core.data

import kotlin.random.Random

/**
 * Collision-resistant ids for user templates / memory / glossary.
 *
 * SQLDelight upserts use `INSERT OR REPLACE` — a 5-digit birthday space
 * (`Random.nextInt(100000)`) silently destroys another row on clash.
 * Match [RecordingIds] width (1e9–1e10).
 */
object UserContentIds {
    fun template(
        nowEpochSeconds: Long = epochSecondsNow(),
        random: Random = Random.Default,
    ): String = "tpl-user-$nowEpochSeconds-${wide(random)}"

    fun memory(
        nowEpochSeconds: Long = epochSecondsNow(),
        random: Random = Random.Default,
    ): String = "mem-$nowEpochSeconds-${wide(random)}"

    fun glossary(
        nowEpochSeconds: Long = epochSecondsNow(),
        random: Random = Random.Default,
    ): String = "gloss-$nowEpochSeconds-${wide(random)}"

    private fun wide(random: Random): Long =
        random.nextLong(1_000_000_000L, 9_999_999_999L)
}
