package dev.velum.app.platform

/** A file shared into the app, or the news that copying it failed (the user must be told). */
sealed interface SharedFile {
    data class Ready(val path: String) : SharedFile
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
