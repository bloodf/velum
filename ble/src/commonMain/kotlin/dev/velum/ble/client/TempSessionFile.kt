package dev.velum.ble.client

expect fun createTempSessionFile(): TempSessionFile?

interface TempSessionFile : SessionByteSink {
    fun size(): Long
    fun readAll(): ByteArray
    fun absolutePath(): String
    fun delete()
}
