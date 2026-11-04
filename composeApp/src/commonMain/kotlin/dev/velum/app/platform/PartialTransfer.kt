package dev.velum.app.platform

import dev.velum.ble.client.PartialTransferStore

/** Partial downloads not resumed within this time are removed at startup. */
const val STALE_PARTIAL_TRANSFER_MS: Long = 7L * 24L * 60L * 60L * 1000L

/** A session's encrypted bytes kept across restarts until they are decrypted and persisted. */
interface PartialTransfer : PartialTransferStore {
    /** Whether the device's checksum for this complete transfer did not match (kept across restarts). */
    fun crcMismatch(): Boolean

    /** Records or clears the mismatch durably, so a retry after a failed save still reports it. */
    fun setCrcMismatch(mismatch: Boolean)

    /** Consecutive failed attempts to read this complete partial back (kept across restarts). */
    fun readFailures(): Int

    fun setReadFailures(count: Int)

    fun close()

    /** Close and remove the partial data. False when some of it is still there. */
    fun delete(): Boolean
}

/**
 * Opens the partial download for a session on this device, or null where the platform has no durable
 * store (iOS: the transfer then stays in memory, without resume). Without a real [deviceSerial] the
 * partial starts empty, so bytes from two devices can never be joined.
 */
expect fun openPartialTransfer(sessionId: Long, deviceSerial: String?): PartialTransfer?

/** Removes partial downloads last touched at least [STALE_PARTIAL_TRANSFER_MS] ago; returns the file count. */
expect fun sweepStalePartialTransfers(nowEpochMs: Long): Int

/** Removes every partial download and the salt of their file names. Throws if anything remains. */
expect fun deleteAllPartialTransfers()
