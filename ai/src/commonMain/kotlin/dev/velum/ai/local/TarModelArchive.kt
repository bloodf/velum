package dev.velum.ai.local

/** Streaming tar reader for the model catalog, including GNU long names and POSIX path records. */
internal object TarModelArchive {
    fun extract(read: (ByteArray) -> Int, destination: ModelStoreDir) {
        destination.createDirectories()
        require(destination.listChildren().isEmpty()) { "Archive destination must be empty" }
        fun exact(size: Int): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val buffer = ByteArray(size - offset)
                val count = read(buffer)
                require(count in 1..buffer.size) { "Truncated model archive" }
                buffer.copyInto(result, offset, 0, count)
                offset += count
            }
            return result
        }
        fun field(header: ByteArray, offset: Int, size: Int): String =
            header.copyOfRange(offset, offset + size).takeWhile { it != 0.toByte() }.toByteArray().decodeToString(throwOnInvalidSequence = true)
        fun octal(value: String): Long = value.trim(' ', '\u0000').ifEmpty { "0" }.toLong(8)
        var nextPath: String? = null
        while (true) {
            val header = exact(512)
            if (header.all { it == 0.toByte() }) {
                require(exact(512).all { it == 0.toByte() }) { "Invalid tar end marker" }
                return
            }
            val checksum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }
            require(octal(field(header, 148, 8)) == checksum.toLong()) { "Invalid tar header checksum" }
            val size = octal(field(header, 124, 12))
            require(size >= 0) { "Invalid tar entry size" }
            val kind = header[156].toInt().toChar()
            if (kind == 'x' || kind == 'g' || kind == 'L') {
                require(size <= 1024 * 1024) { "Tar metadata too large" }
                val metadata = exact(size.toInt())
                if (kind == 'L') {
                    require(nextPath == null) { "Conflicting tar path metadata" }
                    nextPath = metadata.decodeToString(throwOnInvalidSequence = true).trimEnd('\u0000')
                } else {
                    var offset = 0
                    while (offset < metadata.size) {
                        val space = (offset until metadata.size).firstOrNull { metadata[it] == 32.toByte() }
                            ?: throw IllegalArgumentException("Invalid PAX record")
                        val digits = metadata.copyOfRange(offset, space).decodeToString()
                        require(digits.isNotEmpty() && digits.all { it in '0'..'9' }) { "Invalid PAX record" }
                        val length = digits.toIntOrNull() ?: throw IllegalArgumentException("Invalid PAX record")
                        require(length > space - offset + 1 && length <= metadata.size - offset) { "Invalid PAX record" }
                        require(metadata[offset + length - 1] == 10.toByte()) { "Invalid PAX record newline" }
                        val record = metadata.copyOfRange(space + 1, offset + length - 1).decodeToString(throwOnInvalidSequence = true)
                        require(record.indexOf('=') > 0) { "Invalid PAX attribute" }
                        if (record.startsWith("path=")) {
                            require(kind == 'x') { "Global tar path is not supported" }
                            require(nextPath == null) { "Conflicting tar path metadata" }
                            nextPath = record.removePrefix("path=")
                        }
                        require(!record.startsWith("size=") && !record.startsWith("linkpath=")) { "Unsupported PAX size or link" }
                        offset += length
                    }
                }
            } else {
                val prefix = field(header, 345, 155)
                val name = nextPath ?: listOf(prefix, field(header, 0, 100)).filter { it.isNotEmpty() }.joinToString("/")
                nextPath = null
                require(!name.startsWith('/') && '\\' !in name && '\u0000' !in name) { "Unsafe tar path" }
                val components = name.split('/').filter { it.isNotEmpty() && it != "." }
                require(components.none { it == ".." }) { "Archive entry escapes destination" }
                require(kind == '0' || kind == '\u0000' || kind == '5') { "Archive links and special files are not allowed" }
                val output = destination.resolve(components.joinToString("/"))
                if (kind == '5') {
                    require(size == 0L) { "Invalid tar directory size" }
                    require(!output.exists() || output.isDirectory()) { "Tar directory collides with file" }
                    output.createDirectories()
                } else {
                    require(components.isNotEmpty() && !output.exists()) { "Duplicate or invalid tar file" }
                    output.writeBytes(ByteArray(0))
                    var remaining = size
                    while (remaining > 0) {
                        val bytes = exact(minOf(64 * 1024L, remaining).toInt())
                        output.appendBytes(bytes)
                        remaining -= bytes.size
                    }
                }
            }
            val padding = ((512 - size % 512) % 512).toInt()
            if (padding > 0) exact(padding)
        }
    }
}
