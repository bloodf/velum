package dev.velum.ai.local

/**
 * Platform models directory (`…/models/`). Weights are never committed.
 */
expect class ModelStoreDir(absolutePath: String) {
    val absolutePath: String
    val name: String
    fun resolve(child: String): ModelStoreDir
    fun exists(): Boolean
    fun isDirectory(): Boolean
    fun createDirectories()
    fun writeBytes(bytes: ByteArray)
    /** Like [writeBytes], but the contents are on stable storage before this returns (a power cut keeps them). */
    fun writeBytesDurably(bytes: ByteArray)
    /** Best effort: persist this directory's entries (a rename into it). Platforms that cannot ignore it. */
    fun syncDirectory()
    fun appendBytes(bytes: ByteArray)
    fun readBytes(): ByteArray
    /** Random-access read of [length] bytes starting at [offset] (for spilled PCM windows). */
    fun readRange(offset: Long, length: Int): ByteArray
    /** Remove a single file. Never traverse directories, including a concurrently replaced path. */
    fun deleteFile(): Boolean
    fun deleteRecursively()
    fun listChildren(): List<ModelStoreDir>
    fun length(): Long
    /** Free bytes on the volume holding this path (nearest existing parent), or null when unknown. */
    fun freeSpaceBytes(): Long?
    /** Last modification time in epoch milliseconds, or 0 when the path does not exist. */
    fun lastModifiedMillis(): Long
    /**
     * Publish this path onto [dest], replacing it. JVM/Android: atomic for a file target on
     * the same filesystem (ATOMIC_MOVE; plain replace where unsupported); an existing
     * directory target is deleted first. iOS: rename(2), which replaces a file target atomically.
     */
    fun renameTo(dest: ModelStoreDir): Boolean
}

expect fun defaultModelStoreRoot(): ModelStoreDir

/** Depth-first file walk (files only). */
fun ModelStoreDir.walkFiles(): List<ModelStoreDir> {
    if (!exists()) return emptyList()
    if (!isDirectory()) return listOf(this)
    return listChildren().flatMap { child ->
        if (child.isDirectory()) child.walkFiles() else listOf(child)
    }
}

/** Depth-first directory walk including this node when it is a directory. */
fun ModelStoreDir.walkDirectories(): List<ModelStoreDir> {
    if (!exists() || !isDirectory()) return emptyList()
    return listOf(this) + listChildren().filter { it.isDirectory() }.flatMap { it.walkDirectories() }
}
