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
import kotlinx.coroutines.runBlocking

/** #365: what the webhook test shows on failure must not carry the webhook URL, its query string or a reply body. */
class WebhookErrorTextTest {
    private val url = "https://hooks.example.com/h/SECRETPATH?token=SECRETTOKEN"

    private fun dispatcher(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData): WebhookDispatcher {
        val settings = WebhookSettingsStore(InMemorySecureStore()).apply { runBlocking { save(url, null) } }
        val client = HttpClient(MockEngine { handler() })
        return WebhookDispatcher(VelumRepository(createDatabase(DriverFactory())), settings, client, maxAttempts = 1)
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
        assertEquals("IOException", detail)
    }

    @Test fun an_error_reply_shows_the_status_only() = runBlocking {
        val detail = dispatcher { respond("Bad request to $url", HttpStatusCode.InternalServerError) }.ping()
        assertClean(detail)
        assertEquals("HTTP 500", detail)
    }

    @Test fun a_success_reply_is_unchanged() = runBlocking {
        assertEquals("HTTP 200", dispatcher { respond("", HttpStatusCode.OK) }.ping())
    }
}
