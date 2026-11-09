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
}
