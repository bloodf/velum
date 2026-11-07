package dev.velum.ai.integrations

import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.integrations.WebhookSettingsStore
import dev.velum.core.security.InMemorySecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** #365: what the webhook test shows on failure must not carry the webhook URL, its query string or a reply body. */
class WebhookErrorTextTest {
    private val url = "https://hooks.example.com/h/SECRETPATH?token=SECRETTOKEN"

    private fun dispatcher(
        maxAttempts: Int = 1,
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData,
    ): WebhookDispatcher {
        val settings = WebhookSettingsStore(InMemorySecureStore()).apply { runBlocking { save(url, null) } }
        val client = HttpClient(MockEngine { handler() })
        return WebhookDispatcher(VelumRepository(createDatabase(DriverFactory())), settings, client, maxAttempts = maxAttempts)
    }

    private fun assertClean(detail: String?) {
        for (secret in listOf("SECRETPATH", "SECRETTOKEN", "hooks.example.com", "https://")) {
            assertFalse(detail.orEmpty().contains(secret), "'$detail' leaks $secret")
        }
    }

    @Test fun a_failing_connection_shows_no_url() = runBlocking {
        val detail = dispatcher {
            throw java.io.IOException("Connect timeout has expired [url=$url, connect_timeout=1000 ms]")
        }.ping()
        assertClean(detail)
        assertEquals("connection", detail)
    }

    @Test fun an_error_reply_shows_the_status_only() = runBlocking {
        val detail = dispatcher { respond("Bad request to $url", HttpStatusCode.InternalServerError) }.ping()
        assertClean(detail)
        assertEquals("HTTP 500", detail)
    }

    @Test fun a_success_reply_is_unchanged() = runBlocking {
        assertEquals("HTTP 200", dispatcher { respond("", HttpStatusCode.OK) }.ping())
    }

    @Test fun failures_map_to_stable_codes() = runBlocking {
        assertEquals("timeout", dispatcher { throw io.ktor.client.plugins.HttpRequestTimeoutException(url, 1_000) }.ping())
        assertEquals("timeout", dispatcher { throw java.net.SocketTimeoutException("Read timed out") }.ping())
        assertEquals("unresolved_host", dispatcher { throw java.net.UnknownHostException("hooks.example.com") }.ping())
        assertEquals("tls", dispatcher { throw javax.net.ssl.SSLHandshakeException("PKIX path building failed") }.ping())
        assertEquals("connection", dispatcher { throw java.net.ConnectException("Connection refused") }.ping())
    }

    @Test fun the_cause_chain_decides_the_code() = runBlocking {
        val detail = dispatcher { throw RuntimeException("wrapped $url", java.net.UnknownHostException("x")) }.ping()
        assertEquals("unresolved_host", detail)
        assertClean(detail)
    }

    @Test fun cancellation_is_rethrown_not_turned_into_a_failure() {
        val result = runCatching { runBlocking { dispatcher { throw kotlinx.coroutines.CancellationException("stop") }.ping() } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException, "got $result")
    }

    @Test fun a_later_attempt_can_succeed() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val detail = dispatcher(maxAttempts = 3) {
            if (calls.incrementAndGet() < 3) throw java.io.IOException("down $url") else respond("", HttpStatusCode.OK)
        }.ping()
        assertEquals("HTTP 200", detail)
        assertEquals(3, calls.get())
    }

    @Test fun every_attempt_is_made_and_the_last_failure_is_reported() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val detail = dispatcher(maxAttempts = 3) {
            when (calls.incrementAndGet()) {
                1 -> throw java.io.IOException("down")
                2 -> respond("busy", HttpStatusCode.ServiceUnavailable)
                else -> throw java.net.UnknownHostException("gone")
            }
        }.ping()
        assertEquals(3, calls.get())
        assertEquals("unresolved_host", detail)
    }
}
