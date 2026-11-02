package dev.velum.ble.client

import dev.velum.protocol.Crc16Ccitt
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Bytes received so far for one session. [length] is where a resumed transfer continues, so a durable
 * implementation must report only bytes it has flushed.
 */
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
 * failed connection, lost link, timeout or integrity failure (via SYNC_FILE_START's start offset). Only
 * attempts that store no new bytes count against [maxNoProgressAttempts], with a growing delay between
 * them, so a multi-hour transfer cannot run out of retries while it is progressing.
 *
 * [connect] brings the link up and returns a new client; this class unlocks it, runs the handshake and
 * closes it after the attempt. A store already holding all the bytes is returned without connecting,
 * with [CrcStatus.NotProvided]. Cancellation of the caller is never retried.
 *
 * Simulated only: whether a real Note Pro honours a non-zero start offset is unverified (#6).
 */
class ResumableSessionDownload(
    private val connect: suspend () -> PlaudDeviceClient,
    private val crcPolicy: CrcPolicy,
    private val maxNoProgressAttempts: Int = 5,
) {
    init {
        require(maxNoProgressAttempts >= 1)
    }

    suspend fun download(
        sessionId: Long,
        sizeBytes: Long,
        store: PartialTransferStore,
        onProgress: ((receivedBytes: Long) -> Unit)? = null,
    ): ResumableDownloadResult {
        if (sizeBytes > 0 && store.length() > sizeBytes) store.truncate(0)
        var attempts = 0
        var noProgress = 0
        var crcMismatches = 0
        var lastError: Exception? = null
        while (true) {
            coroutineContext.ensureActive()
            if (sizeBytes > 0 && store.length() == sizeBytes) {
                return ResumableDownloadResult(sizeBytes, CrcStatus.NotProvided, attempts)
            }
            attempts++
            val before = store.length()
            var client: PlaudDeviceClient? = null
            try {
                client = connect()
                client.unlock()
                client.handshake()
                val deviceCrc = client.downloadSessionTo(sessionId, sizeBytes, store, onProgress, startOffset = before)
                val crc = crcStatus(deviceCrc, store)
                if (crc !is CrcStatus.Mismatch || crcPolicy == CrcPolicy.ReportOnly) {
                    return ResumableDownloadResult(store.length(), crc, attempts)
                }
                store.truncate(0)
                crcMismatches++
                lastError = TransferIntegrityException("CRC mismatch")
            } catch (e: TimeoutCancellationException) {
                // Our own timeout (lost link, silent device); a caller cancellation fails ensureActive below.
                lastError = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            } finally {
                client?.close()
            }
            // Lost links between complete transfers reset noProgress, so mismatches have their own cap.
            if (crcMismatches >= MAX_CRC_MISMATCHES) {
                throw TransferIntegrityException("CRC mismatch on $crcMismatches complete transfers", lastError)
            }
            noProgress = if (store.length() > before) 0 else noProgress + 1
            if (noProgress >= maxNoProgressAttempts) {
                throw TransferIntegrityException("Session download made no progress in $noProgress attempts", lastError)
            }
            coroutineContext.ensureActive()
            if (noProgress > 0) delay(backoff(noProgress))
        }
    }

    private fun backoff(noProgress: Int): Duration =
        (1L shl (noProgress - 1).coerceAtMost(MAX_BACKOFF_SHIFT)).seconds

    private fun crcStatus(deviceCrc: Int, store: PartialTransferStore): CrcStatus {
        if (deviceCrc == PlaudDeviceClient.NO_DEVICE_CRC) return CrcStatus.NotProvided
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
        const val CRC_BLOCK = 64 * 1024
        const val MAX_CRC_MISMATCHES = 3
        const val MAX_BACKOFF_SHIFT = 5 // 32 s
    }
}
