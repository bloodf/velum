package dev.velum.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.settings.StoragePrivacyScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "en-rUS-w360dp-h1800dp-xxhdpi")
class StorageCountLocaleUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun english_empty_library() = render(0, En, "0 recordings · 0 B")
    @Test fun english_one_recording() = render(1, En, "1 recording · 100 B")
    @Test fun english_three_recordings() = render(3, En, "3 recordings · 300 B")
    @Test fun portuguese_empty_library() = render(0, PtBr, "0 gravações · 0 B")
    @Test fun portuguese_one_recording() = render(1, PtBr, "1 gravação · 100 B")
    @Test fun portuguese_three_recordings() = render(3, PtBr, "3 gravações · 300 B")

    private fun render(count: Int, strings: Strings, expected: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("en", context.resources.configuration.locales[0].language)
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-storage-count-ui").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        try {
            runBlocking {
                container.settingsRepository.load()
                repeat(count) { index ->
                    container.repository.insertRecording(
                        id = "storage-$index", sessionId = null, folderId = null,
                        title = "Synthetic recording $index", source = "phone", deviceSerial = null,
                        startedAt = 1, durationSeconds = 60, sizeBytes = 100, localPath = null,
                        transcript = null, summaryMarkdown = null, status = "ready",
                    )
                }
            }
            val activity = Robolectric.buildActivity(ComponentActivity::class.java)
            try {
                activity.setup().get().setContent {
                    CompositionLocalProvider(LocalStrings provides strings) {
                        VelumTheme(darkTheme = false) {
                            StoragePrivacyScreen(container, onBack = {})
                        }
                    }
                }
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
            } finally {
                activity.pause().stop().destroy()
            }
        } finally {
            try {
                container.databaseHandle.driver.close()
            } finally {
                try {
                    context.deleteDatabase("velum.db")
                    assertTrue(!context.getDatabasePath("velum.db").exists())
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    }
}
