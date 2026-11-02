package dev.velum.app.platform

actual fun streamToFileAtomically(
    partPath: String,
    headBytes: Int,
    finalPathFor: (head: ByteArray) -> String,
    produce: (sink: (ByteArray) -> Unit) -> Unit,
): Pair<String, Long> = error("Recorder download persistence is Android-first; iOS file IO lands with M3")

actual fun sweepStaleDownloadParts(dirPath: String, olderThanEpochMs: Long): List<String> = emptyList()
