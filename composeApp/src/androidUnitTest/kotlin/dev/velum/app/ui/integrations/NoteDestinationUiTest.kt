package dev.velum.app.ui.integrations

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.installAppContext
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Slack / Notion cards in Integrations and the recording's "Post to Slack" / "Export to Notion" (#59, #61). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Tall enough for a whole card or the export sheet: no scrolling needed to reach any control.
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w411dp-h1600dp")
class NoteDestinationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val d = En.added.destinations
    private val hook = "https://hooks.slack.com/services/T0SYNTH/B0SYNTH/syntheticsecret"
    private val token = "ntn_synthetic_token_0123456789"
    private val databaseLink = "https://www.notion.so/acme/Meetings-0123456789abcdef0123456789abcdef?v=1"
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    /** Slack answers "ok"; Notion answers [notionStatus] for the database and creates pages. */
    private var notionStatus = HttpStatusCode.OK
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val http = HttpClient(
        MockEngine { request ->
            requests += request
            when {
                request.url.host == "hooks.slack.com" -> respond("ok", HttpStatusCode.OK)
                request.url.encodedPath.startsWith("/v1/databases/") ->
                    respond("""{"object":"database","properties":{"Name":{"type":"title"},"Date":{"type":"date"}}}""", notionStatus, jsonHeaders)
                request.url.encodedPath == "/v1/pages" && request.method == HttpMethod.Post ->
                    respond("""{"id":"page-1","url":"https://www.notion.so/page-1"}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        },
    )

    private lateinit var container: AppContainer
    private lateinit var root: ModelStoreDir
    private var activity: ActivityController<ComponentActivity>? = null

    private fun show(content: @Composable () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        installAppContext(context)
        root = ModelStoreDir(Files.createTempDirectory("velum-destinations-ui").toString())
        container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root, noteDestinationHttpClient = http)
        runBlocking { container.settingsRepository.load() }
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().also {
            it.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
    }

    @After fun tearDown() {
        activity?.pause()?.stop()?.destroy()
        if (::container.isInitialized) container.databaseHandle.driver.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun body(r: HttpRequestData) = (r.body as TextContent).text

    private fun click(text: String) = compose.onNodeWithText(text).performClick()

    /** BlackButton / OutlineButton expose their label as the content description. */
    private fun button(label: String) = compose.onNodeWithContentDescription(label).performClick()

    private fun seeText(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    /** Results that arrive after storage or HTTP work finishes on another dispatcher. */
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        seeText(text)
    }

    // ---- Slack card

    @Test fun slack_card_validates_saves_without_showing_the_url_tests_and_removes() {
        show { SlackCard(container) }
        seeText(En.oauthDisconnected)

        compose.onNode(hasSetTextAction()).performTextInput("https://example.com/hook")
        button(En.save)
        awaitText(d.slackInvalidUrl)
        compose.runOnIdle { assertFalse(runBlocking { container.slackSettings.isConfigured() }) }

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(hook)
        button(En.save)
        awaitText(d.slackSaved)
        seeText(En.oauthConnected)
        seeText(d.slackUrlSavedHint)
        compose.runOnIdle { assertEquals(hook, runBlocking { container.slackSettings.getUrl() }) }
        // The saved webhook is a credential: the field is emptied and the URL is never shown again.
        assertEquals(0, compose.onAllNodes(hasText("syntheticsecret", substring = true)).fetchSemanticsNodes().size)

        button(d.slackTest)
        awaitText(d.slackTestOk)
        compose.runOnIdle {
            val sent = requests.single()
            assertEquals(hook, sent.url.toString())
            assertTrue(d.slackTestMessage in body(sent), body(sent))
        }

        click(d.slackClear)
        seeText(d.slackClearBody)
        click(En.cancel)
        compose.runOnIdle { assertTrue(runBlocking { container.slackSettings.isConfigured() }) }
        click(d.slackClear)
        click(En.confirm)
        awaitText(d.slackCleared)
        seeText(En.oauthDisconnected)
        compose.runOnIdle { assertFalse(runBlocking { container.slackSettings.isConfigured() }) }
    }

    // ---- Notion card

    @Test fun notion_card_checks_token_and_database_masks_the_token_and_connects() {
        show { NotionCard(container) }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

        fields[0].performTextInput("not-a-token-at-all-really")
        fields[1].performTextInput(databaseLink)
        button(En.save)
        awaitText(d.notionInvalidToken)

        fields[0].performTextClearance()
        fields[0].performTextInput(token)
        fields[1].performTextClearance()
        fields[1].performTextInput("https://www.notion.so/acme/no-id-here")
        button(En.save)
        awaitText(d.notionInvalidDatabase)
        assertTrue(requests.isEmpty(), "nothing is sent before both fields are valid")

        fields[1].performTextClearance()
        fields[1].performTextInput(databaseLink)
        button(En.save)
        awaitText(d.notionSaved + " " + d.notionTestOk)
        seeText(En.oauthConnected)
        compose.runOnIdle {
            val check = requests.single()
            assertEquals("/v1/databases/01234567-89ab-cdef-0123-456789abcdef", check.url.encodedPath)
            assertEquals("Bearer $token", check.headers[HttpHeaders.Authorization])
        }
        assertEquals(0, compose.onAllNodes(hasText(token, substring = true)).fetchSemanticsNodes().size)

        click(d.notionClear)
        click(En.confirm)
        awaitText(d.notionCleared)
        compose.runOnIdle { assertFalse(runBlocking { container.notionSettings.isConfigured() }) }
    }

    @Test fun notion_card_explains_a_database_that_is_not_shared_with_the_integration() {
        notionStatus = HttpStatusCode.NotFound
        show { NotionCard(container) }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(token)
        fields[1].performTextInput(databaseLink)
        button(En.save)
        awaitText(d.notionNotShared)
    }

    // ---- Recording: Post to Slack / Export to Notion

    private fun seedRecording() = runBlocking {
        container.repository.insertRecording(
            id = "rec-share", sessionId = null, folderId = null, title = "Weekly sync", source = "phone",
            deviceSerial = null, startedAt = 1_763_800_000L, durationSeconds = 1_800, sizeBytes = 0, localPath = null,
            transcript = "**Speaker 1** · 0:01\nHello.", summaryMarkdown = "## Decisions\n- Ship the beta", status = "ready",
        )
    }

    @Test fun recording_export_offers_slack_and_notion_only_when_set_up() {
        show { RecordingDetailScreen(container, "rec-share", onBack = {}) }
        seedRecording()
        compose.onNodeWithContentDescription(En.share).performClick()
        seeText(En.ux.exportMarkdown)
        assertEquals(0, compose.onAllNodesWithText(d.postToSlack).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText(d.exportToNotion).fetchSemanticsNodes().size)
    }

    @Test fun posting_to_slack_asks_first_and_reports_the_result() {
        show { RecordingDetailScreen(container, "rec-share", onBack = {}) }
        seedRecording()
        runBlocking { container.slackSettings.save(hook) }
        compose.onNodeWithContentDescription(En.share).performClick()
        click(d.postToSlack)
        seeText(d.postToSlackConfirmBody)
        click(En.cancel)
        compose.runOnIdle { assertTrue(requests.isEmpty(), "cancel sends nothing") }

        compose.onNodeWithContentDescription(En.share).performClick()
        click(d.postToSlack)
        click(En.confirm)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(d.slackPosted).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val sent = requests.single()
            assertEquals(hook, sent.url.toString())
            assertTrue("Weekly sync" in body(sent) && "Ship the beta" in body(sent), body(sent))
        }
    }

    @Test fun exporting_to_notion_creates_a_page_after_confirmation() {
        show { RecordingDetailScreen(container, "rec-share", onBack = {}) }
        seedRecording()
        runBlocking { container.notionSettings.save(token, databaseLink) }
        compose.onNodeWithContentDescription(En.share).performClick()
        click(d.exportToNotion)
        seeText(d.exportToNotionConfirmBody)
        click(En.confirm)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(d.notionExported).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val page = requests.single { it.url.encodedPath == "/v1/pages" }
            assertTrue("Weekly sync" in body(page) && "Ship the beta" in body(page), body(page))
        }
    }
}
