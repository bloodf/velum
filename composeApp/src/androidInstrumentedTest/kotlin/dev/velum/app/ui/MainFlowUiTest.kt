package dev.velum.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke for main shell flows. Requires a device/emulator:
 * `./gradlew :composeApp:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class MainFlowUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun filesList_showsPtBrArquivosHeaderAndSeededItems() {
        composeRule.onNodeWithText("Todos os arquivos", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Planejamento de produto", substring = true).assertIsDisplayed()
    }

    @Test
    fun exploreTab_opensExploreScreen() {
        composeRule.onNodeWithContentDescription("Explore").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Explorar", substring = true).assertIsDisplayed()
    }

    @Test
    fun recordingDetail_opensNotasWithMarkdownSummary() {
        composeRule.onNodeWithText("26 out, 10:39", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Notas", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Resumo", substring = true).assertIsDisplayed()
    }
}
