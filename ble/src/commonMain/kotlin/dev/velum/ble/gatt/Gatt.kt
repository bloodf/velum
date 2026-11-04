package dev.velum.ble.gatt

import dev.velum.protocol.PlaudUuids
import dev.velum.recorder.omi.OmiUuids
import dev.velum.recorder.pocket.PocketUuids

/** A GATT characteristic address (128-bit UUID strings, as in [PlaudUuids]). */
data class GattChar(val service: String, val characteristic: String) {
    /** UUIDs are case-insensitive on the wire, so the Plaud write characteristic is recognised in any case. */
    val isPlaudWrite: Boolean
        get() = service.equals(GattProfiles.PLAUD_WRITE.service, ignoreCase = true) &&
            characteristic.equals(GattProfiles.PLAUD_WRITE.characteristic, ignoreCase = true)
}

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
    /** Characteristics read once (not subscribed), e.g. a codec descriptor. */
    val reads: List<GattChar> = emptyList(),
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
    val OMI_AUDIO_CODEC = GattChar(OmiUuids.SERVICE, OmiUuids.AUDIO_CODEC)
    val OMI_BATTERY_LEVEL = GattChar(OmiUuids.BATTERY_SERVICE, OmiUuids.BATTERY_LEVEL)

    /**
     * Omi / Friend (BasedHardware/omi, MIT): audio notifications and battery level only. It declares NO writable
     * characteristic, so [GuardedLink] rejects every write made under this profile, including to Plaud's 2BB1.
     */
    val Omi = GattProfile(
        id = "omi",
        notify = listOf(OMI_AUDIO_DATA, OMI_BATTERY_LEVEL),
        writes = emptyMap(),
        reads = listOf(OMI_AUDIO_CODEC),
    )

    val POCKET_AUDIO_DATA = GattChar(PocketUuids.AUDIO_SERVICE, PocketUuids.AUDIO_DATA)
    val POCKET_REPLIES = GattChar(PocketUuids.AUDIO_SERVICE, PocketUuids.AUDIO_META)
    val POCKET_BATTERY_LEVEL = GattChar(PocketUuids.BATTERY_SERVICE, PocketUuids.BATTERY_LEVEL)

    /**
     * Pocket / OpenPocket (pocket-libre PROTOCOL.md, MIT): MP3 audio, `MCU&` replies and battery notifications only.
     * It declares NO writable characteristic, so the `APP&` command channel (001120A2/A3) is unreachable, including
     * `APP&SK&`, which pairs the device. Commands need their own allow-list guard first (not designed yet). Whether
     * notifications alone are enough to receive audio from a real device is unverified; the published protocol has the
     * app start streaming with a command.
     */
    val Pocket = GattProfile(
        id = "pocket",
        notify = listOf(POCKET_AUDIO_DATA, POCKET_REPLIES, POCKET_BATTERY_LEVEL),
        writes = emptyMap(),
    )

    val LIMITLESS_RX = GattChar(OmiUuids.LIMITLESS_SERVICE, OmiUuids.LIMITLESS_RX)
    val LIMITLESS_TX = GattChar(OmiUuids.LIMITLESS_SERVICE, OmiUuids.LIMITLESS_TX)

    /**
     * Limitless pendant (UUIDs: `OmiUuids.kt` LIMITLESS_*; packet shape: `LimitlessProtocol.kt`, "every RX
     * notification is a BLE wrapper protobuf"): the app receives protobuf BLE wrappers as notifications on RX.
     * It declares NO writable characteristic, so [GuardedLink] rejects every write under this profile, including to
     * TX. TX is taken to be the command channel from its name and the #18 notes (`docs/DEVICES_ROADMAP.md`: the
     * public clients send commands such as start/stop recording, clear storage, factory reset and set key); there is
     * no explicit in-repo source for the mapping. No driver is wired to this profile and whether to build one is
     * the owner's decision (#18); the profile only fixes the addresses and the "no writes" rule.
     */
    val Limitless = GattProfile(
        id = "limitless",
        notify = listOf(LIMITLESS_RX),
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
    bytes: ByteArray,
    override val withoutResponse: Boolean,
) : GattWrite() {
    private val data = bytes.copyOf()

    /** A copy each time: a transport wrapper editing it cannot change what the next transport sees. */
    override val bytes: ByteArray get() = data.copyOf()
}

/** Bytes produced by a session cipher from a plaintext the guard already accepted. Never parsed. */
class SealedWrite internal constructor(
    override val char: GattChar,
    bytes: ByteArray,
    override val withoutResponse: Boolean,
) : GattWrite() {
    private val data = bytes.copyOf()

    /** A copy each time: a transport wrapper editing it cannot change what the next transport sees. */
    override val bytes: ByteArray get() = data.copyOf()
}
