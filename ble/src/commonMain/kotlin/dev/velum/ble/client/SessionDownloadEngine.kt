package dev.velum.ble.client

import dev.velum.protocol.Crc16Ccitt
import kotlinx.coroutines.TimeoutCancellationException

/**
 * App-scoped download-path owner for resumable, CRC-verified session transfers.
 *
 * Persists verified offsets atomically and streams into a durable partial file so
 * disconnect / process death / FGS restart can continue without gaps or duplicates.
 * Does not live in a ViewModel — wire from AppContainer / SessionDownloadService.
 *
 * On CRC mismatch the partial file is kept ([SessionDownloadStatus.CrcFailed]) and
 * never reported as a successful recording.
 */
class SessionDownloadEngine(
    private val progressStore: SessionDownloadProgressStore,
    private val partialFiles: SessionPartialFileFactory,
) {
    /** Last persisted checkpoint for [sessionId], if any (process-death resume probe). */
    fun checkpoint(sessionId: Long): SessionDownloadCheckpoint? = progressStore.load(sessionId)
    data class Success(
        val sessionId: Long,
        val sizeBytes: Long,
        val crc: Int,
        val partialPath: String,
    )

    /**
     * Resume or start a download. [download] receives the resume offset and must stream
     * only new bytes into [SessionDownloadSink.sink], calling [SessionDownloadSink.onVerified]
     * after each contiguous chunk is durable.
     */
    suspend fun download(
        sessionId: Long,
        sizeBytes: Long,
        onProgress: ((verifiedBytes: Long) -> Unit)? = null,
        download: suspend (SessionDownloadSink) -> SessionDownloadReceipt,
    ): Success {
        require(sizeBytes in 0..PlaudDeviceClient.MAX_SESSION_DOWNLOAD_BYTES) {
            "Session sizeBytes=$sizeBytes exceeds cap of ${PlaudDeviceClient.MAX_SESSION_DOWNLOAD_BYTES}"
        }

        val existing = progressStore.load(sessionId)
        if (existing?.status == SessionDownloadStatus.Completed &&
            existing.verifiedOffset == existing.expectedSizeBytes &&
            existing.expectedSizeBytes == sizeBytes
        ) {
            return Success(
                sessionId = sessionId,
                sizeBytes = sizeBytes,
                crc = existing.runningCrc,
                partialPath = existing.partialPath,
            )
        }

        // CRC failure: keep prior partial recoverable under a renamed path is out of scope;
        // clear checkpoint and allocate a fresh partial so retry fetches exact bytes.
        val restartFromScratch = existing?.status == SessionDownloadStatus.CrcFailed
        val recoverableCrcFailedPath =
            if (restartFromScratch) existing?.partialPath else null
        if (restartFromScratch) {
            progressStore.clear(sessionId)
        }

        val checkpoint = when {
            !restartFromScratch &&
                existing != null &&
                existing.status == SessionDownloadStatus.InProgress &&
                existing.expectedSizeBytes == sizeBytes -> existing
            else -> {
                val path = partialFiles.allocatePath(sessionId)
                SessionDownloadCheckpoint(
                    sessionId = sessionId,
                    expectedSizeBytes = sizeBytes,
                    verifiedOffset = 0L,
                    runningCrc = Crc16Ccitt.INITIAL,
                    partialPath = path,
                    status = SessionDownloadStatus.InProgress,
                ).also { progressStore.save(it) }
            }
        }

        // Recoverable CRC-failed file is left on disk; do not delete (caller may inspect).
        @Suppress("UNUSED_VARIABLE")
        val keptForRecovery = recoverableCrcFailedPath

        val partial = partialFiles.open(checkpoint.partialPath)
        try {
            if (partial.size() > checkpoint.verifiedOffset) {
                partial.truncateTo(checkpoint.verifiedOffset)
            }
            require(partial.size() == checkpoint.verifiedOffset) {
                "Partial file size=${partial.size()} != verifiedOffset=${checkpoint.verifiedOffset}"
            }

            var verifiedOffset = checkpoint.verifiedOffset
            var runningCrc = checkpoint.runningCrc

            val sink = SessionDownloadSink(
                sessionId = sessionId,
                sizeBytes = sizeBytes,
                startOffset = verifiedOffset,
                initialCrc = runningCrc,
                sink = SessionByteSink { data -> partial.write(data) },
                onVerified = { offset, crc ->
                    partial.sync()
                    verifiedOffset = offset
                    runningCrc = crc
                    progressStore.save(
                        SessionDownloadCheckpoint(
                            sessionId = sessionId,
                            expectedSizeBytes = sizeBytes,
                            verifiedOffset = offset,
                            runningCrc = crc,
                            partialPath = checkpoint.partialPath,
                            status = SessionDownloadStatus.InProgress,
                        ),
                    )
                    onProgress?.invoke(offset)
                },
            )

            val receipt = try {
                download(sink)
            } catch (t: SessionCrcMismatchException) {
                progressStore.save(
                    SessionDownloadCheckpoint(
                        sessionId = sessionId,
                        expectedSizeBytes = sizeBytes,
                        verifiedOffset = verifiedOffset,
                        runningCrc = runningCrc,
                        partialPath = checkpoint.partialPath,
                        status = SessionDownloadStatus.CrcFailed,
                    ),
                )
                throw SessionCrcMismatchException(
                    sessionId = sessionId,
                    expectedCrc = t.expectedCrc,
                    actualCrc = t.actualCrc,
                    verifiedOffset = verifiedOffset,
                    partialPath = checkpoint.partialPath,
                )
            } catch (t: TimeoutCancellationException) {
                // Disconnect / mid-stream drop: verified offset already persisted.
                throw t
            }

            if (sizeBytes > 0L && verifiedOffset < sizeBytes) {
                throw SessionDownloadTruncatedException(
                    sessionId = sessionId,
                    expectedSizeBytes = sizeBytes,
                    verifiedOffset = verifiedOffset,
                )
            }

            val deviceCrc = receipt.deviceCrc
            if (deviceCrc != runningCrc) {
                progressStore.save(
                    SessionDownloadCheckpoint(
                        sessionId = sessionId,
                        expectedSizeBytes = sizeBytes,
                        verifiedOffset = verifiedOffset,
                        runningCrc = runningCrc,
                        partialPath = checkpoint.partialPath,
                        status = SessionDownloadStatus.CrcFailed,
                    ),
                )
                throw SessionCrcMismatchException(
                    sessionId = sessionId,
                    expectedCrc = deviceCrc,
                    actualCrc = runningCrc,
                    verifiedOffset = verifiedOffset,
                    partialPath = checkpoint.partialPath,
                )
            }

            progressStore.save(
                SessionDownloadCheckpoint(
                    sessionId = sessionId,
                    expectedSizeBytes = sizeBytes,
                    verifiedOffset = verifiedOffset,
                    runningCrc = runningCrc,
                    partialPath = checkpoint.partialPath,
                    status = SessionDownloadStatus.Completed,
                ),
            )
            return Success(
                sessionId = sessionId,
                sizeBytes = verifiedOffset,
                crc = runningCrc,
                partialPath = checkpoint.partialPath,
            )
        } finally {
            partial.close()
        }
    }
}

