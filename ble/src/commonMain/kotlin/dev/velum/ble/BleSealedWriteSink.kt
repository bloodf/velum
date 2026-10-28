package dev.velum.ble

/**
 * Module-internal sink for already-sealed Plaud link frames.
 *
 * [writeSealedAlreadyGuarded] lives **only** here — not on the public
 * [BleTransport] surface. [PlaudEncryptedSession.writeEncrypted] obtains a sink
 * via [AbstractBleTransport.sealedWriteSink] after guarding plaintext and sealing
 * inside `:ble`.
 */
internal interface BleSealedWriteSink {
    /**
     * Write already-sealed bytes without opcode parsing.
     *
     * Callers must have already run [PlaudWriteGuard.requireAllowedPlaintext].
     */
    suspend fun writeSealedAlreadyGuarded(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean = false,
    )
}

/**
 * Public transport base that exposes a module-internal [BleSealedWriteSink].
 *
 * Subclasses must not put [BleSealedWriteSink.writeSealedAlreadyGuarded] on their
 * own public API; they return a sink via [sealedWriteSink] (`internal override`).
 */
abstract class AbstractBleTransport : BleTransport {
    /** Module-internal sealed-write adapter. */
    internal abstract fun sealedWriteSink(): BleSealedWriteSink
}
