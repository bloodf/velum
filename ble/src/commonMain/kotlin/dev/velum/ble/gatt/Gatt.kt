package dev.velum.ble.gatt

import dev.velum.protocol.PlaudUuids

/** A GATT characteristic address (128-bit UUID strings, as in [PlaudUuids]). */
data class GattChar(val service: String, val characteristic: String)

/** Validates the PLAINTEXT of a write before it can reach the radio. Throws to reject. */
fun interface WriteGuard {
    fun check(plaintext: ByteArray)
}

/**
 * Characteristics a driver uses. Every writable characteristic MUST declare a [WriteGuard];
 * [GuardedLink] rejects writes to anything not listed here.
 */
data class GattProfile(
    val id: String,
    val notify: List<GattChar>,
    val writes: Map<GattChar, WriteGuard>,
)

object GattProfiles {
    val PLAUD_NOTIFY = GattChar(PlaudUuids.SERVICE, PlaudUuids.NOTIFY)
    val PLAUD_WRITE = GattChar(PlaudUuids.SERVICE, PlaudUuids.WRITE)

    /** Guard is keyed on the 2BB1 write characteristic UUID, not on the profile id. */
    val Plaud = GattProfile(
        id = "plaud",
        notify = listOf(PLAUD_NOTIFY),
        writes = mapOf(PLAUD_WRITE to PlaudWriteGuard),
    )
}

/**
 * A write that already passed its guard. Both constructors are `internal` to `:ble`, so a
 * driver in another module cannot build one, and [dev.velum.ble.BleTransport.writeRaw] is
 * `internal` too: [GuardedLink] is the only path to a characteristic.
 */
sealed class GattWrite {
    abstract val char: GattChar
    abstract val bytes: ByteArray
    abstract val withoutResponse: Boolean
}

/** Bytes sent as given (no session cipher active). */
class PlainWrite internal constructor(
    override val char: GattChar,
    override val bytes: ByteArray,
    override val withoutResponse: Boolean,
) : GattWrite()

/** Bytes produced by a session cipher from a plaintext the guard already accepted. Never parsed. */
class SealedWrite internal constructor(
    override val char: GattChar,
    override val bytes: ByteArray,
    override val withoutResponse: Boolean,
) : GattWrite()
