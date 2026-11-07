package dev.velum.ai.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/** #279: archives are removed once extracted, and a full disk is reported as a plain, localizable state. */
class ModelStorageTest {
    private val id = "parakeet-tdt-0.6b-v3-int8"
    private val files = arrayOf(
        "weights/tokens.txt" to "a\n", "weights/encoder.int8.onnx" to "enc",
        "weights/decoder.int8.onnx" to "dec", "weights/joiner.int8.onnx" to "join",
    )

    private fun zip(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipArchiveOutputStream(out).use { z ->
            files.forEach { (name, text) -> z.putArchiveEntry(ZipArchiveEntry(name)); z.write(text.encodeToByteArray()); z.closeArchiveEntry() }
        }
    }.toByteArray()

    private fun spec(bytes: ByteArray) = LocalModelSpec(
        id = id, displayName = "Parakeet", kind = LocalModelKind.Asr, approxBytes = bytes.size.toLong(), license = "CC-BY-4.0",
        notes = "test", languages = "en", downloadUrl = "https://example.test/model.zip", archiveFileName = "model.zip", sha256 = sha256Hex(bytes),
    )

    private fun newRoot() = ModelStoreDir(File.createTempFile("velum-storage", "").apply { delete(); mkdirs() }.absolutePath)

    @Test fun restart_verifies_single_file_weights_offline_and_preserves_valid_cached_bytes() = runTest {
        val root = newRoot()
        val bytes = "verified model".encodeToByteArray()
        val spec = spec(bytes).copy(kind = LocalModelKind.Llm, archiveFileName = "model.litertlm")
        val weights = root.resolve(id).resolve(spec.archiveFileName)
        val client = HttpClient(MockEngine { error("restart must not access the network") })
        try {
            weights.writeBytes(bytes)
            assertTrue(LocalModelManager(root, client, listOf(spec)).also { it.awaitInitialization() }.isReady(id))
            assertTrue(weights.readBytes().contentEquals(bytes), "valid cache changed")
            weights.writeBytes(ByteArray(bytes.size) { 1 })
            assertFalse(LocalModelManager(root, client, listOf(spec)).also { it.awaitInitialization() }.isReady(id), "same-size corrupt file became Ready")
            weights.writeBytes(byteArrayOf(1))
            assertFalse(LocalModelManager(root, client, listOf(spec)).also { it.awaitInitialization() }.isReady(id), "truncated fallback copy became Ready")
            assertTrue(weights.exists(), "verification must not delete the owner's cache")
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test fun the_archive_is_removed_after_a_successful_extraction_and_the_model_stays_ready() = runTest {
        val bytes = zip(); val root = newRoot()
        val manager = LocalModelManager(root, HttpClient(MockEngine {
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
        }), listOf(spec(bytes)))
        manager.awaitInitialization()
        assertEquals(LocalModelStatus.Ready, manager.download(id).toList().last().status)
        assertFalse(root.resolve(id).resolve("model.zip").exists(), "archive kept next to the extracted files")
        assertTrue(manager.modelPath(id)!!.resolve("tokens.txt").exists())
        manager.refreshFromDisk().join()
        assertTrue(manager.isReady(id), "readiness must not depend on the archive")
        root.deleteRecursively()
    }

    @Test fun an_archive_left_by_an_earlier_version_is_reclaimed_on_startup() = runTest {
        val bytes = zip(); val root = newRoot()
        val dir = root.resolve(id).also { it.createDirectories() }
        files.forEach { (name, text) -> dir.resolve(name.substringAfter('/')).writeBytes(text.encodeToByteArray()) }
        dir.resolve("model.zip").writeBytes(bytes)
        val manager = LocalModelManager(root, HttpClient(MockEngine { respond("") }), listOf(spec(bytes)))
        manager.awaitInitialization()
        assertTrue(manager.isReady(id))
        assertFalse(dir.resolve("model.zip").exists())
        root.deleteRecursively()
    }

    @Test fun no_archive_based_catalog_model_is_ready_from_its_archive_alone() = runTest {
        val archives = DefaultLocalCatalog.filter { it.usesArchiveExtract() }
        assertTrue(archives.size >= 6, "expected the archive-based models, got ${archives.map { it.id }}")
        for (spec in archives) {
            val root = newRoot(); val dir = root.resolve(spec.id).also { it.createDirectories() }
            dir.resolve(spec.archiveFileName).writeBytes(ByteArray(64))
            assertFalse(ModelReadiness.isReady(spec, dir), "${spec.id} counts its archive as weights")
            root.deleteRecursively()
        }
    }

    @Test fun not_enough_free_space_fails_before_any_request_with_the_storage_marker() = runTest {
        val bytes = zip(); val root = newRoot(); val requests = AtomicInteger()
        val manager = LocalModelManager(root, HttpClient(MockEngine { requests.incrementAndGet(); respond(bytes) }), listOf(spec(bytes)), freeSpace = { 1L })
        manager.awaitInitialization()
        val last = manager.download(id).toList().last()
        assertEquals(LocalModelStatus.Failed, last.status)
        assertEquals(LocalModelErrors.StorageFull, last.error)
        assertEquals(0, requests.get())
        root.deleteRecursively()
    }

    @Test fun a_write_failure_from_the_disk_becomes_the_storage_marker_not_errno_text() = runTest {
        val bytes = zip(); val root = newRoot()
        val manager = LocalModelManager(root, HttpClient(MockEngine { throw IOException("write failed: ENOSPC (No space left on device)") }), listOf(spec(bytes)))
        manager.awaitInitialization()
        val last = manager.download(id).toList().last()
        assertEquals(LocalModelStatus.Failed, last.status)
        assertEquals(LocalModelErrors.StorageFull, last.error)
        assertFalse(last.error!!.contains("ENOSPC"))
        root.deleteRecursively()
    }

    @Test fun an_http_failure_is_stored_as_its_status_code() = runTest {
        val bytes = zip(); val root = newRoot()
        val manager = LocalModelManager(root, HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }), listOf(spec(bytes)))
        manager.awaitInitialization()
        assertEquals("http_404", manager.download(id).toList().last().error)
        root.deleteRecursively()
    }

