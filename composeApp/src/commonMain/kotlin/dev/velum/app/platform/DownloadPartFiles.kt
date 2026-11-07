package dev.velum.app.platform

/** In-progress recorder download; never a finalized recording. */
const val DOWNLOAD_PART_SUFFIX = ".part"

/** Startup removes `rec-*.part` older than this (a download never survives a process restart). */
const val STALE_DOWNLOAD_PART_AGE_MS: Long = 24L * 60L * 60L * 1000L

/**
 * Streams [produce]'s chunks into [partPath], fsyncs it, then atomically moves it onto the
 * path [finalPathFor] picks from the first [headBytes] bytes, replacing any existing file
 * without a window where none exists. The part file is deleted on any failure, leaving an
 * existing target untouched. Returns the final path and byte count.
 */
expect fun streamToFileAtomically(
    partPath: String,
    headBytes: Int,
    finalPathFor: (head: ByteArray) -> String,
    produce: (sink: (ByteArray) -> Unit) -> Unit,
): Pair<String, Long>

/** Deletes `rec-*.part` files in [dirPath] last modified before [olderThanEpochMs]; returns their names. */
expect fun sweepStaleDownloadParts(dirPath: String, olderThanEpochMs: Long): List<String>
