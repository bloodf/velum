package dev.velum.ai.local

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** #237: a collector on the main thread must never run the download's blocking file work. */
class LocalModelDownloadThreadingTest {
    private val caller = Executors.newSingleThreadExecutor { Thread(it, "caller-main") }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "model-io") }
    private val root = ModelStoreDir(File.createTempFile("velum-model-io", "").apply { delete(); mkdirs() }.absolutePath)
    private val payload = "synthetic".encodeToByteArray()

    @AfterTest fun cleanup() {
        caller.shutdownNow(); io.shutdownNow(); root.deleteRecursively()
    }

    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val dispatches = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    private fun spec(sha: String) = LocalModelSpec(
        id = "tiny", displayName = "Tiny", kind = LocalModelKind.Asr, approxBytes = 5, license = "MIT", notes = "test",
        languages = "en", downloadUrl = "https://example.test/tiny.bin", archiveFileName = "tiny.bin", sha256 = sha,
    )

    private fun manager(client: HttpClient, dispatcher: CoroutineDispatcher = io.asCoroutineDispatcher()) =
        LocalModelManager(root, client, listOf(spec(sha256Hex(payload))), dispatcher)

    @Test fun body_runs_on_the_io_dispatcher_and_states_reach_the_collector_on_its_own_thread() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        })
        var bodyThread = ""
        // The request pipeline runs on the coroutine that calls prepareGet: the download's own body.
        client.requestPipeline.intercept(HttpRequestPipeline.Before) { bodyThread = Thread.currentThread().name }
        val manager = manager(client)
        val collectorThreads = mutableSetOf<String>()
        val statuses = mutableListOf<LocalModelStatus>()
        CoroutineScope(caller.asCoroutineDispatcher()).launch {
            manager.download("tiny").collect {
                collectorThreads += Thread.currentThread().name
                statuses += it.status
            }
        }.join()
        assertTrue(bodyThread.startsWith("model-io"), bodyThread)
        assertTrue(collectorThreads.isNotEmpty() && collectorThreads.all { it.startsWith("caller-main") }, "$collectorThreads")
        assertEquals(LocalModelStatus.Ready, statuses.last())
        assertTrue(statuses.first() == LocalModelStatus.Downloading)
    }

    @Test fun cancel_mid_transfer_cancels_the_collector_resets_state_and_removes_the_partial() = runBlocking {
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000"))
        })
        val manager = manager(client)
        val midTransfer = CompletableDeferred<Unit>()
        val job = CoroutineScope(caller.asCoroutineDispatcher()).launch {
            manager.download("tiny").collect { if (it.progress > 0f) midTransfer.complete(Unit) }
        }
        body.writeFully(payload)
        withTimeout(10_000) { midTransfer.await() }
        val partial = root.resolve("tiny").resolve("tiny.bin.partial")
        assertTrue(partial.exists())

        manager.cancelDownload("tiny")
        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled, "cancelDownload must still end the caller's coroutine")
        assertEquals(LocalModelStatus.NotDownloaded, manager.stateFor("tiny")?.status)
        assertFalse(partial.exists())
        body.close()
    }

    @Test fun failed_download_reports_failure_to_the_collector() = runBlocking {
        val client = HttpClient(MockEngine { respond("nope", HttpStatusCode.InternalServerError) })
        val states = manager(client).download("tiny").toList()
        assertEquals(LocalModelStatus.Failed, states.last().status)
    }

    @Test fun delete_runs_on_the_io_dispatcher() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        })
        val counting = CountingDispatcher(io.asCoroutineDispatcher())
        val manager = manager(client, counting)
        root.resolve("tiny").createDirectories()
        root.resolve("tiny").resolve("tiny.bin").writeBytes(payload)
        val before = counting.dispatches.get()
        manager.delete("tiny")
        assertTrue(counting.dispatches.get() > before, "delete did not leave the caller's dispatcher")
        assertFalse(root.resolve("tiny").exists())
    }
}
