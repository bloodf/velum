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
    /**
     * The device sends the packet but the app never sees it (e.g. a notification buffer overflowing
     * while the app stalls): the sequence number is used up, unlike [dropAt].
     */
    lostNotificationAt: Set<Long> = emptySet(),
    /** Deliver the same sealed packet twice (seq already seen). */
    replayAt: Set<Long> = emptySet(),
    /** Instead of the chunk, send one starting 50 bytes before it (a partial overlap). */
    overlapAt: Set<Long> = emptySet(),
    /** Before the chunk, send a chunk carrying another session's id (a stray from an interrupted transfer). */
    strayAt: Set<Long> = emptySet(),
    /** Before the chunk, send an end marker carrying another session's id. */
    strayEndAt: Set<Long> = emptySet(),
    /** From this chunk on, keep sending the previous chunk forever (a device stuck repeating). */
    internal val endlessRepeatAt: Long? = null,
    /** Before the chunk, send the previous chunk this many times. */
    repeatBurstAt: Map<Long, Int> = emptyMap(),
    /** Stream from 0 whatever start offset is asked for. */
    internal val ignoreStartOffset: Boolean = false,
    /** Number chunks from the start offset asked for, not from the start of the file. */
    internal val relativeOffsets: Boolean = false,
    /** Never answer a SYNC_FILE_START with a non-zero offset. */
    internal val silentOnNonZeroStart: Boolean = false,
    /** Answer a SYNC_FILE_START with a non-zero offset with a reply too short to parse. */
    internal val malformedReplyOnNonZeroStart: Boolean = false,
    /** Refuse any SYNC_FILE_START with a non-zero offset. */
    internal val refuseNonZeroStart: Boolean = false,
    /** Lose the link at this offset once on every pass that starts from 0. */
    internal val disconnectEveryPassAt: Long? = null,
    /** Deliver the previous session-list reply's sealed packet again before each new one. */
    internal val replayStaleListReply: Boolean = false,
) {
    private val disconnect = disconnectAt.sorted().toMutableList()
    internal val corrupt = corruptAt.toMutableSet()
    internal val garble = garbleAt.toMutableSet()
    internal val drop = dropAt.toMutableSet()
    internal val duplicate = duplicateAt.toMutableSet()
    internal val lostNotification = lostNotificationAt.toMutableSet()
    internal val replay = replayAt.toMutableSet()
    internal val overlap = overlapAt.toMutableSet()
    internal val stray = strayAt.toMutableSet()
    internal val strayEnd = strayEndAt.toMutableSet()
    internal val repeatBurst = repeatBurstAt.toMutableMap()
    private var passArmed = false

    internal fun startPass(fromOffset: Int) {
        if (fromOffset == 0) passArmed = true
    }

    internal fun takePassDisconnect(chunkOffset: Long): Boolean {
        if (!passArmed || chunkOffset != disconnectEveryPassAt) return false
        passArmed = false
        return true
    }

    internal fun takeDisconnect(chunkOffset: Long): Boolean {
        val hit = disconnect.firstOrNull { it <= chunkOffset } ?: return false
        disconnect.remove(hit)
        return true
    }
}
