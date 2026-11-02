package dev.velum.ble.client

/**
 * A session transfer delivered bytes that cannot be trusted: a packet that fails authentication or
 * its sequence check, a chunk offset gap or overlap, a short transfer, or an enforced CRC mismatch.
 * The message carries offsets only, never identity values. Retrying (resuming) is safe.
 */
class TransferIntegrityException(message: String, cause: Throwable? = null) : Exception(message, cause)
