package dev.velum.ai.pipeline

/**
 * Loop guard for processing that could not be promoted to the foreground (#330). Unpromoted work is stopped after
 * about 10 minutes, the processor restores the row and WorkManager reruns it from the start, so a long local job
 * could loop without progress. The unpromoted run itself is recorded through [setFlag] (the caller stores the work id, so a REPLACE enqueue is not a rerun); a run ends the flag by
 * finishing on its own, so only a rerun that follows a *stopped* unpromoted run fails. Retries for other reasons
 * (thermal, runtime holder, backoff) leave the flag clear and never fail.
 *
 * @return true when this run must fail instead of starting again.
 */
internal suspend fun failUnpromotedRerun(
    promoted: Boolean,
    apiOnly: Boolean,
    wasUnpromoted: suspend () -> Boolean,
    setFlag: suspend (Boolean) -> Unit,
): Boolean {
    if (promoted || apiOnly) {
        setFlag(false)
        return false
    }
    if (wasUnpromoted()) {
        setFlag(false)
        return true
    }
    setFlag(true)
    return false
}
