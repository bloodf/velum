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
 *
 * A disconnect on the underlying [AbstractBleTransport] bumps the link
 * generation; this session then rejects further writes until a new session is
 * opened after reconnect.
 */
class PlaudEncryptedSession internal constructor(
    private val transport: BleTransport,
    private val cipher: SessionCipher,
    private val secret: Protocol20SessionSecret,
    initialSendSeq: Long = 2,
) {
    init {
        require(initialSendSeq >= 2) {
            "initialSendSeq must be >= 2 (got $initialSendSeq)"
        }
    }

    private val mutex = Mutex()
    private var sendSeq: Long = initialSendSeq
    private val linkGenerationAtOpen: Long =
        (transport as? AbstractBleTransport)?.currentLinkGeneration
            ?: error("BLE transport does not support sealed session writes")

    internal val sessionSecret: Protocol20SessionSecret get() = secret

    /** Sequence number that will be used for the next [writeEncrypted] (tests). */
    internal val nextSendSequence: Long get() = sendSeq

    /**
     * Guard [plaintext] for forbidden opcodes/settings, seal with the session
     * cipher inside `:ble`, then write via the transport's
     * `internal` [AbstractBleTransport.writeSealedAlreadyGuarded].
     *
     * Takes plaintext only — there is no public overload that accepts sealed bytes.
     */
    suspend fun writeEncrypted(
        plaintext: ByteArray,
        withoutResponse: Boolean = false,
    ) {
        mutex.withLock {
            val host = transport as? AbstractBleTransport
                ?: error("BLE transport does not support sealed session writes")
            check(host.currentLinkGeneration == linkGenerationAtOpen) {
                "Encrypted session invalidated by disconnect — open a new session after reconnect"
            }
            PlaudWriteGuard.requireAllowedPlaintext(plaintext)
            val seq = sendSeq
            val sealed = cipher.sealPacket(secret, seq, plaintext)
            sendSeq = seq + 1
            host.writeSealedAlreadyGuarded(
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
