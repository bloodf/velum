package dev.velum.ble

import dev.velum.protocol.PlaudOutboundGuard

/**
 * Transport-layer Plaud safety: when writing the Plaud write characteristic,
 * reject forbidden opcodes / settings before any bytes reach the radio.
 */
object PlaudWriteGuard {
    fun maybeRequireAllowed(
        profile: GattProfile?,
        characteristicName: String,
        payload: ByteArray,
    ) {
        if (profile == null) return
        if (profile.id != GattProfiles.Plaud.id) return
        if (characteristicName != GattProfiles.PLAUD_WRITE) return
        PlaudOutboundGuard.requireAllowed(payload)
    }
}
