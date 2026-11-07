package dev.velum.app.platform

actual fun openPartialTransfer(sessionId: Long, deviceSerial: String?): PartialTransfer? = null

actual fun deletePartialTransfer(sessionId: Long, deviceSerial: String?): Boolean = true

actual fun sweepStalePartialTransfers(nowEpochMs: Long): Int = 0

actual fun deleteAllPartialTransfers() = Unit
