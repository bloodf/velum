package dev.velum.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.theme.VelumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CountTextBoundaryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun search_sheet_displays_count_boundaries_in_both_languages() {
        val count = mutableIntStateOf(0)
        val portuguese = androidx.compose.runtime.mutableStateOf(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides if (portuguese.value) PtBr else En) {
                    VelumTheme(darkTheme = false) {
                        SearchSheet(query = "", onQueryChange = {}, onDismiss = {}, resultCount = count.intValue)
                    }
                }
            }
            compose.onNodeWithText("0 results").assertIsDisplayed()
            compose.runOnIdle { count.intValue = 1 }
            compose.onNodeWithText("1 result").assertIsDisplayed()
            compose.runOnIdle { count.intValue = 3 }
            compose.onNodeWithText("3 results").assertIsDisplayed()
            compose.runOnIdle { portuguese.value = true; count.intValue = 0 }
            compose.onNodeWithText("0 resultados").assertIsDisplayed()
            compose.runOnIdle { count.intValue = 1 }
            compose.onNodeWithText("1 resultado").assertIsDisplayed()
            compose.runOnIdle { count.intValue = 3 }
            compose.onNodeWithText("3 resultados").assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
