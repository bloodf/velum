package dev.velum.ai.local

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TarModelArchiveTest {
    private fun archive(name: String, link: Boolean = false): ByteArray = ByteArrayOutputStream().also { out ->
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val entry = if (link) TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = "../outside" }
                else TarArchiveEntry(name).apply { size = 6 }
            tar.putArchiveEntry(entry)
            if (!link) tar.write("weight".encodeToByteArray())
            tar.closeArchiveEntry()
        }
    }.toByteArray()

    private fun extract(bytes: ByteArray, root: ModelStoreDir) {
        val input = ByteArrayInputStream(bytes)
        TarModelArchive.extract({ input.read(it, 0, minOf(it.size, 7)) }, root)
    }

    @Test fun streaming_tar_extracts_pax_paths_with_bounded_reads() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-tar").toString())
        try {
            val path = "models/" + "long-directory/".repeat(10) + "encoder.onnx"
            extract(archive(path), root)
            assertEquals("weight", root.resolve(path).readBytes().decodeToString())
        } finally { root.deleteRecursively() }
    }

    @Test fun tar_rejects_traversal_links_bad_headers_and_truncated_data() {
        for (bytes in listOf(
            archive("../outside"), archive("symlink", link = true),
            archive("encoder.onnx").apply { this[0] = 1 }, archive("encoder.onnx").copyOf(515),
        )) {
            val root = ModelStoreDir(Files.createTempDirectory("velum-tar-invalid").toString())
            try {
                assertFailsWith<IllegalArgumentException> { extract(bytes, root) }
                assertFalse(root.resolve("symlink").exists())
            } finally { root.deleteRecursively() }
        }
    }

    private fun rawEntry(name: String, kind: Char, body: ByteArray = byteArrayOf(), declaredSize: Long = body.size.toLong()): ByteArray {
        val header = ByteArray(512)
        name.encodeToByteArray().copyInto(header, 0)
        (declaredSize.toString(8).padStart(11, '0') + "\u0000").encodeToByteArray().copyInto(header, 124)
        header[156] = kind.code.toByte()
        val sum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }
        (sum.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(header, 148)
        return header + body + ByteArray((512 - body.size % 512) % 512)
    }

    private fun pax(attribute: String): ByteArray {
        val value = "$attribute\n".encodeToByteArray()
        var length = value.size + 2
        while (length != value.size + length.toString().length + 1) {
            length = value.size + length.toString().length + 1
        }
        return "$length ".encodeToByteArray() + value
    }

    private fun withRoot(check: (ModelStoreDir) -> Unit) {
        val root = ModelStoreDir(Files.createTempDirectory("velum-tar-regression").toString())
        try { check(root) } finally { root.deleteRecursively() }
    }

    @Test fun malformed_pax_is_rejected_before_file_creation() {
        val records = listOf(
            "no-space".encodeToByteArray(), "999999999999 path=a\n".encodeToByteArray(),
            "-1 path=a\n".encodeToByteArray(), "999 path=a\n".encodeToByteArray(),
            pax("path=wrong").apply { this[lastIndex] = 'x'.code.toByte() },
            pax("missing_equals"), pax("=empty_key"), pax("size=6"), pax("linkpath=target"),
        )
        for (record in records) withRoot { root ->
            assertFailsWith<IllegalArgumentException> {
                extract(rawEntry("pax", 'x', record) + rawEntry("model", '0', byteArrayOf(7)) + ByteArray(1024), root)
            }
            assertEquals(emptyList(), root.listChildren())
        }
        withRoot { root ->
            assertFailsWith<IllegalArgumentException> {
                extract(rawEntry("pax", 'g', pax("path=global")) + ByteArray(1024), root)
            }
            assertEquals(emptyList(), root.listChildren())
        }
    }

    @Test fun path_metadata_preserves_newlines_and_is_consumed_once() {
        for (kind in listOf('x', 'L')) withRoot { root ->
            val name = "model\n"
            val metadata = if (kind == 'x') pax("path=$name") else "$name\u0000".encodeToByteArray()
            extract(rawEntry("metadata", kind, metadata) + rawEntry("fallback", '0', byteArrayOf(7)) +
                rawEntry("second", '0', byteArrayOf(8)) + ByteArray(1024), root)
            assertEquals(listOf<Byte>(7), root.resolve(name).readBytes().toList())
            assertEquals(listOf<Byte>(8), root.resolve("second").readBytes().toList())
            assertFalse(root.resolve("model").exists())
        }
    }

    @Test fun conflicting_path_metadata_is_rejected_in_both_orders() {
        val gnu = rawEntry("longname", 'L', "gnu\u0000".encodeToByteArray())
        val posix = rawEntry("pax", 'x', pax("path=posix"))
        for (metadata in listOf(gnu + posix, posix + gnu)) withRoot { root ->
            assertFailsWith<IllegalArgumentException> {
                extract(metadata + rawEntry("fallback", '0', byteArrayOf(7)) + ByteArray(1024), root)
            }
            assertEquals(emptyList(), root.listChildren())
        }
    }

    @Test fun directory_over_file_is_rejected_and_original_bytes_survive() = withRoot { root ->
        val error = assertFailsWith<IllegalArgumentException> {
            extract(rawEntry("weights", '0', "original".encodeToByteArray()) + rawEntry("weights", '5') + ByteArray(1024), root)
        }
        assertEquals("Tar directory collides with file", error.message)
        assertEquals("original", root.resolve("weights").readBytes().decodeToString())
    }

    @Test fun metadata_limit_rejects_all_supported_metadata_kinds() {
        for (kind in listOf('x', 'g', 'L')) withRoot { root ->
            val error = assertFailsWith<IllegalArgumentException> {
                extract(rawEntry("metadata", kind, declaredSize = 1048577) + ByteArray(1024), root)
            }
            assertEquals("Tar metadata too large", error.message)
            assertEquals(emptyList(), root.listChildren())
        }
    }

    @Test fun duplicate_files_and_file_over_directory_are_rejected() {
        for (first in listOf(rawEntry("weights", '0', "original".encodeToByteArray()), rawEntry("weights", '5'))) withRoot { root ->
            assertFailsWith<IllegalArgumentException> {
                extract(first + rawEntry("weights", '0', byteArrayOf(7)) + ByteArray(1024), root)
            }
        }
    }

    @Test fun incomplete_and_corrupt_end_markers_are_rejected() {
        for (end in listOf(ByteArray(512), ByteArray(512) + ByteArray(512) { 1 })) withRoot { root ->
            assertFailsWith<IllegalArgumentException> { extract(end, root) }
        }
    }
}
