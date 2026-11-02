package dev.velum.ble.fake

/**
 * One-shot transfer faults for [FakePlaudPeripheral], keyed by the byte offset of the chunk they hit.
 * Each fault fires once and is then consumed, so a retry of the same range succeeds.
 */
class TransferFaults(
    /** Stop streaming at the first chunk at or past each offset (no end marker), like a lost link. */
    disconnectAt: Set<Long> = emptySet(),
    /** Flip a plaintext byte of the chunk at this offset; it still authenticates (only a CRC can tell). */
    corruptAt: Set<Long> = emptySet(),
    /** Damage the sealed packet so it fails authentication. */
    garbleAt: Set<Long> = emptySet(),
    /** Never send the chunk (a gap in the offsets). */
    dropAt: Set<Long> = emptySet(),
    /** Send the chunk twice (an overlap in the offsets). */
    duplicateAt: Set<Long> = emptySet(),
) {
    private val disconnect = disconnectAt.sorted().toMutableList()
    internal val corrupt = corruptAt.toMutableSet()
    internal val garble = garbleAt.toMutableSet()
    internal val drop = dropAt.toMutableSet()
    internal val duplicate = duplicateAt.toMutableSet()

    internal fun takeDisconnect(chunkOffset: Long): Boolean {
        val hit = disconnect.firstOrNull { it <= chunkOffset } ?: return false
        disconnect.remove(hit)
        return true
    }
}
