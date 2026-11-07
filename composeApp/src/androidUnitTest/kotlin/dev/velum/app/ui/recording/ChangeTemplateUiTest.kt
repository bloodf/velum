package dev.velum.app.ui.recording

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.devicewalk.DeviceWalkMode
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.security.SecureStore
import dev.velum.core.security.SecureStoreUnavailableException
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * #103 at the screen: Change template on a recording with an existing summary, driven through the real
 * RecordingDetailScreen, container, job scheduler and RecordingProcessor. The container swaps its
 * engines for the device-walk fakes (DeviceWalkMode), so the "model" is a fake that writes the
 * template's own headings; real HTTP and real local models are not involved.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h1800dp-xxhdpi")
class ChangeTemplateUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun resetWalkMode() { DeviceWalkMode.enabled = false }

    // Buttons expose their label as a content description (clearAndSetSemantics); chips and cards as text.
    private fun nodes(label: String) = compose.onAllNodesWithText(label).fetchSemanticsNodes().size +
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size
    private fun shows(label: String) = nodes(label) > 0
    private fun awaitCond(what: String, cond: () -> Boolean) {
        try {
            compose.waitUntil(20_000, cond)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val roots = compose.onAllNodes(isRoot())
            throw AssertionError(
                "Timed out waiting for $what\n" +
                    roots.fetchSemanticsNodes().indices.drop(1).joinToString("\n====\n") { roots[it].printToString(maxDepth = 14) }.take(9000),
                e,
            )
        }
    }
    private fun await(label: String) = awaitCond("'$label'") { shows(label) }
    // Pointer injection does not reach nodes low in the tall dialog window under Robolectric, so click via semantics.
    private fun click(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
    }
    private fun tap(label: String) {
        if (compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) {
            click(compose.onAllNodesWithText(label)[0])
        } else {
            click(compose.onAllNodesWithContentDescription(label)[0])
        }
    }

    private fun fixture(
        summaryMode: ComputeMode,
        store: SecureStore = InMemorySecureStore(),
        status: String = "ready",
        setup: suspend (AppContainer) -> Unit = {},
        // false: the container keeps its real engines (a route that cannot work fails before any request is made).
        walkMode: Boolean = true,
        withApiKey: Boolean = true,
        block: (AppContainer) -> Unit,
    ) {
        DeviceWalkMode.enabled = walkMode
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-ct-ui-test").toString())
        val container = AppContainer(store, DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.settingsRepository.load()
            container.settingsRepository.setSummaryMode(summaryMode)
            if (withApiKey) container.settingsRepository.setApiKey("sk-test-not-real") // configured, but only used when Summary is on API
            container.repository.upsertTemplate(
                // Category "aaa" sorts first, so the picker (a 220dp list) shows this row without scrolling.
                id = "ui103-target", name = "ZQ Change Target", category = "aaa", description = "target",
                systemPrompt = "## ZQ Heading Alpha\n- point", builtin = true, featured = true, uses = 1_000_000,
            )
            container.repository.insertRecording(
                id = "rec", sessionId = null, folderId = null, title = "Weekly", source = "phone", deviceSerial = null,
                startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = null,
                transcript = "Speaker 1: ship on Friday.", summaryMarkdown = "## Prior heading\n- prior point", status = status,
            )
            setup(container)
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) {
                        RecordingDetailScreen(container, recordingId = "rec", onBack = {}, initialTab = 1)
                    }
                }
            }
            await("Prior heading")
            block(container)
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    private fun pickTemplateAndApply() {
        await(PtBr.changeTemplate)
        tap(PtBr.changeTemplate)
        // The sheet's mode chip is a text node; the Notes pane has a same-named button (content description only).
        awaitCond("sheet chip") { compose.onAllNodesWithText(PtBr.generateCustom).fetchSemanticsNodes().isNotEmpty() }
        val chip = compose.onAllNodes(hasText(PtBr.generateCustom) and hasClickAction())
        click(chip[chip.fetchSemanticsNodes().lastIndex])
        // Custom mode swaps the sheet heading to the same word, so a second text node appears.
        awaitCond("custom mode heading") { compose.onAllNodesWithText(PtBr.generateCustom).fetchSemanticsNodes().size >= 2 }
        await("ZQ Change Target")
        tap("ZQ Change Target")
        tap(PtBr.apply)
    }

    @Test
    fun change_template_regenerates_the_summary_with_the_picked_template_on_the_api_route() =
        fixture(ComputeMode.Api) { container ->
            pickTemplateAndApply()

            await("ZQ Heading Alpha") // the regenerated summary is on screen
            val rec = runBlocking { container.repository.getRecording("rec")!! }
            assertTrue(rec.summary_markdown.orEmpty().contains("ZQ Heading Alpha"))
            val versions = runBlocking { container.repository.getSummaryVersions("rec") }
            assertEquals("ui103-target", versions.single { it.is_active != 0L }.template_id)
            assertEquals("ready", rec.status)
        }

    @Test
    fun a_failed_generation_shows_the_failure_the_reason_and_retry_while_the_prior_summary_stays() =
        fixture(
            ComputeMode.Api,
            status = "failed",
            setup = { it.repository.setProcessingError("rec", "The API could not be reached. Check the connection.") },
        ) { container ->
            await(PtBr.ux.failedProcessing)
            assertTrue(!shows(PtBr.ux.processingFailedWithDetail.replace("%s", "The API could not be reached. Check the connection")))
            await(PtBr.ux.retryProcessing)
            assertTrue(shows("Prior heading"), "the previous summary must stay on screen")
            val rec = runBlocking { container.repository.getRecording("rec")!! }
            assertEquals("## Prior heading\n- prior point", rec.summary_markdown)
        }

    @Test
    fun an_api_route_with_no_key_fails_on_screen_with_the_reason_and_retry_and_keeps_the_prior_summary() =
        fixture(ComputeMode.Api, walkMode = false, withApiKey = false) { container ->
            pickTemplateAndApply()

            await(PtBr.ux.failedProcessing)
            await(PtBr.ux.retryProcessing)
            // The failed status renders before a separate effect finishes loading its reason.
            awaitCond("the reason is shown in the app language") {
                shows(PtBr.added.processingFailedApiKey)
            }
            assertTrue(compose.onAllNodes(hasText("API key required", substring = true)).fetchSemanticsNodes().isEmpty())
            assertTrue(shows("Prior heading"), "the previous summary must stay on screen")
            val rec = runBlocking { container.repository.getRecording("rec")!! }
            assertEquals("## Prior heading\n- prior point", rec.summary_markdown)
            assertEquals(0, runBlocking { container.repository.getSummaryVersions("rec") }.count { it.template_id == "ui103-target" })
        }

    @Test
    fun local_route_without_the_model_shows_the_prompt_and_changes_nothing() =
        fixture(ComputeMode.Local) { container ->
            pickTemplateAndApply()

            await(PtBr.localModelMissingTitle)
            assertTrue(shows(PtBr.switchToApi), "the prompt offers an API route instead of silently using one")
            val rec = runBlocking { container.repository.getRecording("rec")!! }
            assertEquals("## Prior heading\n- prior point", rec.summary_markdown, "previous summary untouched")
            assertEquals(0, runBlocking { container.repository.getSummaryVersions("rec") }.count { it.template_id == "ui103-target" })
            assertEquals("ready", rec.status)
        }

    @Test
    fun switching_to_api_from_the_missing_model_prompt_keeps_the_picked_template() =
        fixture(ComputeMode.Local) { container ->
            pickTemplateAndApply()
            await(PtBr.localModelMissingTitle)

            tap(PtBr.switchToApi)

            // Wait for the regeneration to finish, then see which template it actually used.
            compose.waitUntil(30_000) { runBlocking { container.repository.getSummaryVersions("rec") }.any { it.is_active != 0L } }
            val versions = runBlocking { container.repository.getSummaryVersions("rec") }
            assertEquals("ui103-target", versions.single { it.is_active != 0L }.template_id)
        }

    @Test
    fun a_double_tap_on_switch_to_api_runs_the_picked_template_once() =
        fixture(ComputeMode.Local) { container ->
            pickTemplateAndApply()
            await(PtBr.localModelMissingTitle)

            // Fire the button's click twice before the dialog can recompose away.
            val node = compose.onAllNodesWithText(PtBr.switchToApi)[0].fetchSemanticsNode()
            val onClick = node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            compose.runOnUiThread { onClick(); onClick() }

            compose.waitUntil(30_000) { runBlocking { container.repository.getSummaryVersions("rec") }.any { it.is_active != 0L } }
            // Give a stray second job, if there were one, time to land before counting.
            runBlocking { kotlinx.coroutines.delay(2_000) }
            val versions = runBlocking { container.repository.getSummaryVersions("rec") }
            // A leaked second tap would restart a full run (Transcription is still Local without its model),
            // which re-opens the missing-model prompt.
            assertTrue(!shows(PtBr.localModelMissingTitle), "no second prompt from a double tap")
            // The prior summary is kept as a baseline version without a template; every templated version is a job.
            assertEquals(listOf("ui103-target"), versions.mapNotNull { it.template_id }, "one job, with the picked template")
        }

    @Test
    fun diarization_prompt_has_download_and_cancel_but_no_switch_to_api() =
        fixture(
            ComputeMode.Local,
            status = "failed",
            setup = { c ->
                c.settingsRepository.setTranscriptionMode(ComputeMode.Api)
                c.settingsRepository.setSummaryMode(ComputeMode.Api)
                c.settingsRepository.setDiarizationMode(ComputeMode.Local)
            },
        ) { container ->
            tap(PtBr.ux.retryProcessing)

            await(PtBr.localModelMissingTitle)
            assertTrue(shows(PtBr.downloadModel))
            assertTrue(shows(PtBr.cancel), "an explicit way out is offered")
            assertTrue(!shows(PtBr.switchToApi), "no API route exists for diarization")
            tap(PtBr.cancel)
            compose.waitUntil(10_000) { !shows(PtBr.localModelMissingTitle) }
            assertEquals("failed", runBlocking { container.repository.getRecording("rec")!!.status })
        }

    @Test
    fun storage_failure_on_switch_to_api_is_shown_and_nothing_is_enqueued() {
        val memory = InMemorySecureStore()
        val armed = AtomicBoolean(false)
        val failing = object : SecureStore by memory {
            override suspend fun putString(key: String, value: String) {
                // setSummaryMode writes its mode key first, so this is the call that must fail.
                if (armed.get() && key == "ai.summary_mode") throw SecureStoreUnavailableException(ProviderException("synthetic"))
                memory.putString(key, value)
            }
        }
        fixture(ComputeMode.Local, store = failing) { container ->
            pickTemplateAndApply()
            await(PtBr.localModelMissingTitle)
            armed.set(true)

            tap(PtBr.switchToApi)

            await(PtBr.ux.secureStorageUnavailable)
            assertEquals(0, runBlocking { container.repository.getSummaryVersions("rec") }.count { it.template_id == "ui103-target" })
            assertEquals(ComputeMode.Local, container.settingsRepository.settings.value.summaryMode)
        }
    }
}
