package dev.velum.ai.local

import java.io.File

actual class ModelStoreDir actual constructor(absolutePath: String) {
    private val file = File(absolutePath)

    actual val absolutePath: String = file.absolutePath

    actual val name: String = file.name

    actual fun resolve(child: String): ModelStoreDir =
        ModelStoreDir(File(file, child).absolutePath)

    actual fun exists(): Boolean = file.exists()

    actual fun isDirectory(): Boolean = file.isDirectory

    actual fun createDirectories() {
        if (!file.exists()) file.mkdirs()
    }

    actual fun writeBytes(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    actual fun appendBytes(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.appendBytes(bytes)
    }

    actual fun readBytes(): ByteArray = file.readBytes()

    actual fun deleteRecursively() {
        file.deleteRecursively()
    }

    actual fun listChildren(): List<ModelStoreDir> =
        file.listFiles()?.map { ModelStoreDir(it.absolutePath) } ?: emptyList()

    actual fun length(): Long = if (file.isFile) file.length() else 0L

    actual fun renameTo(dest: ModelStoreDir): Boolean {
        val target = File(dest.absolutePath)
        target.parentFile?.mkdirs()
        if (target.exists()) target.deleteRecursively()
        return file.renameTo(target)
    }
}

actual fun defaultModelStoreRoot(): ModelStoreDir {
    val home = System.getProperty("user.home") ?: "."
    return ModelStoreDir(File(home, ".velum/models").absolutePath)
}
