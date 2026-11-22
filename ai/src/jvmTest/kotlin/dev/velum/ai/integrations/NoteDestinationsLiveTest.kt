package dev.velum.ai.integrations

import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import dev.velum.core.integrations.NotionSettingsStore
import dev.velum.core.integrations.SlackSettingsStore
import dev.velum.core.security.InMemorySecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Talks to the real Slack and Notion APIs with the production clients. Skipped unless the owner provides throwaway
 * credentials (see docs/SLACK_NOTION.md, "Check against the real services"):
 *
 * ```
 * VELUM_LIVE_SLACK_WEBHOOK=https://hooks.slack.com/services/... \
 * VELUM_LIVE_NOTION_TOKEN=ntn_... VELUM_LIVE_NOTION_DATABASE=https://www.notion.so/... \
 *   ./gradlew :ai:jvmTest --tests '*NoteDestinationsLiveTest*'
 * ```
 *
 * Each run posts one Slack message and creates one Notion page titled "Velum live check"; delete them afterwards.
 */
class NoteDestinationsLiveTest {
    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun repository(): VelumRepository =
        VelumRepository(createDatabase(DriverFactory())).also { repo ->
            // Long enough to need Notion's follow-up append requests (more than 100 blocks).
            val transcript = (1..140).joinToString("\n\n") { n -> "**Speaker ${n % 3 + 1}** · ${n / 60}:${(n % 60).toString().padStart(2, '0')}\nLine $n of the live check, with *some* **formatting** and a [link](https://example.com)." }
            repo.insertRecording(
                id = "live", sessionId = null, folderId = null, title = "Velum live check", source = "phone",
                deviceSerial = null, startedAt = System.currentTimeMillis() / 1000, durationSeconds = 1_260, sizeBytes = 0,
                localPath = null, transcript = transcript,
                summaryMarkdown = """
                    ## Summary
                    A synthetic note sent by `NoteDestinationsLiveTest`.

                    ## Decisions
                    - **Ship** the beta on Friday
                    - Keep ~~two~~ one release channel

                    ## Action items
                    - [ ] Delete this message / page — **you**
                    - [x] Run the live check
                """.trimIndent(),
                status = "ready",
            )
        }

    @Test fun slack_incoming_webhook_accepts_the_test_message_and_a_note() = runBlocking {
        val hook = env("VELUM_LIVE_SLACK_WEBHOOK")
        assumeTrue("VELUM_LIVE_SLACK_WEBHOOK not set", hook != null)
        val settings = SlackSettingsStore(InMemorySecureStore()).also { it.save(hook!!) }
        val poster = SlackPoster(repository(), settings)
        assertEquals(DestinationResult.Sent(), poster.test("Velum live check: test message"))
        assertEquals(DestinationResult.Sent(), poster.post("live"))
    }

    @Test fun notion_database_accepts_a_long_page() = runBlocking {
        val token = env("VELUM_LIVE_NOTION_TOKEN")
        val database = env("VELUM_LIVE_NOTION_DATABASE")
        assumeTrue("VELUM_LIVE_NOTION_TOKEN / VELUM_LIVE_NOTION_DATABASE not set", token != null && database != null)
        val settings = NotionSettingsStore(InMemorySecureStore()).also { it.save(token!!, database!!) }
        val exporter = NotionExporter(repository(), settings)
        assertEquals(DestinationResult.Sent(), exporter.test())
        val sent = assertIs<DestinationResult.Sent>(exporter.export("live"))
        assertTrue(sent.url?.startsWith("https://www.notion.so/") == true, "page URL: ${sent.url}")
    }
}
