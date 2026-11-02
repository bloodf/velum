package dev.velum.app.di

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.ui.settings.VoiceProfilesScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

// #87: the sensitivity buttons must show which threshold is active, and follow a new selection.
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class VoiceProfileThresholdSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun active_threshold_is_selected_and_selecting_another_moves_it() {
        val root = ModelStoreDir(Files.createTempDirectory("velum-threshold-ui-test").toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent { VelumTheme(darkTheme = false) { VoiceProfilesScreen(container, onBack = {}) } }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("0.55")).fetchSemanticsNodes().isNotEmpty()
            }
            // Default threshold 0.55 is active; the others are not.
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("0.55") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("0.55").performScrollTo().assertIsSelected()
            compose.onNodeWithContentDescription("0.75").assertIsNotSelected()

            compose.onNodeWithContentDescription("0.75").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("0.75") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("0.55").assertIsNotSelected()
        } finally {
            activity.pause().stop().destroy()
            root.deleteRecursively()
        }
    }
}
