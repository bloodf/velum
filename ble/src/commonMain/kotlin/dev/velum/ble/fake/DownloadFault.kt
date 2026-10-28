package dev.velum.ble.fake

/**
 * Simulated transfer faults for resume / CRC regression tests.
 * Synthetic only — never talks to real hardware.
 */
sealed class DownloadFault {
    data object None : DownloadFault()

    /** Abruptly stop emitting after reaching [offset] (simulates disconnect). */
    data class DropAtOffset(val offset: Long) : DownloadFault()

    /** Flip bits in the chunk that covers [offset] (CRC must fail). */
    data class CorruptAtOffset(val offset: Long) : DownloadFault()

    /** Emit a final data chunk shorter than remaining bytes, then End. */
    data class TruncateFinalChunk(val sendOnlyBytes: Long) : DownloadFault()

    /** Re-send the chunk starting at [offset] a second time before continuing. */
    data class DuplicateChunkAt(val offset: Long) : DownloadFault()
}
