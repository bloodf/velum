package dev.velum.app.ui.recording

import dev.velum.core.db.Speaker

/**
 * Speaker rows get stable ids ("recording-S1"), so a rerun that replaces them keeps the same ids. What tells two
 * runs apart is the label and talk time; display_name is excluded because renaming the speakers is what this
 * screen does.
 */
internal fun sameRun(a: List<Speaker>, b: List<Speaker>): Boolean =
    a.map { Triple(it.id, it.label, it.talk_seconds) } == b.map { Triple(it.id, it.label, it.talk_seconds) }

/**
 * Applies [renames] typed against the [shown] rows. A background rerun can replace the rows while the sheet is open;
 * the names would then land on, and enrol the voice of, the new run's speakers. Per name: re-check the stored rows,
 * rename ([rename] checks atomically with its write and returns false when the row is no longer the one shown),
 * re-check, and only then [enrol]. A refused rename enrols nothing, and a swap right after the rename costs the enrolment
 * (the name stays), which is the smaller loss. On a mismatch nothing further is renamed or enrolled and false is returned.
 *
 * Remaining window: a swap after the last re-check and before the enrolment's own read still enrols the new run's cached
 * voice (it is keyed by label only); it is a few calls wide and cannot be closed from here.
 */
internal suspend fun confirmSpeakerNames(
    shown: List<Speaker>,
    current: suspend () -> List<Speaker>,
    renames: Map<String, String>,
    enrol: suspend (row: Speaker, name: String) -> Unit,
    rename: suspend (row: Speaker, name: String) -> Boolean,
): Boolean {
    for ((speakerId, name) in renames) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) continue
        val row = shown.firstOrNull { it.id == speakerId } ?: continue
        if (!sameRun(current(), shown)) return false
        if (!rename(row, trimmed)) return false
        if (!sameRun(current(), shown)) return false
        enrol(row, trimmed)
    }
    return true
}
