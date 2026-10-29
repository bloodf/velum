package dev.velum.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.pipeline.AskAcrossRecordings
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.ask.AskAcrossScreen
import dev.velum.app.ui.importing.ImportHubScreen
import dev.velum.app.ui.settings.PreferencesScreen
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression for #118: ScreenTopBar (Back) must stay fixed while body scrolls
 * on Settings / Import / Ask destinations.
 *
 * Short viewport + performScrollTo on a lower row; Back boundsInRoot must not move.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h280dp-xxhdpi",
    // Avoid VelumApplication → AndroidKeyStore (unavailable under Robolectric).
    application = android.app.Application::class,
)
class ScreenTopBarPinnedTest {

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
    }

    @Test
    fun preferences_backStaysPutAfterScroll() {
        setScreen {
            PreferencesScreen(container = container, onBack = {})
        }
        assertBackPinnedAfterSwipe(scrollAnchorText = En.messageNotification)
    }

    @Test
    fun importHub_backStaysPutAfterScroll() {
        setScreen {
            ImportHubScreen(
                container = container,
                onBack = {},
                onOpenRecording = {},
                onPickWatchedFolder = {},
                onSyncWatchedFolder = { emptyList() },
            )
        }
        assertBackPinnedAfterSwipe(scrollAnchorText = En.ux.syncWatchedFolders)
    }

    @Test
    fun askAcross_backStaysPutAfterScroll() {
        val preview = AskAcrossRecordings.Answer(
            markdown = buildString {
                appendLine("## Answer")
                repeat(12) { i ->
                    appendLine()
                    appendLine("Paragraph $i — enough body text so the Ask screen must scroll on a short viewport.")
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
        setScreen {
            AskAcrossScreen(
                container = container,
                onBack = {},
                onOpenCitation = { _, _ -> },
                previewAnswer = preview,
                initialQuestion = "What was decided in the last sync?",
            )
        }
        assertBackPinnedAfterSwipe(scrollAnchorText = En.ux.askAcrossSources)
    }

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

    private fun assertBackPinnedAfterSwipe(scrollAnchorText: String) {
        val backCd = En.ux.back
        val back = composeRule.onNodeWithContentDescription(backCd)
        back.assertIsDisplayed()
        val backBefore = back.getBoundsInRoot()

        // Same path device-walk uses: scroll a lower row into view (#118 / #106).
        // If Back lived inside verticalScroll, this would move/hide it.
        composeRule.onNodeWithText(scrollAnchorText).performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()

        val afterBack = composeRule.onNodeWithContentDescription(backCd)
        afterBack.assertIsDisplayed()
        val backAfter = afterBack.getBoundsInRoot()

        assertEquals(
            "Back top must stay fixed while content scrolls (#118)",
            backBefore.top.value,
            backAfter.top.value,
            0.5f,
        )
        assertEquals(
            "Back left must stay fixed while content scrolls (#118)",
            backBefore.left.value,
            backAfter.left.value,
            0.5f,
        )
    }
}
