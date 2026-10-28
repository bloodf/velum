package dev.velum.ble.client

/** Sync-stop CRC did not match the bytes received. Partial data remains recoverable. */
class SessionCrcMismatchException(
    val sessionId: Long,
    val expectedCrc: Int,
    val actualCrc: Int,
    val verifiedOffset: Long,
    val partialPath: String?,
    message: String =
        "Session $sessionId CRC mismatch: device=0x${expectedCrc.toString(16)} " +
            "local=0x${actualCrc.toString(16)} at offset=$verifiedOffset" +
            (partialPath?.let { " path=$it" } ?: ""),
) : Exception(message)

/** Recording-data chunk created a gap past the last verified offset. */
class SessionDownloadGapException(
    val sessionId: Long,
    val expectedOffset: Long,
    val chunkOffset: Long,
    message: String =
        "Session $sessionId gap: expected offset=$expectedOffset got chunk offset=$chunkOffset",
) : Exception(message)

/** Transfer ended before [expectedSizeBytes] were verified. */
class SessionDownloadTruncatedException(
    val sessionId: Long,
    val expectedSizeBytes: Long,
    val verifiedOffset: Long,
    message: String =
        "Session $sessionId truncated: got $verifiedOffset of $expectedSizeBytes bytes",
) : Exception(message)
