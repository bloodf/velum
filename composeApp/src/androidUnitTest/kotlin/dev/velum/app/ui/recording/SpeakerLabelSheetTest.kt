package dev.velum.app.ui.recording

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class SpeakerLabelSheetTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun empty_sheet_has_no_editable_speakers_and_real_speaker_can_be_named() {
        val speakers = mutableStateOf(emptyList<SpeakerLabelRow>())
        var closed = 0
        var confirmed: Map<String, String>? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    SpeakerLabelSheetContent(onClose = { closed++ }, speakers = speakers.value, onConfirm = { confirmed = it })
                }
            }
            compose.onNodeWithText(En.added.noSpeakers).assertExists()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithText("Total duration: 5m 12s").assertDoesNotExist()
            compose.onNodeWithText("Total duration: 3m 40s").assertDoesNotExist()
            compose.onNodeWithText(En.confirm).assertIsNotEnabled()
            compose.onNodeWithText(En.cancel).performClick()
            assertEquals(1, closed)
            assertEquals(null, confirmed)

            compose.runOnIdle { speakers.value = listOf(SpeakerLabelRow("actual-id", "S1", null, 42)) }
            compose.onNodeWithText(En.added.noSpeakers).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.onNode(hasSetTextAction()).performTextInput("Ada")
            compose.onNodeWithText(En.confirm).performClick()
            assertEquals(mapOf("actual-id" to "Ada"), confirmed)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
