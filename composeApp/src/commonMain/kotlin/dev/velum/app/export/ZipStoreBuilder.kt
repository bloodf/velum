package dev.velum.app.export

/**
 * Minimal ZIP writer using the store (no compression) method.
 * Sufficient for OOXML packages that Word / LibreOffice accept.
 */
internal class ZipStoreBuilder {
    private data class Entry(
        val name: String,
        val data: ByteArray,
        val crc: Int,
        val localHeaderOffset: Int,
    )

    private val entries = mutableListOf<Entry>()
    private val localParts = mutableListOf<ByteArray>()
    private var offset = 0

    fun add(name: String, data: ByteArray): ZipStoreBuilder {
        val crc = crc32(data)
        val nameBytes = name.encodeToByteArray()
        val local = ByteArrayBuilder()
            .u32(0x04034b50) // local file header signature
            .u16(20) // version needed
            .u16(0) // flags
            .u16(0) // method = store
            .u16(0) // mod time
            .u16(0) // mod date
            .u32(crc)
            .u32(data.size)
            .u32(data.size)
            .u16(nameBytes.size)
            .u16(0) // extra len
            .bytes(nameBytes)
            .bytes(data)
            .toByteArray()
        entries += Entry(name, data, crc, offset)
        localParts += local
        offset += local.size
        return this
    }

    fun build(): ByteArray {
        val central = ByteArrayBuilder()
        entries.forEach { entry ->
            val nameBytes = entry.name.encodeToByteArray()
            central
                .u32(0x02014b50) // central directory header
                .u16(20) // version made by
                .u16(20) // version needed
                .u16(0)
                .u16(0) // store
                .u16(0)
                .u16(0)
                .u32(entry.crc)
                .u32(entry.data.size)
                .u32(entry.data.size)
                .u16(nameBytes.size)
                .u16(0) // extra
                .u16(0) // comment
                .u16(0) // disk start
                .u16(0) // int attrs
                .u32(0) // ext attrs
                .u32(entry.localHeaderOffset)
                .bytes(nameBytes)
        }
        val centralBytes = central.toByteArray()
        val end = ByteArrayBuilder()
            .u32(0x06054b50)
            .u16(0)
            .u16(0)
            .u16(entries.size)
            .u16(entries.size)
            .u32(centralBytes.size)
            .u32(offset)
            .u16(0)
            .toByteArray()
        val out = ByteArray(offset + centralBytes.size + end.size)
        var pos = 0
        localParts.forEach { part ->
            part.copyInto(out, pos)
            pos += part.size
        }
        centralBytes.copyInto(out, pos)
        pos += centralBytes.size
        end.copyInto(out, pos)
        return out
    }
}

private class ByteArrayBuilder {
    private val chunks = mutableListOf<ByteArray>()
    private var size = 0

    fun u16(value: Int): ByteArrayBuilder {
        val v = value and 0xFFFF
        return bytes(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }

    fun u32(value: Int): ByteArrayBuilder {
        val v = value
        return bytes(
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte(),
            ),
        )
    }

    fun bytes(data: ByteArray): ByteArrayBuilder {
        if (data.isNotEmpty()) {
            chunks += data
            size += data.size
        }
        return this
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray(size)
        var pos = 0
        chunks.forEach { chunk ->
            chunk.copyInto(out, pos)
            pos += chunk.size
        }
        return out
    }
}

/** IEEE CRC-32 (ZIP / PNG polynomial). */
internal fun crc32(data: ByteArray): Int {
    var crc = 0xFFFFFFFF.toInt()
    for (b in data) {
        val index = (crc xor (b.toInt() and 0xFF)) and 0xFF
        crc = CRC_TABLE[index] xor (crc ushr 8)
    }
    return crc.inv()
}

private val CRC_TABLE: IntArray = IntArray(256) { n ->
    var c = n
    repeat(8) {
        c = if ((c and 1) != 0) {
            (c ushr 1) xor 0xEDB88320.toInt()
        } else {
            c ushr 1
        }
    }
    c
}
