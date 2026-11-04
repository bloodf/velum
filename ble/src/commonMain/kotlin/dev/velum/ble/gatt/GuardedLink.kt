package dev.velum.ble.gatt

/**
 * The single choke point for every GATT write. The guard for the target characteristic runs on
 * the PLAINTEXT, then [seal] (if any) produces the bytes that go out; sealed bytes are never parsed.
 */
class GuardedLink(
    private val transport: GattLink,
    // No default: a link for another family must name its profile, never silently get the Plaud guard.
    private val profile: GattProfile,
) {
    suspend fun write(
        char: GattChar,
        plaintext: ByteArray,
        withoutResponse: Boolean,
        // The link generation the caller wrote for, carried on the write so the transport can refuse it if the link
        // changed after the caller's own check. Before [seal] so a trailing lambda still binds to it.
        linkGeneration: Long? = null,
        seal: ((ByteArray) -> ByteArray)? = null,
    ) {
        val guard = requireNotNull(profile.writes[char]) {
            "No write guard declared for ${char.characteristic} in profile ${profile.id}"
        }
        // Copy first, then check and use the same copy: the caller cannot mutate the bytes between
        // the guard and the write.
        val p = plaintext.copyOf()
        guard.check(p)
        val write = if (seal == null) {
            PlainWrite(char, p, withoutResponse, linkGeneration)
        } else {
            SealedWrite(char, seal(p).copyOf(), withoutResponse, linkGeneration)
        }
        transport.writeRaw(write)
    }
}