    @Test fun space_needed_counts_pending_downloads_plus_room_to_extract_the_largest_archive() = runTest {
        val bytes = zip(); val root = newRoot()
        val manager = LocalModelManager(root, HttpClient(MockEngine { respond("") }), listOf(spec(bytes)))
        manager.awaitInitialization()
        val size = bytes.size.toLong()
        assertEquals(size, manager.downloadBytesFor(listOf(id)))
        assertEquals(size * 2, manager.bytesNeededFor(listOf(id)))
        root.deleteRecursively()
    }

    // A killed download leaves a large .partial; the retry must not be told to free space it is holding itself.
    @Test fun a_retry_is_not_blocked_by_the_models_own_leftovers() = runTest {
        val bytes = zip(); val root = newRoot(); val capacity = bytes.size * 10L
        val dir = root.resolve(id).also { it.createDirectories() }
        dir.resolve("model.zip.partial").writeBytes(ByteArray((bytes.size * 9).toInt()))
        fun used(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val manager = LocalModelManager(root, HttpClient(MockEngine {
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
        }), listOf(spec(bytes)), freeSpace = { capacity - used(File(root.absolutePath)) })
        manager.awaitInitialization()
        val last = manager.download(id).toList().last()
        assertEquals(LocalModelStatus.Ready, last.status, last.error)
        root.deleteRecursively()
    }

    // ENOSPC or a kill while extracting must never leave truncated weights that restart reports as Ready.
    @Test fun a_failed_extraction_exposes_no_weights_and_restart_is_not_ready() = runTest {
        val whole = zip(); val cut = whole.copyOf(whole.size * 7 / 10); val root = newRoot()
        val manager = LocalModelManager(root, HttpClient(MockEngine {
            respond(cut, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, cut.size.toString()))
        }), listOf(spec(cut)))
        manager.awaitInitialization()
        val last = manager.download(id).toList().last()
        assertEquals(LocalModelStatus.Failed, last.status)
        assertFalse(root.resolve(id).resolve("tokens.txt").exists() || root.resolve(id).resolve("weights").exists())
        val restarted = LocalModelManager(root, HttpClient(MockEngine { respond("") }), listOf(spec(cut)))
        restarted.awaitInitialization()
        assertFalse(restarted.isReady(id))
        root.deleteRecursively()
    }

