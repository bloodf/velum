package dev.velum.app.devicewalk

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.model.ComputeMode
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #103 on a device, through the real RecordingDetailScreen: Change template, Custom, pick a template,
 * Apply; the regenerated summary appears, the previous summary stays as a version with its template,
 * and after a cold restart (database driver closed, a new container opened on the same file) both
 * versions, their template ids and the active summary are still there and the screen shows them.
 *
 * Synthetic data, device-walk fake engines (no network, no model). Uses its own database file so it
 * never touches the app's velum.db. Requires runner arg `velumDeviceWalk=true`.
 */
@RunWith(AndroidJUnit4::class)
class ChangeTemplateRestartDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var modelRoot: File
    private val open = mutableListOf<AppContainer>()

    @Before
    fun setUp() {
        assertTrue(DeviceWalkMode.isEnabled(), "DeviceWalkMode must be enabled via runner arg velumDeviceWalk=true")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB)
        modelRoot = File(context.cacheDir, "ct-restart-models").also { it.deleteRecursively(); it.mkdirs() }
    }

    @After
    fun tearDown() {
        open.forEach { runCatching { it.databaseHandle.driver.close() } }
        context.deleteDatabase(DB)
        modelRoot.deleteRecursively()
    }

    private fun newContainer(): AppContainer =
        AppContainer(InMemorySecureStore(), DriverFactory(context, DB), modelStoreRoot = ModelStoreDir(modelRoot.absolutePath))
            .also { open += it }

    private fun show(container: AppContainer) {
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    VelumTheme(darkTheme = false) {
                        RecordingDetailScreen(container, recordingId = ID, onBack = {}, initialTab = 1)
                    }
                }
            }
        }
    }

    private fun shows(label: String) =
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private fun await(label: String) = compose.waitUntil(30_000) { shows(label) }

    private fun tap(label: String) {
        if (compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText(label)[0].performClick()
        } else {
            compose.onAllNodesWithContentDescription(label)[0].performClick()
        }
    }

    @Test
    fun changeTemplate_regenerates_keeps_previous_version_and_survives_a_cold_restart() {
        val first = newContainer()
        runBlocking {
            first.settingsRepository.load()
            first.settingsRepository.setSummaryMode(ComputeMode.Api)
            first.settingsRepository.setApiKey("sk-test-not-real") // device-walk fake engine; nothing is sent
            first.repository.upsertTemplate(
                // Category "aaa" sorts first so the picker shows this row without scrolling.
                id = TEMPLATE_ID, name = TEMPLATE_NAME, category = "aaa", description = "target",
                systemPrompt = "## ZQ Heading Alpha\n- point", builtin = true, featured = true, uses = 1_000_000,
            )
            first.repository.insertRecording(
                id = ID, sessionId = null, folderId = null, title = "Weekly", source = "phone", deviceSerial = null,
                startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = null,
                transcript = "Speaker 1: ship on Friday.", summaryMarkdown = PRIOR, status = "ready",
            )
        }
        show(first)
        await("Prior heading")

        await(PtBr.changeTemplate)
        tap(PtBr.changeTemplate)
        compose.waitUntil(20_000) { compose.onAllNodesWithText(PtBr.generateCustom).fetchSemanticsNodes().isNotEmpty() }
        val chip = compose.onAllNodes(hasText(PtBr.generateCustom) and hasClickAction())
        chip[chip.fetchSemanticsNodes().lastIndex].performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(PtBr.generateCustom).fetchSemanticsNodes().size >= 2 }
        await(TEMPLATE_NAME)
        tap(TEMPLATE_NAME)
        tap(PtBr.apply)
        await("ZQ Heading Alpha")

        verify(first)

        // Cold restart: nothing of the first container survives except the database file.
        compose.runOnUiThread { compose.activity.setContent { } }
        compose.waitForIdle()
        first.databaseHandle.driver.close()
        open.remove(first)

        val second = newContainer()
        verify(second)
        show(second)
        await("ZQ Heading Alpha")
    }

    private fun verify(container: AppContainer) = runBlocking {
        val rec = container.repository.getRecording(ID)!!
        assertEquals("ready", rec.status)
        assertTrue(rec.summary_markdown.orEmpty().contains("ZQ Heading Alpha"), "regenerated summary is the active one")
        val versions = container.repository.getSummaryVersions(ID)
        val current = versions.single { it.is_active != 0L }
        assertEquals(TEMPLATE_ID, current.template_id)
        assertEquals(rec.summary_markdown, current.markdown)
        val previous = versions.single { it.is_active == 0L }
        assertEquals(PRIOR, previous.markdown, "previous summary retained verbatim")
        assertEquals(2, versions.size)
    }

    private companion object {
        const val DB = "velum-ct-restart-test.db"
        const val ID = "rec-ct-restart"
        const val TEMPLATE_ID = "dev103-target"
        const val TEMPLATE_NAME = "ZQ Change Target"
        const val PRIOR = "## Prior heading\n- prior point"
    }
}
