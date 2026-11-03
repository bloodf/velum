package dev.velum.app.platform

/**
 * Suspends until the next file shared into the app has been copied, then returns its path; null on platforms with no
 * share intake (the caller stops). Android copies off the main thread, so the path can arrive after composition starts.
 */
expect suspend fun awaitIncomingSharePath(): String?
