package dev.velum.ai.integrations

import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.integrations.NotionSettingsStore
import dev.velum.core.integrations.SlackSettingsStore
import dev.velum.core.security.InMemorySecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NoteDestinationsClientTest {
    private val hook = "https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXXXXXX"
    private val token = "ntn_abcdefghijklmnopqrstu"
    private val db = "0123abcd-4567-89ab-cdef-0123456789ab"
    private val requests = mutableListOf<HttpRequestData>()

    private fun body(r: HttpRequestData) = (r.body as TextContent).text
    private fun json(r: HttpRequestData) = Json.parseToJsonElement(body(r)).jsonObject

    private suspend fun repo(transcriptLines: Int = 2): VelumRepository =
        VelumRepository(createDatabase(DriverFactory())).also {
            it.insertRecording(
                id = "rec", sessionId = null, folderId = null, title = "Weekly sync", source = "phone",
                deviceSerial = null, startedAt = 1_763_800_000L, durationSeconds = 1_800, sizeBytes = 0, localPath = null,
                transcript = (1..transcriptLines).joinToString("\n\n") { n -> "**Speaker 1** · 0:$n\nline $n" } +
                    "\n\n## Markers\n- **0:05** private marker",
                summaryMarkdown = "## Decisions\n- **Ship** the beta", status = "ready",
            )
        }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { request -> requests += request; handler(request) })

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // ---- Slack

    @Test fun slack_posts_the_summary_to_the_saved_webhook() = runTest {
        val settings = SlackSettingsStore(InMemorySecureStore()).also { it.save(hook) }
        val poster = SlackPoster(repo(), settings, client { respond("ok", HttpStatusCode.OK) })
        assertEquals(DestinationResult.Sent(), poster.post("rec"))
        val request = requests.single()
        assertEquals(hook, request.url.toString())
        val payload = json(request)
        assertEquals("Weekly sync", payload["text"]!!.jsonPrimitive.content)
        assertTrue(body(request).contains("*Ship* the beta"), body(request))
        assertTrue(!body(request).contains("private marker"), "markers and transcript are not posted")
    }

    @Test fun slack_without_a_webhook_or_recording_sends_nothing() = runTest {
        val empty = SlackPoster(repo(), SlackSettingsStore(InMemorySecureStore()), client { error("no request expected") })
        assertEquals(DestinationResult.NotConfigured, empty.post("rec"))
        val configured = SlackPoster(repo(), SlackSettingsStore(InMemorySecureStore()).also { it.save(hook) }, client { error("no request") })
        assertEquals(DestinationResult.NotConfigured, configured.post("missing"))
        assertTrue(requests.isEmpty())
    }

    @Test fun slack_client_errors_are_not_retried_server_errors_are() = runTest {
        val settings = SlackSettingsStore(InMemorySecureStore()).also { it.save(hook) }
        val gone = SlackPoster(repo(), settings, client { respond("no_service", HttpStatusCode.NotFound) })
        assertEquals(DestinationResult.Failed("HTTP 404"), gone.post("rec"))
        assertEquals(1, requests.size)
        requests.clear()
        val flaky = SlackPoster(repo(), settings, client { respond("oops", HttpStatusCode.BadGateway) })
        assertEquals(DestinationResult.Failed("HTTP 502"), flaky.post("rec"))
        assertEquals(3, requests.size)
    }

    @Test fun slack_network_failures_become_codes_not_messages() = runTest {
        val settings = SlackSettingsStore(InMemorySecureStore()).also { it.save(hook) }
        val poster = SlackPoster(repo(), settings, client { throw IOException("connect to $hook failed") })
        assertEquals(DestinationResult.Failed(WebhookFailure.CONNECTION), poster.post("rec"))
    }

    @Test fun slack_test_message_needs_no_recording() = runTest {
        val settings = SlackSettingsStore(InMemorySecureStore()).also { it.save(hook) }
        val poster = SlackPoster(repo(), settings, client { respond("ok", HttpStatusCode.OK) })
        assertEquals(DestinationResult.Sent(), poster.test("Velum is connected"))
        assertEquals("Velum is connected", json(requests.single())["text"]!!.jsonPrimitive.content)
    }

    // ---- Notion

    private val schema = """{"object":"database","properties":{
        "Name":{"id":"title","type":"title","title":{}},
        "When":{"id":"a","type":"date","date":{}},
        "Tags":{"id":"b","type":"multi_select","multi_select":{}}}}"""

    private suspend fun notionSettings() = NotionSettingsStore(InMemorySecureStore()).also { it.save(token, db) }

    @Test fun notion_creates_a_page_with_title_date_summary_and_transcript() = runTest {
        val exporter = NotionExporter(repo(), notionSettings(), client { request ->
            when {
                request.method == HttpMethod.Get -> respond(schema, HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"id":"page-1","url":"https://www.notion.so/page-1"}""", HttpStatusCode.OK, jsonHeaders)
            }
        })
        assertEquals(DestinationResult.Sent("https://www.notion.so/page-1"), exporter.export("rec", DestinationLabels("Resumo", "Transcrição")))
        val (get, post) = requests
        assertEquals("https://api.notion.com/v1/databases/$db", get.url.toString())
        assertEquals("Bearer $token", get.headers[HttpHeaders.Authorization])
        assertEquals(NotionExporter.NOTION_VERSION, post.headers["Notion-Version"])
        val page = json(post)
        assertEquals(db, page["parent"]!!.jsonObject["database_id"]!!.jsonPrimitive.content)
        val props = page["properties"]!!.jsonObject
        assertEquals("Weekly sync", props["Name"]!!.jsonObject["title"]!!.jsonArray[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("2025-11-22T08:26:40Z", props["When"]!!.jsonObject["date"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        val text = body(post)
        assertTrue(text.contains("Resumo") && text.contains("Transcrição") && text.contains("line 2"), text)
        assertTrue(!text.contains("private marker"))
    }

    @Test fun notion_appends_blocks_beyond_the_first_hundred() = runTest {
        val exporter = NotionExporter(repo(transcriptLines = 250), notionSettings(), client { request ->
            when (request.method) {
                HttpMethod.Get -> respond(schema, HttpStatusCode.OK, jsonHeaders)
                HttpMethod.Post -> respond("""{"id":"page-1","url":"u"}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"object":"list"}""", HttpStatusCode.OK, jsonHeaders)
            }
        })
        assertIs<DestinationResult.Sent>(exporter.export("rec"))
        val patches = requests.filter { it.method == HttpMethod.Patch }
        assertTrue(patches.isNotEmpty())
        patches.forEach {
            assertEquals("https://api.notion.com/v1/blocks/page-1/children", it.url.toString())
            assertTrue(json(it)["children"]!!.jsonArray.size <= 100)
        }
        val total = json(requests.first { it.method == HttpMethod.Post })["children"]!!.jsonArray.size +
            patches.sumOf { json(it)["children"]!!.jsonArray.size }
        assertTrue(total > 250, "every transcript block arrives ($total)")
    }

    @Test fun notion_rate_limits_are_retried_after_the_requested_wait() = runTest {
        var gets = 0
        val exporter = NotionExporter(repo(), notionSettings(), client { request ->
            if (request.method == HttpMethod.Get && gets++ == 0) {
                respond("""{"code":"rate_limited"}""", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
            } else if (request.method == HttpMethod.Get) {
                respond(schema, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("""{"id":"p","url":"u"}""", HttpStatusCode.OK, jsonHeaders)
            }
        })
        assertIs<DestinationResult.Sent>(exporter.export("rec"))
        assertEquals(2, gets)
    }

    @Test fun notion_failures_are_codes() = runTest {
        val unshared = NotionExporter(repo(), notionSettings(), client { respond("""{"code":"object_not_found"}""", HttpStatusCode.NotFound, jsonHeaders) })
        assertEquals(DestinationResult.Failed("HTTP 404"), unshared.export("rec"))
        assertEquals(DestinationResult.Failed("HTTP 404"), unshared.test())
        val notDatabase = NotionExporter(repo(), notionSettings(), client { respond("""{"object":"database","properties":{}}""", HttpStatusCode.OK, jsonHeaders) })
        assertEquals(DestinationResult.Failed(NotionExporter.NOT_A_DATABASE), notDatabase.export("rec"))
        val notConfigured = NotionExporter(repo(), NotionSettingsStore(InMemorySecureStore()), client { error("no request") })
        assertEquals(DestinationResult.NotConfigured, notConfigured.export("rec"))
    }
}
