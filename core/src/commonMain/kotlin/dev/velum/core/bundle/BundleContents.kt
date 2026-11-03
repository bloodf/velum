package dev.velum.core.bundle

/**
 * Plaintext inside a [SealedBundle]: named sections of opaque bytes (one per synced data type, e.g. "transcripts",
 * "notes", "templates"). Layout: count u32 | per section: nameLen u8, name UTF-8, dataLen u32, data. Parsing is strict:
 * trailing bytes, duplicate names, overruns or empty names make the whole bundle invalid.
 */
class BundleContents(val sections: Map<String, ByteArray>) {
    fun encode(): ByteArray {
        var out = u32(sections.size)
        for ((name, data) in sections.entries.sortedBy { it.key }.map { it.key to it.value }) {
            val n = name.encodeToByteArray()
            require(n.isNotEmpty() && n.size <= 255) { "section name must be 1..255 bytes" }
            out += byteArrayOf(n.size.toByte()) + n + u32(data.size) + data
        }
        return out
    }

    companion object {
        const val MAX_SECTIONS = 1024

        fun decode(bytes: ByteArray): BundleContents {
            var pos = 0
            fun need(n: Int) { if (n < 0 || pos + n > bytes.size) throw BundleUnreadableException() }
            fun u32(): Int { need(4); return ((bytes[pos].toInt() and 0xFF) shl 24 or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)).also { pos += 4 } }
            val count = u32()
            if (count !in 0..MAX_SECTIONS) throw BundleUnreadableException()
            val out = LinkedHashMap<String, ByteArray>()
            repeat(count) {
                need(1)
                val nameLen = bytes[pos++].toInt() and 0xFF
                if (nameLen == 0) throw BundleUnreadableException()
                need(nameLen)
                val name = bytes.copyOfRange(pos, pos + nameLen).decodeToString().also { pos += nameLen }
                val len = u32()
                need(len)
                if (out.put(name, bytes.copyOfRange(pos, pos + len)) != null) throw BundleUnreadableException()
                pos += len
            }
            if (pos != bytes.size) throw BundleUnreadableException()
            return BundleContents(out)
        }

        private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }
}

/** A data type's import: [prepare] validates its bytes and returns the commit, or throws. */
fun interface SectionImporter {
    fun prepare(data: ByteArray): () -> Unit
}

object BundleImporter {
    /**
     * Opens [sealed] and applies it all-or-nothing: the bundle must authenticate, every section must have an importer,
     * and every [SectionImporter.prepare] must succeed before any commit runs. Commits then run in sorted section order;
     * wrap them in one database transaction via [atomically] so a commit failure also rolls back.
     */
    fun import(
        passphrase: String,
        sealed: ByteArray,
        importers: Map<String, SectionImporter>,
        atomically: (() -> Unit) -> Unit = { it() },
    ) {
        val contents = BundleContents.decode(SealedBundle.open(passphrase, sealed))
        val unknown = contents.sections.keys - importers.keys
        if (unknown.isNotEmpty()) throw BundleUnreadableException()
        val commits = contents.sections.entries.sortedBy { it.key }.map { (name, data) -> importers.getValue(name).prepare(data) }
        atomically { commits.forEach { it() } }
    }
}
