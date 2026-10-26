package dev.velum.ble.client

internal expect fun createTempSessionFile(): TempSessionFile?

internal interface TempSessionFile : SessionByteSink {
    fun size(): Long
    fun readAll(): ByteArray
    fun absolutePath(): String
    fun delete()
}
