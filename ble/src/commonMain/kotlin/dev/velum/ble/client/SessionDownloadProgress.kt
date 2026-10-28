package dev.velum.ble.client

/**
 * Durable checkpoint for an in-flight session download.
 * [verifiedOffset] is the contiguous prefix that has been written and flushed;
 * resume always continues from this offset (no gaps, no duplicates).
 */
data class SessionDownloadCheckpoint(
    val sessionId: Long,
    val expectedSizeBytes: Long,
    val verifiedOffset: Long,
    /** Running CRC-16/CCITT (init 0xFFFF) over the verified prefix. */
    val runningCrc: Int,
    val partialPath: String,
    val status: SessionDownloadStatus = SessionDownloadStatus.InProgress,
) {
    init {
        require(verifiedOffset >= 0) { "verifiedOffset must be >= 0" }
        require(expectedSizeBytes >= 0) { "expectedSizeBytes must be >= 0" }
        require(verifiedOffset <= expectedSizeBytes) {
            "verifiedOffset=$verifiedOffset exceeds expectedSizeBytes=$expectedSizeBytes"
        }
    }
}

enum class SessionDownloadStatus {
    InProgress,
    /** Transfer finished and sync-stop CRC matched. */
    Completed,
    /**
     * Sync-stop CRC mismatched. Partial file is kept for recovery; never treat as a
     * good recording. Resume/retry should re-fetch (typically from offset 0).
     */
    CrcFailed,
}

/**
 * Atomic persistence of [SessionDownloadCheckpoint].
 * Implementations must make [save] crash-safe (write temp + rename, or equivalent).
 */
interface SessionDownloadProgressStore {
    fun load(sessionId: Long): SessionDownloadCheckpoint?

    fun save(checkpoint: SessionDownloadCheckpoint)

    fun clear(sessionId: Long)
}

/** Process-local store for unit tests and in-memory hosts. */
class InMemorySessionDownloadProgressStore : SessionDownloadProgressStore {
    private val map = linkedMapOf<Long, SessionDownloadCheckpoint>()

    override fun load(sessionId: Long): SessionDownloadCheckpoint? = map[sessionId]

    override fun save(checkpoint: SessionDownloadCheckpoint) {
        map[checkpoint.sessionId] = checkpoint
    }

    override fun clear(sessionId: Long) {
        map.remove(sessionId)
    }

    fun clearAll() {
        map.clear()
    }
}
