package dev.velum.ble.client

/**
 * Disk-backed sink for [PlaudDeviceClient.downloadSessionTo].
 * Prefer this over [PlaudDeviceClient.downloadSession] so callers can decrypt
 * and persist without an extra full-heap copy of the encrypted payload.
 */
expect fun createTempSessionFile(): TempSessionFile?

interface TempSessionFile : SessionByteSink {
    fun size(): Long
    fun readAll(): ByteArray
    fun absolutePath(): String
    fun delete()
}
