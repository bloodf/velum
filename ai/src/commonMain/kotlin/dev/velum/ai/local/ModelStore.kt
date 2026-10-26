package dev.velum.ai.local

/**
 * Platform models directory (`…/models/`). Weights are never committed.
 */
expect class ModelStoreDir(absolutePath: String) {
    val absolutePath: String
    fun resolve(child: String): ModelStoreDir
    fun exists(): Boolean
    fun isDirectory(): Boolean
    fun createDirectories()
    fun writeBytes(bytes: ByteArray)
    fun appendBytes(bytes: ByteArray)
    fun readBytes(): ByteArray
    fun deleteRecursively()
    fun listChildren(): List<ModelStoreDir>
    fun length(): Long
}

expect fun defaultModelStoreRoot(): ModelStoreDir
