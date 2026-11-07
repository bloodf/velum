package dev.velum.app.platform

/** A file shared into the app, or the news that copying it failed (the user must be told). */
sealed interface SharedFile {
    /** [recoveredAtMs] is set when this share was found pending after a restart (the time it was copied): report it quietly. */
    data class Ready(val path: String, val recoveredAtMs: Long? = null) : SharedFile
    data object CopyFailed : SharedFile
}

/**
 * Suspends until the next shared file has been copied (or failed to), and returns it; null on platforms with no share
 * intake (the caller stops). Android copies off the main thread, so results can arrive after composition starts.
 */
expect suspend fun awaitIncomingSharePath(): SharedFile?

/**
 * Tells the platform a copied share has been fully handled (imported, found duplicate, failed or refused), so it is not
 * offered again after a restart. Android persists a pending marker per copy; iOS and desktop have no share intake.
 */
expect fun markShareHandled(path: String)

/** Counts that the import of [path] has started, durably (Android). A share whose import never finishes is dropped after a few starts. */
expect fun markShareAttempt(path: String)

/** Forgets every pending share. A wipe calls this first, so wiped audio is never offered again after a restart. */
expect fun clearPendingShares()

/** One approval applies to one external share only. A restart creates a fresh gate. */
class ExternalShareConsent {
    private val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private var consumed = false

    fun decide(approved: Boolean) { decision.complete(approved) }

    suspend fun runIfApproved(action: () -> Unit): Boolean {
        if (!decision.await() || consumed) return false
        consumed = true
        action()
        return true
    }
}
