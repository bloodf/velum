package dev.velum.ai.local

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class ModelArchiveExtractorTest {
    @Test
    fun extracts_zip_and_marks_ready_for_transducer_layout() = runTest {
        val rootDir = File.createTempFile("velum-models", "").also { it.delete(); it.mkdirs() }
        val root = ModelStoreDir(rootDir.absolutePath)
        val zipBytes = buildZip(
            "weights/tokens.txt" to "a\n",
            "weights/encoder.int8.onnx" to "enc",
            "weights/decoder.int8.onnx" to "dec",
            "weights/joiner.int8.onnx" to "join",
        )
        val engine = MockEngine {
            respond(
                content = zipBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/zip"),
            )
        }
        val manager = LocalModelManager(
            storeRoot = root,
            httpClient = HttpClient(engine),
            catalog = listOf(
                LocalModelSpec(
                    id = "parakeet-tdt-0.6b-v3-int8",
                    displayName = "Parakeet",
                    kind = LocalModelKind.Asr,
                    approxBytes = zipBytes.size.toLong(),
                    license = "CC-BY-4.0",
                    notes = "test",
                    languages = "en",
                    downloadUrl = "https://example.test/model.zip",
                    archiveFileName = "model.zip",
                    sha256 = sha256Hex(zipBytes),
                ),
            ),
        )
        val states = manager.download("parakeet-tdt-0.6b-v3-int8").toList()
        assertEquals(LocalModelStatus.Ready, states.last().status, states.last().error)
        val weights = manager.modelPath("parakeet-tdt-0.6b-v3-int8")
        assertTrue(weights != null)
        assertTrue(weights!!.resolve("tokens.txt").exists())
        assertTrue(weights.resolve("encoder.int8.onnx").exists())
    }

    @Test
    fun extracts_tar_bz2_synthetic() {
        val dir = File.createTempFile("velum-tar", "").also { it.delete(); it.mkdirs() }
        val archiveFile = File(dir, "sample.tar.bz2")
        archiveFile.writeBytes(
            buildTarBz2(
                "nested/tokens.txt" to "tok\n",
                "nested/model.int8.onnx" to "onnx",
            ),
        )
        val dest = ModelStoreDir(File(dir, "out").absolutePath)
        ModelArchiveExtractor.extract(ModelStoreDir(archiveFile.absolutePath), dest)
        assertTrue(dest.resolve("nested").resolve("tokens.txt").exists())
        assertTrue(dest.resolve("nested").resolve("model.int8.onnx").exists())
        assertTrue(ModelArchiveExtractor.isArchive("x.tar.bz2"))
        assertFalse(ModelArchiveExtractor.isArchive("x.bin"))
    }

    @Test
    fun incomplete_extract_is_not_ready() {
        val rootDir = File.createTempFile("velum-incomplete", "").also { it.delete(); it.mkdirs() }
        val modelDir = ModelStoreDir(rootDir.absolutePath).resolve("whisper-large-v3-turbo")
        modelDir.createDirectories()
        modelDir.resolve("only-tokens.txt").writeBytes("x".encodeToByteArray())
        val spec = DefaultLocalCatalog.first { it.id == "whisper-large-v3-turbo" }
        assertFalse(ModelReadiness.isReady(spec, modelDir))
        assertTrue(ModelReadiness.missingRequirements(spec, modelDir).isNotEmpty())
    }

    @Test
    fun cosine_similarity_matches_identical_vectors() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        assertEquals(1f, VoiceProfileStore.cosineSimilarity(a, b))
        val storeRoot = ModelStoreDir(
            File.createTempFile("velum-voices", "").also { it.delete(); it.mkdirs() }.absolutePath,
        )
        val store = VoiceProfileStore(storeRoot)
        store.upsert("Alice", floatArrayOf(0.1f, 0.2f, 0.3f))
        val match = store.match(floatArrayOf(0.1f, 0.2f, 0.3f), threshold = 0.99f)
        assertEquals("Alice", match?.displayName)
    }

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipArchiveOutputStream(baos).use { zip ->
            for ((name, content) in entries) {
                val bytes = content.encodeToByteArray()
                val entry = ZipArchiveEntry(name)
                entry.size = bytes.size.toLong()
                zip.putArchiveEntry(entry)
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun buildTarBz2(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        BZip2CompressorOutputStream(baos).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                for ((name, content) in entries) {
                    val bytes = content.encodeToByteArray()
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return baos.toByteArray()
    }
}
