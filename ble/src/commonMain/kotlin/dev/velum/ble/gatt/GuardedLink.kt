package dev.velum.ble.gatt

/**
 * The single choke point for every GATT write. The guard for the target characteristic runs on
 * the PLAINTEXT, then [seal] (if any) produces the bytes that go out; sealed bytes are never parsed.
 */
class GuardedLink(
    private val transport: GattLink,
    private val profile: GattProfile = GattProfiles.Plaud,
) {
    suspend fun write(
        char: GattChar,
        plaintext: ByteArray,
        withoutResponse: Boolean,
        seal: ((ByteArray) -> ByteArray)? = null,
    ) {
        val guard = requireNotNull(profile.writes[char]) {
            "No write guard declared for ${char.characteristic} in profile ${profile.id}"
        }
        guard.check(plaintext)
        val write = if (seal == null) {
            PlainWrite(char, plaintext.copyOf(), withoutResponse)
        } else {
            SealedWrite(char, seal(plaintext.copyOf()).copyOf(), withoutResponse)
        }
        transport.writeRaw(write)
    }
}
