package dev.velum.ai.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** #301: a failure is reported once, never for a cancel, and queued rows are known to the UI. */
class ModelQueueTest {
    private fun state(id: String, status: LocalModelStatus) = LocalModelState(
        LocalModelSpec(id = id, displayName = id, kind = LocalModelKind.Asr, approxBytes = 1, license = "MIT", notes = "", languages = ""),
        status,
    )

    @Test fun a_download_that_fails_is_reported_once_and_a_cancel_never_is() {
        val before = mapOf("a" to LocalModelStatus.Downloading, "b" to LocalModelStatus.Downloading, "c" to LocalModelStatus.Failed, "d" to LocalModelStatus.NotDownloaded, "e" to LocalModelStatus.Ready)
        val after = listOf(
            state("a", LocalModelStatus.Failed),          // download failed
            state("b", LocalModelStatus.NotDownloaded),   // cancelled
            state("c", LocalModelStatus.Failed),          // was already failed (e.g. found at startup)
            state("d", LocalModelStatus.Failed),          // refused before starting (not enough space)
            state("e", LocalModelStatus.Ready),
        )
        assertEquals(listOf("a", "d"), newlyFailed(before, after).map { it.spec.id })
        assertTrue(newlyFailed(before + mapOf("a" to LocalModelStatus.Failed, "d" to LocalModelStatus.Failed), after).isEmpty())
    }

    private fun spec(id: String, sha: String) = LocalModelSpec(
        id = id, displayName = id, kind = LocalModelKind.Asr, approxBytes = 5, license = "MIT", notes = "", languages = "",
        downloadUrl = "https://example.test/$id.bin", archiveFileName = "$id.bin", sha256 = sha,
    )

    @Test fun rows_behind_the_current_download_are_queued_until_their_turn_and_cleared_on_cancel() = runBlocking {
        val root = ModelStoreDir(File.createTempFile("velum-queue", "").apply { delete(); mkdirs() }.absolutePath)
        val payload = "synthetic".encodeToByteArray(); val sha = sha256Hex(payload)
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000")) })
        val manager = LocalModelManager(root, client, listOf(spec("first", sha), spec("second", sha), spec("third", sha)))
        val job = manager.startDownloads(listOf("first", "second", "third"))
        withTimeout(10_000) { while (manager.stateFor("first")?.status != LocalModelStatus.Downloading) delay(20) }
        assertEquals(setOf("second", "third"), manager.queuedIds.first())
        manager.cancelDownload("first")
        withTimeout(10_000) { job.join() }
        assertTrue(manager.queuedIds.first().isEmpty(), "cancelled sequence still shows queued rows: ${manager.queuedIds.first()}")
        body.close(); root.deleteRecursively()
    }

    // A failing current model: the sequence moves on, and the queue shrinks as it goes.
    @Test fun a_failing_first_model_does_not_leave_the_rest_stuck_queued() = runBlocking {
        val root = ModelStoreDir(File.createTempFile("velum-queue", "").apply { delete(); mkdirs() }.absolutePath)
        val sha = sha256Hex("synthetic".encodeToByteArray())
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("first")) respond("", HttpStatusCode.InternalServerError)
            else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000"))
        })
        val manager = LocalModelManager(root, client, listOf(spec("first", sha), spec("second", sha), spec("third", sha)))
        val job = manager.startDownloads(listOf("first", "second", "third"))
        withTimeout(10_000) { while (manager.stateFor("second")?.status != LocalModelStatus.Downloading) delay(20) }
        assertEquals(LocalModelStatus.Failed, manager.stateFor("first")?.status)
        assertEquals(setOf("third"), manager.queuedIds.first(), "only the model behind the current one is queued")
        manager.cancelDownload("second")
        withTimeout(10_000) { job.join() }
        assertTrue(manager.queuedIds.first().isEmpty())
        body.close(); root.deleteRecursively()
    }

    // Two sequences that both queue "shared": the first one ending must not hide the second one's entry.
    @Test fun overlapping_sequences_do_not_drop_each_others_queued_ids() = runBlocking {
        val root = ModelStoreDir(File.createTempFile("velum-queue", "").apply { delete(); mkdirs() }.absolutePath)
        val sha = sha256Hex("synthetic".encodeToByteArray())
        val bodyA = ByteChannel(autoFlush = true); val bodyB = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { request ->
            respond(if (request.url.encodedPath.contains("a1")) bodyA else bodyB, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000"))
        })
        val manager = LocalModelManager(root, client, listOf(spec("a1", sha), spec("b1", sha), spec("shared", sha)))
        val a = manager.startDownloads(listOf("a1", "shared"))
        withTimeout(10_000) { while (manager.stateFor("a1")?.status != LocalModelStatus.Downloading) delay(20) }
        val b = manager.startDownloads(listOf("b1", "shared"))
        withTimeout(10_000) { while (manager.stateFor("b1")?.status != LocalModelStatus.Downloading) delay(20) }
        assertTrue("shared" in manager.queuedIds.first())
        manager.cancelDownload("a1"); withTimeout(10_000) { a.join() }
        assertTrue("shared" in manager.queuedIds.first(), "sequence A ending dropped sequence B's queued entry")
        manager.cancelDownload("b1"); withTimeout(10_000) { b.join() }
        assertTrue(manager.queuedIds.first().isEmpty())
        bodyA.close(); bodyB.close(); root.deleteRecursively()
    }
}
