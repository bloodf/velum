package dev.velum.ble

/**
 * Internal `:ble`-only path for writing payloads whose **plaintext** was already
 * checked by [PlaudWriteGuard.requireAllowedPlaintext] (typically sealed
 * ciphertext after unlock).
 *
 * Not part of the public [BleTransport] surface — other modules cannot opt out
 * of the transport opcode guard. Only [PlaudBleTransport.writeSealedAfterPlaintextGuard]
 * should invoke this after the plaintext check succeeds.
 */
internal interface BleSealedWriteSink {
    suspend fun writeSealedAlreadyGuarded(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean = false,
    )
}
