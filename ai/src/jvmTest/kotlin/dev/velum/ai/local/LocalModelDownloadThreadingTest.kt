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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
        id = "tiny", displayName = "Tiny", kind = LocalModelKind.Llm, approxBytes = 5, license = "MIT", notes = "test",
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

    private fun archiveManager(client: HttpClient, extract: (ModelStoreDir, ModelStoreDir) -> Unit) = LocalModelManager(
        root,
        client,
        listOf(spec(sha256Hex(payload)).copy(archiveFileName = "tiny.tar.gz")),
        io.asCoroutineDispatcher(),
        extract,
    )

    @Test fun extraction_runs_on_the_io_dispatcher_not_the_collectors_thread() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        })
        var extractThread = ""
        val manager = archiveManager(client) { _, dir ->
            extractThread = Thread.currentThread().name
            dir.resolve("weights.litertlm").writeBytes(ByteArray(2048))
        }
        CoroutineScope(caller.asCoroutineDispatcher()).launch { manager.download("tiny").collect() }.join()
        assertTrue(extractThread.startsWith("model-io"), "extraction ran on '$extractThread'")
    }

    @Test fun cancel_during_extraction_leaves_nothing_a_refresh_could_call_ready() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        })
        val extracting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = archiveManager(client) { _, dir ->
            dir.resolve("weights.litertlm").writeBytes(ByteArray(2048))
            extracting.countDown()
            // Extraction can't be interrupted: like the real one, this finishes whatever cancel does.
            assertTrue(release.await(10, TimeUnit.SECONDS))
        }
        val job = CoroutineScope(caller.asCoroutineDispatcher()).launch { manager.download("tiny").collect() }
        assertTrue(extracting.await(10, TimeUnit.SECONDS))

        manager.cancelDownload("tiny")
        release.countDown()
        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled)
        assertEquals(LocalModelStatus.NotDownloaded, manager.stateFor("tiny")?.status)
        assertFalse(root.resolve("tiny").exists(), "archive and extracted files must not survive a cancel")
        manager.refreshFromDisk().join()
        assertEquals(LocalModelStatus.NotDownloaded, manager.stateFor("tiny")?.status)
    }

    @Test fun failed_extraction_leaves_nothing_a_later_launch_could_call_ready() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        })
        val manager = archiveManager(client) { _, dir ->
            dir.resolve("weights.litertlm").writeBytes(ByteArray(2048))
            throw java.io.IOException("synthetic: no space left")
        }
        manager.download("tiny").collect()
        assertEquals(LocalModelStatus.Failed, manager.stateFor("tiny")?.status)
        assertFalse(root.resolve("tiny").exists(), "leftovers must not survive a failed extraction")
        manager.refreshFromDisk().join()
        assertEquals(LocalModelStatus.NotDownloaded, manager.stateFor("tiny")?.status)
    }

    @Test fun an_archive_that_extracts_to_nothing_useful_is_incomplete_and_leaves_nothing() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        })
        val manager = archiveManager(client) { _, _ -> } // extracts nothing, so the required files are missing
        manager.download("tiny").collect()
        assertEquals(LocalModelStatus.Failed, manager.stateFor("tiny")?.status)
        assertEquals(LocalModelErrors.Incomplete, manager.stateFor("tiny")?.error)
        assertFalse(root.resolve("tiny").exists(), "nothing a refresh could call Ready may stay")
    }

    @Test fun a_junk_file_in_the_model_folder_is_incomplete_after_a_restart() = runBlocking {
        val dir = root.resolve("tiny")
        dir.createDirectories()
        dir.resolve("junk.bin").writeBytes(ByteArray(8))
        val manager = archiveManager(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })) { _, _ -> }
        manager.refreshFromDisk().join()
        assertEquals(LocalModelStatus.Failed, manager.stateFor("tiny")?.status)
        assertEquals(LocalModelErrors.Incomplete, manager.stateFor("tiny")?.error)
    }

    @Test fun cancel_at_the_final_emit_keeps_the_complete_model() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        })
        val manager = manager(client)
        // Unconfined: runs inside the state update, so the cancel lands before the emit that follows it.
        val canceller = CoroutineScope(Dispatchers.Unconfined).launch {
            manager.states.first { list -> list.single().status == LocalModelStatus.Ready }
            manager.cancelDownload("tiny")
        }
        val job = CoroutineScope(caller.asCoroutineDispatcher()).launch { manager.download("tiny").collect() }
        withTimeout(10_000) { job.join() }
        canceller.cancel()
        assertEquals(LocalModelStatus.Ready, manager.stateFor("tiny")?.status)
        assertTrue(root.resolve("tiny").resolve("tiny.bin").exists(), "a finished download must not be thrown away")
    }

    @Test fun a_started_download_outlives_its_caller_and_can_still_be_cancelled() = runBlocking {
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString())) })
        val manager = manager(client)
        val screen = CoroutineScope(caller.asCoroutineDispatcher())
        var started: kotlinx.coroutines.Job? = null
        screen.launch { started = manager.startDownloads(listOf("tiny")) }.join()
        withTimeout(10_000) { while (manager.stateFor("tiny")?.status != LocalModelStatus.Downloading) delay(5) }

        screen.coroutineContext[kotlinx.coroutines.Job]?.cancel() // leaving the screen
        body.writeFully(payload)
        body.close()
        withTimeout(10_000) { started!!.join() }
        assertEquals(LocalModelStatus.Ready, manager.stateFor("tiny")?.status)
    }

    @Test fun cancel_on_the_first_downloading_state_restores_missing_state() = runBlocking {
        val client = HttpClient(MockEngine { error("cancel must land before the request") })
        val manager = manager(client)
        manager.awaitInitialization()
        val canceller = CoroutineScope(Dispatchers.Unconfined).launch {
            manager.states.first { it.single().status == LocalModelStatus.Downloading }
            manager.cancelDownload("tiny")
        }
        val job = manager.startDownloads(listOf("tiny"))
        withTimeout(10_000) { job.join() }
        canceller.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(LocalModelStatus.NotDownloaded, manager.stateFor("tiny")?.status)
        assertFalse(root.resolve("tiny").exists())
    }

    @Test fun cancel_ends_a_started_download() = runBlocking {
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000")) })
        val manager = manager(client)
        val job = manager.startDownloads(listOf("tiny"))
        withTimeout(10_000) { while (manager.stateFor("tiny")?.status != LocalModelStatus.Downloading) delay(5) }
        manager.cancelDownload("tiny")
        withTimeout(10_000) { job.join() }
        assertEquals(LocalModelStatus.NotDownloaded, manager.stateFor("tiny")?.status)
        body.close()
    }

    @Test fun cancel_during_a_transfer_keeps_a_model_already_on_disk() = runBlocking {
        root.resolve("tiny").createDirectories()
        root.resolve("tiny").resolve("old.litertlm").writeBytes(ByteArray(2048))
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000")) })
        val manager = manager(client)
        val midTransfer = CompletableDeferred<Unit>()
        val job = CoroutineScope(caller.asCoroutineDispatcher()).launch {
            manager.download("tiny").collect { if (it.progress > 0f) midTransfer.complete(Unit) }
        }
        body.writeFully(payload)
        withTimeout(10_000) { midTransfer.await() }
        manager.cancelDownload("tiny")
        withTimeout(10_000) { job.join() }
        assertTrue(root.resolve("tiny").resolve("old.litertlm").exists())
        body.close()
    }

    @Test fun delete_finishes_even_if_the_caller_is_cancelled_first() = runBlocking {
        val client = HttpClient(MockEngine { respond("") })
        var held = CountDownLatch(0)
        val gate = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                io.execute { held.await(10, TimeUnit.SECONDS); block.run() }
            }
        }
        val manager = manager(client, gate)
        root.resolve("tiny").createDirectories()
        root.resolve("tiny").resolve("tiny.bin").writeBytes(payload)
        manager.refreshFromDisk().join()
        assertEquals(LocalModelStatus.Ready, manager.stateFor("tiny")?.status)
        held = CountDownLatch(1)

        val job = CoroutineScope(caller.asCoroutineDispatcher()).launch(start = CoroutineStart.UNDISPATCHED) { manager.delete("tiny") }
        job.cancel()
        held.countDown()
        withTimeout(10_000) { while (manager.stateFor("tiny")?.status == LocalModelStatus.Ready || root.resolve("tiny").exists()) delay(5) }

        assertEquals(LocalModelStatus.NotDownloaded, manager.stateFor("tiny")?.status)
        assertFalse(root.resolve("tiny").exists())
    }

    @Test fun a_second_download_of_the_same_model_follows_the_first_and_never_starts_another_transfer() = runBlocking {
        val requests = AtomicInteger()
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine {
            requests.incrementAndGet()
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString()))
        })
        val manager = manager(client)
        val started = CompletableDeferred<Unit>()
        val first = CoroutineScope(caller.asCoroutineDispatcher()).launch {
            manager.download("tiny").collect { started.complete(Unit) }
        }
        withTimeout(10_000) { started.await(); while (requests.get() == 0) delay(5) }
        var followerLast: LocalModelState? = null
        val follower = CoroutineScope(caller.asCoroutineDispatcher()).launch {
            manager.download("tiny").collect { followerLast = it }
        }
        withTimeout(10_000) { while (followerLast == null) delay(5) }
        assertFalse(follower.isCompleted, "the follower waits for the running download")

        body.writeFully(payload)
        body.close()
        withTimeout(10_000) { first.join(); follower.join() }

        assertEquals(1, requests.get())
        assertEquals(LocalModelStatus.Ready, followerLast?.status)
    }

    @Test fun cancelling_a_follower_leaves_the_running_download_alone() = runBlocking {
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, payload.size.toString())) })
        val manager = manager(client)
        val started = CompletableDeferred<Unit>()
        val first = CoroutineScope(caller.asCoroutineDispatcher()).launch { manager.download("tiny").collect { started.complete(Unit) } }
        withTimeout(10_000) { started.await() }
        val follower = CoroutineScope(caller.asCoroutineDispatcher()).launch { manager.download("tiny").collect() }
        delay(50)
        follower.cancelAndJoin()
        assertFalse(first.isCancelled)
        body.writeFully(payload)
        body.close()
        withTimeout(10_000) { first.join() }
        assertEquals(LocalModelStatus.Ready, manager.stateFor("tiny")?.status)
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
