package dev.velum.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.importing.ImportResult
import dev.velum.app.platform.installAppContext
import dev.velum.app.ui.importing.ImportHubScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "en-rUS-w360dp-h1800dp-xxhdpi")
class ImportHubCountLocaleUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun render(strings: Strings, created: Int, dupes: Int, failed: Int, skipped: Int, expected: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        installAppContext(context)
        val root = ModelStoreDir(Files.createTempDirectory("velum-import-count-ui").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val opened = mutableListOf<String>()
        var scans = 0
        val results = buildList {
            repeat(created) { add(ImportResult.Created("created-$it", "synthetic-$it.wav", "created-hash-$it")) }
            repeat(dupes) { add(ImportResult.Duplicate("duplicate-$it", "duplicate-hash-$it")) }
            repeat(failed) { add(ImportResult.Failed("synthetic failure")) }
            repeat(skipped) { add(ImportResult.Deleted("deleted-hash-$it")) }
        }
        try {
            runBlocking { container.settingsRepository.load() }
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides strings) {
                    VelumTheme(darkTheme = false) {
                        ImportHubScreen(
                            container = container,
                            onBack = {},
                            onOpenRecording = { opened += it },
                            onSyncWatchedFolder = { scans++; results },
                        )
                    }
                }
            }
            compose.onNodeWithContentDescription(strings.ux.syncWatchedFolders).performScrollTo().performClick()
            compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, scans)
                assertEquals(if (created == 0) emptyList() else listOf("created-${created - 1}"), opened)
            }
        } finally {
            activity.pause().stop().destroy()
            try {
                container.databaseHandle.driver.close()
            } finally {
                context.deleteDatabase("velum.db")
                root.deleteRecursively()
            }
        }
    }

    @Test fun english_zero_new_one_duplicate_three_failures() = render(
        En, 0, 1, 3, 0, "Done · 0 new · 1 dupe · 3 failed · 0 skipped",
    )

    @Test fun english_one_new_three_duplicates_one_skipped() = render(
        En, 1, 3, 0, 1, "Done · 1 new · 3 dupes · 0 failed · 1 skipped",
    )

    @Test fun english_three_new_one_failure_three_skipped() = render(
        En, 3, 0, 1, 3, "Done · 3 new · 0 dupes · 1 failed · 3 skipped",
    )

    @Test fun portuguese_zero_new_one_duplicate_three_failures() = render(
        PtBr, 0, 1, 3, 0, "Pronto · 0 novos · 1 duplicado · 3 falhas · 0 ignorados",
    )

    @Test fun portuguese_one_new_three_duplicates_one_skipped() = render(
        PtBr, 1, 3, 0, 1, "Pronto · 1 novo · 3 duplicados · 0 falhas · 1 ignorado",
    )

    @Test fun portuguese_three_new_one_failure_three_skipped() = render(
        PtBr, 3, 0, 1, 3, "Pronto · 3 novos · 0 duplicados · 1 falha · 3 ignorados",
    )
}
