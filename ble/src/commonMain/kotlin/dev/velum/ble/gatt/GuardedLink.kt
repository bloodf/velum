package dev.velum.ble.gatt

/**
 * The single choke point for every GATT write. The guard for the target characteristic runs on
 * the PLAINTEXT, then [seal] (if any) produces the bytes that go out; sealed bytes are never parsed.
 *
 * The Plaud write characteristic (2BB1) always gets [PlaudWriteGuard], whatever guard the profile names for it: a
 * profile is data supplied by the caller and must not be able to weaken the opcode guard. Sealing is `internal`:
 * only code in `:ble` (which verifies the sealed bytes open to the checked plaintext) can supply a [seal].
 */
class GuardedLink(
    private val transport: GattLink,
    // No default: a link for another family must name its profile, never silently get the Plaud guard.
    private val profile: GattProfile,
) {
    suspend fun write(char: GattChar, plaintext: ByteArray, withoutResponse: Boolean) =
        writeGuarded(char, plaintext, withoutResponse, seal = null)

    internal suspend fun write(
        char: GattChar,
        plaintext: ByteArray,
        withoutResponse: Boolean,
        seal: (ByteArray) -> ByteArray,
    ) = writeGuarded(char, plaintext, withoutResponse, seal)

    private suspend fun writeGuarded(
        char: GattChar,
        plaintext: ByteArray,
        withoutResponse: Boolean,
        seal: ((ByteArray) -> ByteArray)?,
    ) {
        val declared = requireNotNull(profile.writes[char]) {
            "No write guard declared for ${char.characteristic} in profile ${profile.id}"
        }
        val guard = if (char == GattProfiles.PLAUD_WRITE) PlaudWriteGuard else declared
        // Copy first, then check and use the same copy: the caller cannot mutate the bytes between
        // the guard and the write.
        val p = plaintext.copyOf()
        guard.check(p)
        val write = if (seal == null) {
            PlainWrite(char, p, withoutResponse)
        } else {
            SealedWrite(char, seal(p).copyOf(), withoutResponse)
        }
        transport.writeRaw(write)
    }
}

/**
 * Transport-level re-check, for transports that can see what they are about to send: a plain write to 2BB1 runs the
 * Plaud guard again, so a write that reached the transport by any route is still held to it. (Sealed writes cannot be
 * parsed; their plaintext was checked, and verified against the sealed bytes, before sealing.)
 */
internal fun requireTransportAllowed(write: GattWrite) =
    requireTransportAllowed(write.char, write.bytes, sealed = write is SealedWrite)

internal fun requireTransportAllowed(char: GattChar, bytes: ByteArray, sealed: Boolean) {
    if (!sealed && char == GattProfiles.PLAUD_WRITE) PlaudWriteGuard.check(bytes)
}
