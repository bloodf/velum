package dev.velum.app.platform

actual fun openPartialTransfer(sessionId: Long, deviceSerial: String?): PartialTransfer? = null

actual fun sweepStalePartialTransfers(nowEpochMs: Long): Int = 0
