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
        ensureDir(file)
    }

    actual fun writeBytes(bytes: ByteArray) {
        file.parentFile?.let(::ensureDir)
        file.writeBytes(bytes)
    }

    actual fun appendBytes(bytes: ByteArray) {
        file.parentFile?.let(::ensureDir)
        file.appendBytes(bytes)
    }

    actual fun readBytes(): ByteArray = file.readBytes()

    actual fun readRange(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "offset=$offset length=$length" }
        java.io.RandomAccessFile(file, "r").use { raf ->
            require(offset + length <= raf.length()) {
                "range $offset+$length exceeds file ${raf.length()}"
            }
            raf.seek(offset)
            val out = ByteArray(length)
            raf.readFully(out)
            return out
        }
    }

    actual fun deleteFile(): Boolean = !file.isDirectory && file.delete()

    actual fun deleteRecursively() {
        file.deleteRecursively()
    }

    actual fun listChildren(): List<ModelStoreDir> =
        file.listFiles()?.map { ModelStoreDir(it.absolutePath) } ?: emptyList()

    actual fun length(): Long = if (file.isFile) file.length() else 0L

    actual fun lastModifiedMillis(): Long = file.lastModified()

    actual fun renameTo(dest: ModelStoreDir): Boolean {
        val target = File(dest.absolutePath)
        target.parentFile?.mkdirs()
        if (file.isFile && !target.isDirectory) {
            // Atomic replace: readers see the old file or the new one, never neither.
            return runCatching {
                try {
                    java.nio.file.Files.move(
                        file.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        file.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }.isSuccess
        }
        if (target.exists()) target.deleteRecursively()
        return file.renameTo(target)
    }
}

actual fun defaultModelStoreRoot(): ModelStoreDir {
    // App code should pass Context.filesDir/models; this is a fallback for tests.
    val tmp = System.getProperty("java.io.tmpdir") ?: "."
    return ModelStoreDir(File(tmp, "velum-models").absolutePath)
}

// Files.createDirectories throws on a symlink to a directory; only create when it is not one already.
private fun ensureDir(dir: File) {
    if (!dir.isDirectory) java.nio.file.Files.createDirectories(dir.toPath())
}
