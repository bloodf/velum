package dev.velum.ble.client

import dev.velum.protocol.Crc16Ccitt
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive

/** Bytes received so far for one session; [length] is where a resumed transfer continues. */
interface PartialTransferStore : SessionByteSink {
    fun length(): Long

    /** Drop everything from [length] on (a CRC mismatch restarts from 0). */
    fun truncate(length: Long)

    fun read(offset: Long, length: Int): ByteArray
}

/**
 * How a CRC mismatch is handled. The Note Pro CRC algorithm is unconfirmed on hardware (#6) and the
 * only real capture reports `0xFFFF`, so real devices must never fail or claim verification on it.
 */
enum class CrcPolicy {
    /** Fake peripheral: a mismatch fails the attempt and the transfer restarts from offset 0. */
    Enforce,

    /** Real devices (owner decision, #94): a mismatch keeps the file and is reported, never [CrcStatus.Verified]. */
    ReportOnly,
}

sealed interface CrcStatus {
    /** Device CRC matched CRC-16/CCITT-FALSE of the whole received file. */
    data object Verified : CrcStatus

    /** Device reported `0xFFFF` (no CRC). */
    data object NotProvided : CrcStatus

    data class Mismatch(val device: Int, val computed: Int) : CrcStatus
}

data class ResumableDownloadResult(val sizeBytes: Long, val crc: CrcStatus, val attempts: Int)

/**
 * Downloads one session into [PartialTransferStore], resuming from the bytes already stored after a
 * lost link, timeout or integrity failure (via SYNC_FILE_START's start offset), up to [maxAttempts]
 * connections. [connect] must return a fresh, unlocked client; each one is closed after its attempt.
 * Cancellation of the caller is never retried.
 *
 * Simulated only: whether a real Note Pro honours a non-zero start offset is unverified (#6).
 */
class ResumableSessionDownload(
    private val connect: suspend () -> PlaudDeviceClient,
    private val crcPolicy: CrcPolicy,
    private val maxAttempts: Int = 5,
) {
    init {
        require(maxAttempts >= 1)
    }

    suspend fun download(
        sessionId: Long,
        sizeBytes: Long,
        store: PartialTransferStore,
        onProgress: ((receivedBytes: Long) -> Unit)? = null,
    ): ResumableDownloadResult {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            coroutineContext.ensureActive()
            val client = connect()
            try {
                val deviceCrc = client.downloadSessionTo(sessionId, sizeBytes, store, onProgress, startOffset = store.length())
                val crc = crcStatus(deviceCrc, store)
                if (crc is CrcStatus.Mismatch && crcPolicy == CrcPolicy.Enforce) {
                    store.truncate(0)
                    lastError = TransferIntegrityException("CRC mismatch")
                    continue
                }
                return ResumableDownloadResult(store.length(), crc, attempt)
            } catch (e: TimeoutCancellationException) {
                // A lost link: the client stopped hearing from the device. Not a caller cancellation.
                lastError = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            } finally {
                client.close()
            }
        }
        throw TransferIntegrityException("Session download failed after $maxAttempts attempts", lastError)
    }

    private fun crcStatus(deviceCrc: Int, store: PartialTransferStore): CrcStatus {
        if (deviceCrc == NO_CRC) return CrcStatus.NotProvided
        var crc = 0xFFFF
        var offset = 0L
        val total = store.length()
        while (offset < total) {
            val n = minOf(CRC_BLOCK.toLong(), total - offset).toInt()
            crc = Crc16Ccitt.compute(store.read(offset, n), crc)
            offset += n
        }
        return if (crc == deviceCrc) CrcStatus.Verified else CrcStatus.Mismatch(deviceCrc, crc)
    }

    private companion object {
        const val NO_CRC = 0xFFFF
        const val CRC_BLOCK = 64 * 1024
    }
}