    @Test fun weights_left_in_the_staging_tree_by_a_kill_are_never_ready() = runTest {
        val bytes = zip(); val root = newRoot()
        val staging = root.resolve(id).resolve(ModelReadiness.STAGING_DIR).also { it.createDirectories() }
        files.forEach { (name, text) -> staging.resolve(name.substringAfter('/')).writeBytes(text.encodeToByteArray()) }
        val manager = LocalModelManager(root, HttpClient(MockEngine { respond("") }), listOf(spec(bytes)))
        manager.awaitInitialization()
        assertFalse(manager.isReady(id))
        assertEquals(null, manager.modelPath(id))
        root.deleteRecursively()
    }

    @Test fun ready_models_are_not_counted_in_what_is_left_to_download() = runTest {
        val bytes = zip(); val root = newRoot()
        val dir = root.resolve(id).also { it.createDirectories() }
        files.forEach { (name, text) -> dir.resolve(name.substringAfter('/')).writeBytes(text.encodeToByteArray()) }
        val manager = LocalModelManager(root, HttpClient(MockEngine { respond("") }), listOf(spec(bytes)))
        manager.awaitInitialization()
        assertTrue(manager.isReady(id))
        assertEquals(0L, manager.downloadBytesFor(listOf(id)))
        assertEquals(0L, manager.bytesNeededFor(listOf(id)))
        root.deleteRecursively()
    }

    // A Ready model is not fetched again: a failed re-download would otherwise cost the working copy.
    @Test fun startDownloads_skips_a_model_that_is_already_ready() = runTest {
        val bytes = zip(); val root = newRoot(); val requests = AtomicInteger()
        val dir = root.resolve(id).also { it.createDirectories() }
        files.forEach { (name, text) -> dir.resolve(name.substringAfter('/')).writeBytes(text.encodeToByteArray()) }
        val manager = LocalModelManager(root, HttpClient(MockEngine { requests.incrementAndGet(); respond("", HttpStatusCode.InternalServerError) }), listOf(spec(bytes)))
        manager.awaitInitialization()
        assertTrue(manager.isReady(id))
        manager.startDownloads(listOf(id)).join()
        assertEquals(0, requests.get())
        assertTrue(manager.isReady(id))
        assertTrue(dir.resolve("tokens.txt").exists())
        root.deleteRecursively()
    }

    // The race: a caller read "not Ready", the other download finished, then this one registered. download() must
    // re-check, whatever the caller did, and must not touch a Ready model.
    @Test fun download_of_a_ready_model_fetches_nothing_and_keeps_the_files() = runTest {
        val bytes = zip(); val root = newRoot(); val requests = AtomicInteger()
        val dir = root.resolve(id).also { it.createDirectories() }
        files.forEach { (name, text) -> dir.resolve(name.substringAfter('/')).writeBytes(text.encodeToByteArray()) }
        val manager = LocalModelManager(root, HttpClient(MockEngine { requests.incrementAndGet(); respond("", HttpStatusCode.InternalServerError) }), listOf(spec(bytes)))
        manager.awaitInitialization()
        assertEquals(LocalModelStatus.Ready, manager.download(id).toList().last().status)
        assertEquals(0, requests.get())
        assertTrue(dir.resolve("tokens.txt").exists())
        root.deleteRecursively()
    }
}
