package dev.velum.ai.local

import java.io.File

actual class ModelStoreDir actual constructor(absolutePath: String) {
    private val file = File(absolutePath)

    actual val absolutePath: String = file.absolutePath

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
}

actual fun defaultModelStoreRoot(): ModelStoreDir {
    // App code should pass Context.filesDir/models; this is a fallback for tests.
    val tmp = System.getProperty("java.io.tmpdir") ?: "."
    return ModelStoreDir(File(tmp, "velum-models").absolutePath)
}
