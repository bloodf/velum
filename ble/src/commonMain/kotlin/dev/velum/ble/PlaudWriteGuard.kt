package dev.velum.ble

import dev.velum.protocol.PlaudOutboundGuard
import dev.velum.protocol.PlaudUuids

/**
 * Transport-layer Plaud safety keyed on the Plaud **write characteristic UUID**
 * (`2BB1`) and service (`1910`), not on profile id / characteristic name.
 *
 * Public [BleTransport.write] always runs this for Plaud write targets.
 * Skipping the opcode parse is only possible via the internal
 * [BleSealedWriteSink] after [PlaudEncryptedSession.writeEncrypted] has
 * already passed [requireAllowedPlaintext] and sealed inside `:ble`.
 */
object PlaudWriteGuard {
    fun isPlaudWriteTarget(serviceUuid: String, characteristicUuid: String): Boolean =
        characteristicUuid.equals(PlaudUuids.WRITE, ignoreCase = true) &&
            serviceUuid.equals(PlaudUuids.SERVICE, ignoreCase = true)

    fun requireAllowedIfPlaudWrite(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray,
    ) {
        if (!isPlaudWriteTarget(serviceUuid, characteristicUuid)) return
        PlaudOutboundGuard.requireAllowed(payload)
    }

    /** Explicit plaintext check used by the Plaud session layer before sealing. */
    fun requireAllowedPlaintext(payload: ByteArray) {
        PlaudOutboundGuard.requireAllowed(payload)
    }
}
