package dev.velum.ble.gatt

import dev.velum.protocol.PlaudUuids
import dev.velum.recorder.omi.OmiUuids

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

    val OMI_AUDIO_DATA = GattChar(OmiUuids.SERVICE, OmiUuids.AUDIO_DATA)
    val OMI_BATTERY_LEVEL = GattChar(OmiUuids.BATTERY_SERVICE, OmiUuids.BATTERY_LEVEL)

    /**
     * Omi / Friend (BasedHardware/omi, MIT): audio notifications and battery level only. It declares NO writable
     * characteristic, so [GuardedLink] rejects every write made under this profile, including to Plaud's 2BB1.
     */
    val Omi = GattProfile(
        id = "omi",
        notify = listOf(OMI_AUDIO_DATA, OMI_BATTERY_LEVEL),
        writes = emptyMap(),
    )
}

/**
 * Connection-level raw write, implemented by transports and test fakes. Public so a separate
 * test module can implement it, but only [GattWrite] values can be passed and those can only be
 * built inside `:ble` (internal constructors) by [GuardedLink] after the guard ran. Drivers and
 * app code hold [dev.velum.ble.BleTransport] / [GuardedLink] and never call `writeRaw`
 * (enforced by `GattArchitectureTest`).
 */
interface GattLink {
    /** Do not call: use [GuardedLink], which runs the write guard first. Implement only in transports/fakes. */
    suspend fun writeRaw(write: GattWrite)
}

/**
 * A write that already passed its guard. Both constructors are `internal` to `:ble`, so only
 * [GuardedLink] can produce one: it is the only path to a characteristic.
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
