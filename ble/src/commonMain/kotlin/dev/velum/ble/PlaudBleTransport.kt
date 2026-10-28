package dev.velum.ble

import kotlinx.coroutines.flow.Flow

/**
 * Thin Plaud-facing wrapper over a generic [BleTransport].
 *
 * Preserves the historical `write(payload)` / `notifications()` shape used by
 * [dev.velum.ble.client.PlaudDeviceClient] while routing I/O through the Plaud
 * [GattProfiles.Plaud] characteristic names.
 *
 * Plaintext writes are opcode-guarded by the transport ([BleWriteSafety.GuardPlaintext]).
 * Encrypted-session writes must call [writeSealedAfterPlaintextGuard] so the
 * transport-layer check runs on plaintext only, never on ciphertext.
 */
class PlaudBleTransport(
    private val transport: BleTransport,
) {
    val underlying: BleTransport get() = transport

    suspend fun connect(deviceId: String) {
        transport.connect(deviceId, GattProfiles.Plaud)
    }

    /** Plaintext write (pre-unlock / unencrypted). Transport guards opcodes. */
    suspend fun write(payload: ByteArray, withoutResponse: Boolean = false) {
        transport.write(
            characteristic = GattProfiles.PLAUD_WRITE,
            payload = payload,
            withoutResponse = withoutResponse,
            safety = BleWriteSafety.GuardPlaintext,
        )
    }

    /**
     * Transport-layer plaintext guard, then write [sealed] without re-parsing.
     *
     * Used after session unlock: forbidden opcodes are rejected here on
     * [plaintext]; [sealed] ciphertext is marked [BleWriteSafety.AlreadyChecked].
     */
    suspend fun writeSealedAfterPlaintextGuard(
        plaintext: ByteArray,
        sealed: ByteArray,
        withoutResponse: Boolean = false,
    ) {
        PlaudWriteGuard.requireAllowedPlaintext(plaintext)
        transport.write(
            characteristic = GattProfiles.PLAUD_WRITE,
            payload = sealed,
            withoutResponse = withoutResponse,
            safety = BleWriteSafety.AlreadyChecked,
        )
    }

    fun notifications(): Flow<ByteArray> =
        transport.notifications(GattProfiles.PLAUD_NOTIFY)
}
