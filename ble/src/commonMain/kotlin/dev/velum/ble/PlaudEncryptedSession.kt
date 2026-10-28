package dev.velum.ble

import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Encrypted Plaud link session owned by `:ble`.
 *
 * Holds the [SessionCipher] and [Protocol20SessionSecret] from unlock so sealing
 * happens **inside** this module. The public API accepts **plaintext only** —
 * sealed ciphertext never crosses a public method boundary.
 *
 * Public entry point for Velum Dev #93: [writeEncrypted].
 */
class PlaudEncryptedSession internal constructor(
    private val transport: BleTransport,
    private val cipher: SessionCipher,
    private val secret: Protocol20SessionSecret,
    initialSendSeq: Long = 2,
) {
    private val mutex = Mutex()
    private var sendSeq: Long = initialSendSeq

    val sessionSecret: Protocol20SessionSecret get() = secret

    /** Sequence number that will be used for the next [writeEncrypted] (tests). */
    val nextSendSequence: Long get() = sendSeq

    /**
     * Guard [plaintext] for forbidden opcodes/settings, seal with the session
     * cipher inside `:ble`, then write via [BleSealedWriteSink.writeSealedAlreadyGuarded].
     *
     * Takes plaintext only — there is no public overload that accepts sealed bytes.
     */
    suspend fun writeEncrypted(
        plaintext: ByteArray,
        withoutResponse: Boolean = false,
    ) {
        mutex.withLock {
            PlaudWriteGuard.requireAllowedPlaintext(plaintext)
            val seq = sendSeq
            val sealed = cipher.sealPacket(secret, seq, plaintext)
            sendSeq = seq + 1
            val host = transport as? AbstractBleTransport
                ?: error("BLE transport does not support sealed session writes")
            host.sealedWriteSink().writeSealedAlreadyGuarded(
                characteristic = GattProfiles.PLAUD_WRITE,
                payload = sealed,
                withoutResponse = withoutResponse,
            )
        }
    }

    /** Open an inbound sealed frame with this session's fixed key/nonce/aad. */
    fun openPacket(sequence: Long, ciphertextAndTag: ByteArray): Pair<Long, ByteArray>? =
        cipher.openPacket(secret, sequence, ciphertextAndTag)
}
