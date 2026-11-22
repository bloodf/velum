package dev.velum.app.ui.recording

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.platform.installAppContext
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

/** A recording whose audio file is gone: the player says so on Sources, and the note is not marked with it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w411dp-h1200dp")
class PlaybackErrorPlacementTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun missing_audio_is_reported_on_sources_only_and_without_its_internal_prefix() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        installAppContext(context)
        val root = ModelStoreDir(Files.createTempDirectory("velum-playback-error").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.insertRecording(
                id = "rec-gone", sessionId = null, folderId = null, title = "Audio gone", source = "phone",
                deviceSerial = null, startedAt = 1_763_800_000L, durationSeconds = 600, sizeBytes = 0,
                localPath = context.filesDir.resolve("missing.wav").path,
                transcript = "**Speaker 1** · 0:01\nHello.", summaryMarkdown = "## Summary\nA short note.", status = "ready",
            )
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides En) {
                    VelumTheme(darkTheme = false) { RecordingDetailScreen(container, "rec-gone", onBack = {}, initialTab = 1) }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("A short note.").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            assertEquals(0, compose.onAllNodes(hasText("playback:", substring = true)).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText(En.ux.playbackUnavailable).fetchSemanticsNodes().size)

            compose.onNodeWithText(En.sources).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(En.ux.playbackUnavailable).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(En.ux.playbackUnavailable).assertIsDisplayed()
            assertEquals(0, compose.onAllNodes(hasText("playback:", substring = true)).fetchSemanticsNodes().size)
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }
}
