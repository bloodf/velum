package dev.velum.ble.client

/**
 * Durable partial session file that can be resumed across process death.
 * Extends [SessionByteSink] with flush/sync and reopen semantics.
 */
interface SessionPartialFile : SessionByteSink {
    fun size(): Long

    fun absolutePath(): String

    /** Flush OS buffers; prefer fsync where available so verified offsets survive crashes. */
    fun sync()

    /**
     * Read [length] bytes at [offset] without loading the whole file.
     * Used to verify overlapping/duplicate chunks.
     */
    fun readAt(offset: Long, length: Int): ByteArray

    /** Discard bytes past [size]; used to drop an unverified crash tail. */
    fun truncateTo(size: Long)

    fun close()

    fun delete()
}

/**
 * Creates or opens durable partial files for [SessionDownloadEngine].
 */
interface SessionPartialFileFactory {
    /** New unique path for [sessionId] (file may not exist yet). */
    fun allocatePath(sessionId: Long): String

    /** Open existing file for append, or create empty. [initialSize] must match file length. */
    fun open(path: String): SessionPartialFile
}

/**
 * JVM/Android file-backed factory. Uses java.io so common tests on JVM work;
 * Android main also links this via the jvm/android source set copies.
 */
expect fun createSessionPartialFileFactory(directoryPath: String): SessionPartialFileFactory
