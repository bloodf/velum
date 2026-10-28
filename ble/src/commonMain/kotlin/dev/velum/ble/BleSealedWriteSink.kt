package dev.velum.ble

/**
 * Base for platform BLE transports that support encrypted-session writes.
 *
 * [writeSealedAlreadyGuarded] is **internal** so sealed bytes never appear on the
 * public JVM/API surface. Only [PlaudEncryptedSession.writeEncrypted] (after a
 * plaintext [PlaudWriteGuard] check) may call it from inside `:ble`.
 *
 * Concrete transports must use `internal override` for that method.
 */
abstract class AbstractBleTransport : BleTransport {
    /**
     * Write already-sealed bytes without opcode parsing.
     *
     * Internal only — not part of the public [BleTransport] contract.
     */
    internal abstract suspend fun writeSealedAlreadyGuarded(
        characteristic: String,
        payload: ByteArray,
        withoutResponse: Boolean = false,
    )
}