/**
 * Parameters for a single resume attempt. [sink] must only receive bytes at/after
 * [startOffset]; [onVerified] is invoked after each contiguous durable chunk.
 */
class SessionDownloadSink(
    val sessionId: Long,
    val sizeBytes: Long,
    val startOffset: Long,
    val initialCrc: Int,
    val sink: SessionByteSink,
    val onVerified: (verifiedOffset: Long, runningCrc: Int) -> Unit,
)

/** Result of a protocol sync (cmd 28/29) after streaming into a sink. */
data class SessionDownloadReceipt(
    val sessionId: Long,
    val deviceCrc: Int,
    val verifiedOffset: Long,
)

/**
 * Wire [PlaudDeviceClient.downloadSessionTo] into a [SessionDownloadSink].
 * Leaves final CRC checking to [SessionDownloadEngine] when [verifyDeviceCrc] is false.
 */
suspend fun PlaudDeviceClient.downloadInto(
    sink: SessionDownloadSink,
    onProgress: ((receivedBytes: Long) -> Unit)? = null,
    verifyDeviceCrc: Boolean = false,
    overlapReader: ((offset: Long, length: Int) -> ByteArray)? = null,
): SessionDownloadReceipt =
    downloadSessionTo(
        sessionId = sink.sessionId,
        sizeBytes = sink.sizeBytes,
        sink = sink.sink,
        onProgress = onProgress,
        startOffset = sink.startOffset,
        initialCrc = sink.initialCrc,
        onVerifiedOffset = sink.onVerified,
        verifyDeviceCrc = verifyDeviceCrc,
        overlapReader = overlapReader,
    )
