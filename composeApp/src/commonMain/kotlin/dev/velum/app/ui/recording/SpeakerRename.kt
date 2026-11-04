package dev.velum.app.ui.recording

import dev.velum.core.db.Speaker

/**
 * Applies [renames] typed against the [shown] rows. Speaker ids are stable across runs ("recording-S1"), so a
 * background rerun that replaces the rows while the sheet is open would otherwise get a name (and an enrolled voice)
 * typed for the old run. Each name is saved only while the stored rows still equal [shown]; otherwise nothing more is
 * saved and false is returned (the caller closes the sheet).
 */
internal suspend fun confirmSpeakerNames(
    shown: List<Speaker>,
    current: suspend () -> List<Speaker>,
    renames: Map<String, String>,
    save: suspend (row: Speaker, name: String) -> Unit,
): Boolean {
    for ((speakerId, name) in renames) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) continue
        val row = shown.firstOrNull { it.id == speakerId } ?: continue
        if (current() != shown) return false
        save(row, trimmed)
    }
    return true
}
