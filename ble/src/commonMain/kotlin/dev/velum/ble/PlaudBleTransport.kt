package dev.velum.ble

import kotlinx.coroutines.flow.Flow

/**
 * Thin Plaud-facing wrapper over a generic [BleTransport].
 *
 * Preserves the historical `write(payload)` / `notifications()` shape used by
 * [dev.velum.ble.client.PlaudDeviceClient] while routing I/O through the Plaud
 * [GattProfiles.Plaud] characteristic names.
 *
 * Plaintext writes always hit the public transport guard. Encrypted-session
 * writes use [writeSealedAfterPlaintextGuard] (public entry point for #93),
 * which checks plaintext then calls the internal [BleSealedWriteSink].
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
     * Transport-layer plaintext guard, then write [sealed] without re-parsing.
     *
     * Public entry point (Velum Dev #93). Forbidden opcodes are rejected on
     * [plaintext]; [sealed] ciphertext is written only via the internal
     * [BleSealedWriteSink] so callers outside `:ble` cannot skip the guard.
     */
    suspend fun writeSealedAfterPlaintextGuard(
        plaintext: ByteArray,
        sealed: ByteArray,
        withoutResponse: Boolean = false,
    ) {
        PlaudWriteGuard.requireAllowedPlaintext(plaintext)
        val sink = transport as? BleSealedWriteSink
            ?: error("BLE transport does not support sealed session writes")
        sink.writeSealedAlreadyGuarded(
            characteristic = GattProfiles.PLAUD_WRITE,
            payload = sealed,
            withoutResponse = withoutResponse,
        )
    }

    fun notifications(): Flow<ByteArray> =
        transport.notifications(GattProfiles.PLAUD_NOTIFY)
}
