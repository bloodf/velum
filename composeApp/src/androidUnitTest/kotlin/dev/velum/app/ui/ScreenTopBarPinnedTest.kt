package dev.velum.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.pipeline.AskAcrossRecordings
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.ask.AskAcrossScreen
import dev.velum.app.ui.importing.ImportHubScreen
import dev.velum.app.ui.settings.AboutScreen
import dev.velum.app.ui.settings.AiProcessingScreen
import dev.velum.app.ui.settings.ApiSettingsScreen
import dev.velum.app.ui.settings.DevicesSettingsScreen
import dev.velum.app.ui.settings.ImportDeviceKeysScreen
import dev.velum.app.ui.settings.PersonalizationScreen
import dev.velum.app.ui.settings.PreferencesScreen
import dev.velum.app.ui.settings.RecordingSettingsScreen
import dev.velum.app.ui.settings.StoragePrivacyScreen
import dev.velum.app.ui.settings.VoiceProfilesScreen
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Regression for #118: ScreenTopBar (Back) must stay fixed while body scrolls
 * on every Settings / Import / Ask screen that previously put Back inside
 * verticalScroll.
 *
 * Short viewport forces a real scroll (anchor starts below the fold or moves).
 * A 2000dp-tall viewport must fail this test — see QA mutation checklist.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    // Short enough that every listed screen's bottom anchor starts off-screen.
    qualifiers = "w360dp-h200dp-xxhdpi",
    // Registers ComponentActivity for createComposeRule without a debug APK manifest
    // entry (keeps device-walk debug APK aligned with main). Avoids VelumApplication
    // → AndroidKeyStore under Robolectric.
    application = RobolectricComposeTestApp::class,
)
class ScreenTopBarPinnedTest(
    private val screen: ScreenCase,
) {

    enum class ScreenCase {
        About,
        AiProcessing,
        ApiSettings,
        DevicesSettings,
        ImportDeviceKeys,
        Personalization,
        Preferences,
        RecordingSettings,
        StoragePrivacy,
        VoiceProfiles,
        ImportHub,
        AskAcross,
    }

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
        )
        // Seed voice profiles so VoiceProfiles has enough content to scroll.
        runBlocking { container.voiceProfileStore.ensureKey() }
        val emb = FloatArray(192) { i -> (i % 17) / 17f }
        repeat(6) { i ->
            container.voiceProfileStore.upsert(
                "Speaker $i",
                FloatArray(192) { j -> ((j + i) % 19) / 19f },
                role = "Role $i",
                company = "Velum",
            )
        }
        container.voiceProfileStore.upsert("Anchor", emb, role = "QA", company = "Velum")
    }

    @Test
    fun backStaysPutAfterScroll() {
        setScreen { contentFor(screen) }
        assertBackPinnedAfterRealScroll(anchorFor(screen))
    }

    private fun anchorFor(screen: ScreenCase): String = when (screen) {
        ScreenCase.About -> En.settingsUi.contributeTemplates
        ScreenCase.AiProcessing -> En.modelManagerHint
        // Unique label near the bottom of the models-by-task section (not a TextField).
        ScreenCase.ApiSettings -> En.settingsUi.diarizationApiLocalOnly
        ScreenCase.DevicesSettings -> En.settingsUi.autoSyncWatchedFolders
        ScreenCase.ImportDeviceKeys -> En.importKeys.clearKeys
        ScreenCase.Personalization -> En.manageMemoryHint
        ScreenCase.Preferences -> En.messageNotification
        ScreenCase.RecordingSettings -> En.settingsUi.recordingNotesBody
        ScreenCase.StoragePrivacy -> En.settingsUi.wipeAllData
        ScreenCase.VoiceProfiles -> En.ux.voiceProfilesEnrollHint
        ScreenCase.ImportHub -> En.ux.syncWatchedFolders
        ScreenCase.AskAcross -> En.ux.askAcrossSources
    }

    /** Prefer the lowest matching node when labels repeat (e.g. TextField decoration). */
    private fun anchorNode(text: String): SemanticsNodeInteraction {
        val nodes = composeRule.onAllNodesWithText(text)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("Expected at least one node with text '$text'", count >= 1)
        return nodes[count - 1]
    }

    @Composable
    private fun contentFor(screen: ScreenCase) {
        when (screen) {
            ScreenCase.About -> AboutScreen(onBack = {})
            ScreenCase.AiProcessing -> AiProcessingScreen(
                container = container,
                onBack = {},
                onModelManager = {},
            )
            ScreenCase.ApiSettings -> ApiSettingsScreen(
                container = container,
                onBack = {},
                onAiProcessing = {},
                onModelManager = {},
            )
            ScreenCase.DevicesSettings -> DevicesSettingsScreen(
                container = container,
                onBack = {},
                onDevice = {},
                onImportKeys = {},
            )
            ScreenCase.ImportDeviceKeys -> ImportDeviceKeysScreen(
                container = container,
                onBack = {},
            )
            ScreenCase.Personalization -> PersonalizationScreen(
                container = container,
                onBack = {},
                onMemory = {},
            )
            ScreenCase.Preferences -> PreferencesScreen(
                container = container,
                onBack = {},
            )
            ScreenCase.RecordingSettings -> RecordingSettingsScreen(
                container = container,
                onBack = {},
            )
            ScreenCase.StoragePrivacy -> StoragePrivacyScreen(
                container = container,
                onBack = {},
            )
            ScreenCase.VoiceProfiles -> VoiceProfilesScreen(
                container = container,
                onBack = {},
            )
            ScreenCase.ImportHub -> ImportHubScreen(
                container = container,
                onBack = {},
                onOpenRecording = {},
                onPickWatchedFolder = {},
                onSyncWatchedFolder = { emptyList() },
            )
            ScreenCase.AskAcross -> AskAcrossScreen(
                container = container,
                onBack = {},
                onOpenCitation = { _, _ -> },
                previewAnswer = askPreviewAnswer(),
                initialQuestion = "What was decided in the last sync?",
            )
        }
    }

    private fun askPreviewAnswer(): AskAcrossRecordings.Answer =
        AskAcrossRecordings.Answer(
            markdown = buildString {
                appendLine("## Answer")
                repeat(12) { i ->
                    appendLine()
                    appendLine(
                        "Paragraph $i — enough body text so the Ask screen must scroll on a short viewport.",
                    )
                }
            },
            citations = (1..8).map { i ->
                AskAcrossRecordings.Citation(
                    recordingId = "rec-$i",
                    title = "Recording $i with a longer title for layout height",
                    offsetSeconds = (i * 30).toLong(),
                    timestampLabel = "0:${(i * 30) % 60}",
                    snippet = "Snippet $i ".repeat(12).trim(),
                )
            },
            matchedRecordingIds = (1..8).map { "rec-$it" },
        )

    private fun setScreen(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalStrings provides En,
                LocalDensity provides Density(density = 3f, fontScale = 1f),
            ) {
                VelumTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().background(VelumBg)) {
                        content()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertBackPinnedAfterRealScroll(scrollAnchorText: String) {
        val backCd = En.ux.back
        val back = composeRule.onNodeWithContentDescription(backCd)
        back.assertIsDisplayed()
        val backBefore = back.getBoundsInRoot()
        val root = composeRule.onRoot().getBoundsInRoot()
        val anchor = anchorNode(scrollAnchorText)
        val anchorBefore = anchor.getBoundsInRoot()

        // Prove the body must scroll: anchor starts below the visible root, or
        // performScrollTo actually moves it. Tall (e.g. 2000dp) viewports fail here.
        val startedOffScreenBelow = anchorBefore.top.value >= root.bottom.value - 0.5f
        val startedOffScreenAbove = anchorBefore.bottom.value <= root.top.value + 0.5f

        anchor.performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()

        val anchorAfter = anchorNode(scrollAnchorText).getBoundsInRoot()
        val moved = abs(anchorAfter.top.value - anchorBefore.top.value) > 1f
        assertTrue(
            "Expected a real scroll for '$scrollAnchorText' on ${screen.name} " +
                "(offBelow=$startedOffScreenBelow offAbove=$startedOffScreenAbove moved=$moved " +
                "beforeTop=${anchorBefore.top} afterTop=${anchorAfter.top} rootBottom=${root.bottom})",
            startedOffScreenBelow || startedOffScreenAbove || moved,
        )

        val afterBack = composeRule.onNodeWithContentDescription(backCd)
        afterBack.assertIsDisplayed()
        val backAfter = afterBack.getBoundsInRoot()

        assertEquals(
            "Back top must stay fixed while content scrolls (#118) [${screen.name}]",
            backBefore.top.value,
            backAfter.top.value,
            0.5f,
        )
        assertEquals(
            "Back left must stay fixed while content scrolls (#118) [${screen.name}]",
            backBefore.left.value,
            backAfter.left.value,
            0.5f,
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> =
            ScreenCase.entries.map { arrayOf(it) }
    }
}
