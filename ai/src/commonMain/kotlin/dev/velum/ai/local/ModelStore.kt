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
    fun appendBytes(bytes: ByteArray)
    fun readBytes(): ByteArray
    /** Random-access read of [length] bytes starting at [offset] (for spilled PCM windows). */
    fun readRange(offset: Long, length: Int): ByteArray
    fun deleteRecursively()
    fun listChildren(): List<ModelStoreDir>
    fun length(): Long
    /** Atomic-ish publish: rename this path onto [dest] (replacing it). */
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
