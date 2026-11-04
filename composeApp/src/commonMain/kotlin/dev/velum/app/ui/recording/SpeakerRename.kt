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
 * the names would then land on, and enrol the voice of, the new run's speakers. The stored rows are re-checked before
 * each enrolment, and [rename] re-checks atomically with its write (it returns false when the row is no longer the one
 * shown). On a mismatch nothing further is enrolled or renamed and false is returned.
 *
 * Remaining window: a swap between the check before [enrol] and the enrolment itself still enrols against the new
 * run (the cached voice is keyed by label only); that gap is a few calls wide and cannot be closed from here.
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
        enrol(row, trimmed)
        if (!rename(row, trimmed)) return false
    }
    return true
}
