package dev.velum.ai.integrations

import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.WipeGate
import dev.velum.core.data.createDatabase
import dev.velum.core.integrations.WebhookSettingsStore
import dev.velum.core.security.InMemorySecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class WebhookWipeTest {
    @Test fun no_delivery_is_sent_while_a_wipe_runs() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "rec-webhook", sessionId = null, folderId = null, title = "Synthetic", source = "phone",
            deviceSerial = null, startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = "synthetic transcript", summaryMarkdown = "## Synthetic", status = "ready",
        )
        val settings = WebhookSettingsStore(InMemorySecureStore()).apply { save("https://example.test/hook", null) }
        val posts = AtomicInteger()
        val client = HttpClient(MockEngine { posts.incrementAndGet(); respond("", HttpStatusCode.OK) })
        val gate = WipeGate()
        val dispatcher = WebhookDispatcher(repo, settings, client, wipeGate = gate)

        gate.wiping(1.seconds) { assertNull(dispatcher.dispatch("rec-webhook")) }

        assertEquals(0, posts.get(), "a webhook was delivered during the wipe")
    }

    @Test fun a_wipe_stops_a_delivery_between_retries() = runBlocking {
        val repo = VelumRepository(createDatabase(DriverFactory()))
        repo.insertRecording(
            id = "rec-webhook", sessionId = null, folderId = null, title = "Synthetic", source = "phone",
            deviceSerial = null, startedAt = 1_700_000_000L, durationSeconds = 60, sizeBytes = 0, localPath = null,
            transcript = "synthetic transcript", summaryMarkdown = "## Synthetic", status = "ready",
        )
        val settings = WebhookSettingsStore(InMemorySecureStore()).apply { save("https://example.test/hook", null) }
        val posts = AtomicInteger()
        val firstPost = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            posts.incrementAndGet()
            firstPost.complete(Unit)
            respond("", HttpStatusCode.InternalServerError)
        })
        val gate = WipeGate()
        val dispatcher = WebhookDispatcher(repo, settings, client, wipeGate = gate)
        val delivery = async(Dispatchers.Default) { dispatcher.dispatch("rec-webhook") }
        firstPost.await()

        assertTrue(gate.wiping(5.seconds) { it }, "the wipe did not stop the delivery")

        assertNull(delivery.await())
        assertEquals(1, posts.get(), "a retry was delivered after the wipe began")
    }
}
