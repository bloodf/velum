package dev.velum.ble

/**
 * Internal `:ble`-only path for writing payloads that were sealed inside this
 * module after [PlaudWriteGuard.requireAllowedPlaintext] (see
 * [PlaudEncryptedSession.writeEncrypted]).
 *
 * Not part of the public [BleTransport] surface — other modules cannot opt out
 * of the transport opcode guard or supply pre-sealed bytes through a public API.
 */
internal interface BleSealedWriteSink {
    suspend fun writeSealedAlreadyGuarded(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean = false,
    )
}
