package dev.velum.app.devicewalk

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import dev.velum.app.MainActivity
import dev.velum.app.VelumApplication
import org.junit.Assume.assumeTrue
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
 * #103 on a device, through the real RecordingDetailScreen, in two host-coordinated phases that run in
 * two separate `am instrument` processes with an `am force-stop` between them (a real cold restart):
 *
 * - `regenerate`: seed a recording, Change template, Custom, pick a template, Apply; the regenerated
 *   summary appears and the previous summary stays as an inactive version. The database file is kept.
 * - `verify`: new process, new container on the same file; the active summary, its template id and the
 *   prior version are still there and the screen shows them. Deletes the file.
 *
 * Without the `ctPhase` argument both tests are skipped, so default discovery is unaffected.
 * Synthetic data, device-walk fake engines (no network, no model), its own database file (never velum.db).
 *
 * Run:
 * ```
 * I=dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 * C=dev.velum.app.devicewalk.ChangeTemplateRestartDeviceTest
 * adb shell am instrument -w -e velumDeviceWalk true -e class $C -e ctPhase regenerate $I
 * adb shell am force-stop dev.velum.app
 * adb shell am instrument -w -e velumDeviceWalk true -e class $C -e ctPhase verify $I
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ChangeTemplateRestartDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var modelRoot: File
    private lateinit var container: AppContainer
    private val phase: String get() = InstrumentationRegistry.getArguments().getString("ctPhase").orEmpty()

    @Before
    fun setUp() {
        assumeTrue("ctPhase not set", phase == "regenerate" || phase == "verify")
        assertTrue(DeviceWalkMode.isEnabled(), "DeviceWalkMode must be enabled via runner arg velumDeviceWalk=true")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        modelRoot = File(context.cacheDir, "ct-restart-models").also { it.deleteRecursively(); it.mkdirs() }
        if (phase == "regenerate") context.deleteDatabase(DB)
        container = AppContainer(InMemorySecureStore(), DriverFactory(context, DB), modelStoreRoot = ModelStoreDir(modelRoot.absolutePath))
    }

    @After
    fun tearDown() {
        if (phase != "regenerate" && phase != "verify") return
        // Activity first: nothing may touch the database while it is closed.
        compose.activityRule.scenario.close()
        container.databaseHandle.driver.close()
        modelRoot.deleteRecursively()
        if (phase == "verify") context.deleteDatabase(DB) // regenerate keeps the file for the next process
    }

    private fun show() {
        // Wait for the app's own handoff (it replaces content once, with App), then take over the activity.
        compose.waitUntil(CONTAINER_TIMEOUT_MS) { (compose.activity.application as VelumApplication).isContainerReady() }
        compose.waitUntil(CONTENT_TIMEOUT_MS) {
            runCatching { compose.onRoot().fetchSemanticsNode().children.isNotEmpty() }.getOrDefault(false)
        }
        compose.waitForIdle()
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

    private fun await(label: String) = compose.waitUntil(UI_TIMEOUT_MS) { shows(label) }

    /** Semantics click: works for nodes below the fold on short screens, where pointer input would miss. */
    private fun click(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun tap(label: String) {
        if (compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) {
            click(compose.onAllNodesWithText(label)[0])
        } else {
            click(compose.onAllNodesWithContentDescription(label)[0])
        }
    }

    @Test
    fun phase_regenerate_changes_the_template_and_keeps_the_previous_version() {
        assumeTrue("phase is $phase", phase == "regenerate")
        runBlocking {
            container.settingsRepository.load()
            container.settingsRepository.setSummaryMode(ComputeMode.Api)
            container.settingsRepository.setApiKey("sk-test-not-real") // device-walk fake engine; nothing is sent
            container.repository.upsertTemplate(
                // Category "aaa" sorts first so the picker shows this row without scrolling.
                id = TEMPLATE_ID, name = TEMPLATE_NAME, category = "aaa", description = "target",
                systemPrompt = "## ZQ Heading Alpha\n- point", builtin = true, featured = true, uses = 1_000_000,
            )
            container.repository.insertRecording(
                id = ID, sessionId = null, folderId = null, title = "Weekly", source = "phone", deviceSerial = null,
                startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = null,
                transcript = "Speaker 1: ship on Friday.", summaryMarkdown = PRIOR, status = "ready",
            )
        }
        show()
        await("Prior heading")

        await(PtBr.changeTemplate)
        tap(PtBr.changeTemplate)
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(PtBr.generateCustom).fetchSemanticsNodes().isNotEmpty() }
        val chip = compose.onAllNodes(hasText(PtBr.generateCustom) and hasClickAction())
        click(chip[chip.fetchSemanticsNodes().lastIndex])
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText(PtBr.generateCustom).fetchSemanticsNodes().size >= 2 }
        await(TEMPLATE_NAME)
        tap(TEMPLATE_NAME)
        tap(PtBr.apply)
        await("ZQ Heading Alpha")

        verify()
    }

    @Test
    fun phase_verify_finds_both_versions_after_a_force_stop() {
        assumeTrue("phase is $phase", phase == "verify")
        verify()
        show()
        await("ZQ Heading Alpha")
    }

    private fun verify() = runBlocking {
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
        const val UI_TIMEOUT_MS = 30_000L
        const val CONTAINER_TIMEOUT_MS = 30_000L
        const val CONTENT_TIMEOUT_MS = 30_000L
        const val DB = "velum-ct-restart-test.db"
        const val ID = "rec-ct-restart"
        const val TEMPLATE_ID = "dev103-target"
        const val TEMPLATE_NAME = "ZQ Change Target"
        const val PRIOR = "## Prior heading\n- prior point"
    }
}
