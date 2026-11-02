package dev.velum.app.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class LocalModelMissingDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun show(onSwitchToApi: (() -> Unit)?, onDownload: () -> Unit = {}, block: () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides PtBr) {
                    LocalModelMissingDialog("Modelo X", onDownload, onSwitchToApi, onDismiss = {})
                }
            }
            block()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun switch_to_api_is_offered_and_works_when_there_is_an_api_route() {
        var switched = 0
        show(onSwitchToApi = { switched++ }) {
            compose.onNodeWithText(PtBr.switchToApi).performClick()
            assertEquals(1, switched)
        }
    }

    @Test
    fun no_switch_to_api_button_without_an_api_route_but_download_stays() {
        var downloads = 0
        show(onSwitchToApi = null, onDownload = { downloads++ }) {
            compose.onNodeWithText(PtBr.switchToApi).assertDoesNotExist()
            compose.onNodeWithText(PtBr.downloadModel).performClick()
            assertEquals(1, downloads)
        }
    }
}
