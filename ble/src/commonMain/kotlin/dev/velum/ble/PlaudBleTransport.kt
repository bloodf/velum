package dev.velum.ble

import dev.velum.ble.crypto.createSessionCipher
import dev.velum.protocol.Protocol20SessionSecret
import dev.velum.protocol.SessionCipher
import kotlinx.coroutines.flow.Flow

/**
 * Thin Plaud-facing wrapper over a generic [BleTransport].
 *
 * Preserves the historical `write(payload)` / `notifications()` shape used by
 * [dev.velum.ble.client.PlaudDeviceClient] while routing I/O through the Plaud
 * [GattProfiles.Plaud] characteristic names.
 *
 * Pre-unlock plaintext uses [write] (always opcode-guarded). After unlock, use
 * [openEncryptedSession] and [PlaudEncryptedSession.writeEncrypted] — plaintext
 * only; sealing stays inside `:ble`.
 */
class PlaudBleTransport(
    private val transport: BleTransport,
) {
    val underlying: BleTransport get() = transport

    suspend fun connect(deviceId: String) {
        transport.connect(deviceId, GattProfiles.Plaud)
    }

    /** Plaintext write (pre-unlock / unencrypted). Transport always guards opcodes. */
    suspend fun write(payload: ByteArray, withoutResponse: Boolean = false) {
        transport.write(
            characteristic = GattProfiles.PLAUD_WRITE,
            payload = payload,
            withoutResponse = withoutResponse,
        )
    }

    /**
     * Start an encrypted link session after unlock.
     *
     * Public signature takes only the session [secret] — the [SessionCipher] is
     * built inside `:ble` via [createSessionCipher] so callers outside the module
     * cannot inject a cipher whose [SessionCipher.sealPacket] returns arbitrary
     * on-wire bytes.
     *
     * The returned [PlaudEncryptedSession] owns sealing; callers pass plaintext
     * to [PlaudEncryptedSession.writeEncrypted] only.
     */
    fun openEncryptedSession(
        secret: Protocol20SessionSecret,
        initialSendSeq: Long = 2,
    ): PlaudEncryptedSession {
        require(initialSendSeq >= 2) {
            "initialSendSeq must be >= 2 (got $initialSendSeq)"
        }
        return openEncryptedSession(
            cipher = createSessionCipher(),
            secret = secret,
            initialSendSeq = initialSendSeq,
        )
    }

    /**
     * Test / in-module overload that accepts an explicit [SessionCipher].
     *
     * **Internal** — outside `:ble` this must not compile (custom-cipher bypass).
     */
    internal fun openEncryptedSession(
        cipher: SessionCipher,
        secret: Protocol20SessionSecret,
        initialSendSeq: Long = 2,
    ): PlaudEncryptedSession {
        require(initialSendSeq >= 2) {
            "initialSendSeq must be >= 2 (got $initialSendSeq)"
        }
        return PlaudEncryptedSession(
            transport = transport,
            cipher = cipher,
            secret = secret,
            initialSendSeq = initialSendSeq,
        )
    }

    fun notifications(): Flow<ByteArray> =
        transport.notifications(GattProfiles.PLAUD_NOTIFY)
}
