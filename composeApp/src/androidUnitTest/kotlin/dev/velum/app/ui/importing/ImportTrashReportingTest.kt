package dev.velum.app.ui.importing

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.importing.ImportResult
import dev.velum.app.platform.installAppContext
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [34])
class ImportTrashReportingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun watched_trash_match_is_visible_and_does_not_open_the_recording() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        installAppContext(context)
        val root = ModelStoreDir(Files.createTempDirectory("velum-trash-reporting").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val opened = mutableListOf<String>()
        var scans = 0
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize()) {
                            ImportHubScreen(
                                container = container,
                                onBack = {},
                                onOpenRecording = { opened += it },
                                onSyncWatchedFolder = {
                                    scans++
                                    listOf(ImportResult.InTrash("synthetic-trashed", "synthetic-hash"))
                                },
                            )
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription(En.ux.syncWatchedFolders).performScrollTo().performClick()
            compose.onNodeWithText("Done · 0 new · 0 dupes · 0 failed · 0 skipped · 1 in Trash (not imported)")
                .performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, scans)
                assertEquals(emptyList(), opened, "the Import hub opened a trashed recording")
            }
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }
}
