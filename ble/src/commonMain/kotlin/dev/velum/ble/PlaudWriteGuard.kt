package dev.velum.ble

import dev.velum.protocol.PlaudOutboundGuard
import dev.velum.protocol.PlaudUuids

/**
 * How a BLE write payload should be treated for Plaud opcode safety.
 *
 * Ciphertext must never be parsed as a Plaud frame — after unlock the session
 * layer checks plaintext, seals, then writes with [AlreadyChecked].
 */
enum class BleWriteSafety {
    /** Assume Plaud plaintext framing when targeting the Plaud write char. */
    GuardPlaintext,

    /**
     * Payload was already opcode-checked as plaintext (typically sealed).
     * Transport must not interpret bytes as an opcode.
     */
    AlreadyChecked,
}

/**
 * Transport-layer Plaud safety keyed on the Plaud **write characteristic UUID**
 * (`2BB1`) and service (`1910`), not on profile id / characteristic name.
 *
 * Any profile that points a writable characteristic at those UUIDs is guarded
 * when [BleWriteSafety.GuardPlaintext] is used.
 */
object PlaudWriteGuard {
    fun isPlaudWriteTarget(serviceUuid: String, characteristicUuid: String): Boolean =
        characteristicUuid.equals(PlaudUuids.WRITE, ignoreCase = true) &&
            serviceUuid.equals(PlaudUuids.SERVICE, ignoreCase = true)

    fun maybeRequireAllowed(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray,
        safety: BleWriteSafety,
    ) {
        if (safety == BleWriteSafety.AlreadyChecked) return
        if (!isPlaudWriteTarget(serviceUuid, characteristicUuid)) return
        PlaudOutboundGuard.requireAllowed(payload)
    }

    /** Explicit plaintext check used by the Plaud session layer before sealing. */
    fun requireAllowedPlaintext(payload: ByteArray) {
        PlaudOutboundGuard.requireAllowed(payload)
    }
}
