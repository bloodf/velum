@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.velum.ai.local

import dev.velum.ai.zip.*
import kotlinx.cinterop.*
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.posix.mkdir

/** Private extraction tree: no files become visible at destination until every entry passes. */
internal object IosZipArchive {
    fun extract(archiveFile: ModelStoreDir, destination: ModelStoreDir) {
        val manager = NSFileManager.defaultManager
        fun present(path: String) = manager.attributesOfItemAtPath(path, error = null) != null
        require(!present(destination.absolutePath)) { "ZIP destination must not exist" }
        val parent = ModelStoreDir(destination.absolutePath.substringBeforeLast('/'))
        parent.createDirectories()
        val staging = parent.resolve(".zip-${NSUUID().UUIDString}")
        require(mkdir(staging.absolutePath, 0x1C0u) == 0) { "Cannot create private ZIP staging directory" }
        val reader = archive_read_new()
        try {
            require(reader != null) { "Cannot allocate ZIP reader" }
            fun ok(result: Int) {
                require(result == ARCHIVE_OK) {
                    archive_error_string(reader)?.toKString() ?: "Invalid model ZIP archive"
                }
            }
            ok(archive_read_support_format_zip_seekable(reader))
            // Treat resource-fork entries as ordinary entries, never silently hide them.
            ok(archive_read_set_format_option(reader, "zip", "mac-ext", null))
            ok(archive_read_open_filename(reader, archiveFile.absolutePath, 64uL * 1024uL))
            val seen = mutableSetOf<String>()
            memScoped {
                val entry = alloc<CPointerVar<archive_entry>>()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val result = archive_read_next_header(reader, entry.ptr)
                    if (result == ARCHIVE_EOF) break
                    ok(result)
                    val item = requireNotNull(entry.value) { "Missing ZIP entry" }
                    val name = requireNotNull(archive_entry_pathname(item)) { "Missing ZIP path" }.toKString()
                    require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name && ':' !in name) { "Unsafe ZIP path" }
                    val components = name.split('/').filter { it.isNotEmpty() && it != "." }
                    require(components.isNotEmpty() && components.none { it == ".." }) { "Unsafe ZIP path" }
                    val path = components.joinToString("/")
                    require(seen.add(path.lowercase())) { "Duplicate ZIP entry" }
                    require(archive_entry_symlink(item) == null && archive_entry_hardlink(item) == null) { "ZIP links are forbidden" }
                    require(archive_entry_is_encrypted(item) == 0) { "Encrypted ZIP entries are unsupported" }
                    val kind = archive_entry_filetype(item).toInt()
                    require(kind == AE_IFREG.toInt() || kind == AE_IFDIR.toInt()) { "ZIP special files are forbidden" }
                    var ancestor = staging
                    for (component in components.dropLast(1)) {
                        ancestor = ancestor.resolve(component)
                        require(!present(ancestor.absolutePath) || ancestor.isDirectory()) { "ZIP parent collides with file" }
                        ancestor.createDirectories()
                    }
                    val output = staging.resolve(path)
                    require(!present(output.absolutePath)) { "ZIP path collision" }
                    if (kind == AE_IFDIR.toInt()) {
                        require(archive_entry_size(item) == 0L) { "Nonempty ZIP directory" }
                        output.createDirectories()
                    } else {
                        output.writeBytes(byteArrayOf())
                    }
                    var count = 0L
                    while (true) {
                        val read = buffer.usePinned { archive_read_data(reader, it.addressOf(0), buffer.size.toULong()) }
                        require(read >= 0L) { archive_error_string(reader)?.toKString() ?: "Corrupt ZIP data" }
                        if (read == 0L) break
                        require(kind == AE_IFREG.toInt()) { "Nonempty ZIP directory" }
                        output.appendBytes(buffer.copyOf(read.toInt()))
                        count += read
                    }
                    require(count == archive_entry_size(item)) { "ZIP entry size mismatch" }
                }
            }
            ok(archive_read_close(reader))
            require(!present(destination.absolutePath)) { "ZIP destination appeared during extraction" }
            require(manager.moveItemAtPath(staging.absolutePath, destination.absolutePath, error = null)) { "Cannot publish extracted ZIP" }
        } finally {
            if (reader != null) archive_read_free(reader)
            staging.deleteRecursively()
        }
    }
}
