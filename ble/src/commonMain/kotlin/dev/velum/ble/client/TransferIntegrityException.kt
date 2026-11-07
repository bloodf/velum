package dev.velum.ble.client

/**
 * A session transfer delivered bytes that cannot be trusted: a chunk offset gap or partial overlap, an
 * overrun, a short transfer, or an enforced CRC mismatch. Hard failures even on real devices, unlike a
 * CRC mismatch there: decryption is positional, so a gapped file is useless. The message carries offsets
 * only, never identity values. Retrying (resuming) is safe.
 */
class TransferIntegrityException(
    message: String,
    cause: Throwable? = null,
    /** A lost chunk or short transfer: worth one more try at the same offset before the partial is discarded. */
    val transient: Boolean = false,
) : Exception(message, cause)
