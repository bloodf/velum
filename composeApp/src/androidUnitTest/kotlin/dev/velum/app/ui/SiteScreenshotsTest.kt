package dev.velum.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import org.robolectric.Robolectric
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.marketing.DemoAudio
import dev.velum.app.marketing.MarketingDemoData
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.importing.ImportHubScreen
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.shell.ShellTab
import dev.velum.app.ui.shell.VelumBottomBar
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Marketing site phone captures (~1080×2400 via w360dp-h800dp @ xxhdpi).
 * English UI + [MarketingDemoData]. Output names match docs/site-screenshots/.
 *
 * Run: `./gradlew :composeApp:recordRoborazziDebug --tests '*SiteScreenshotsTest'`
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: VelumApplication would start its own container (startup recovery, AutoFlow) on the same
// database and change the seeded rows, e.g. end the "recording in progress" demo before it is captured.
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class SiteScreenshotsTest {

    @get:Rule
    val roborazziRule = RoborazziRule()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        // Clock times in the captures must not depend on the machine that renders them.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        dev.velum.app.platform.installAppContext(context)
        dev.velum.app.platform.installRecordingFilesDir(java.io.File(context.filesDir, "recordings").apply { mkdirs() }.path)
        container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
        )
        runBlocking {
            container.settingsRepository.load()
            MarketingDemoData.seed(container) { id, seconds -> DemoAudio.write(java.io.File(context.cacheDir, "site-audio"), seconds, "$id.wav").absolutePath }
        }
    }

    @Test
    fun mobileLibrary() = capture("06-mobile-library", readyText = "Weekly product sync") {
        FilesScreen(
            container,
            onOpenRecording = {},
            onOpenSettings = {},
            selectedRecordingId = MarketingDemoData.LIBRARY_SELECTED_ID,
            onAskAcross = {},
        )
        BottomChrome()
    }

    @Test
    fun mobileLibraryDark() = capture("06-mobile-library-dark", dark = true, readyText = "Weekly product sync") {
        FilesScreen(
            container,
            onOpenRecording = {},
            onOpenSettings = {},
            selectedRecordingId = MarketingDemoData.LIBRARY_SELECTED_ID,
            onAskAcross = {},
        )
        BottomChrome()
    }

    @Test
    fun mobileRecording() = capture("07a-mobile-recording", readyText = En.liveTranscript) {
        RecordingDetailScreen(
            container,
            MarketingDemoData.LIVE_ID,
            onBack = {},
            initialTab = 0,
        )
    }

    @Test
    fun mobileNote() = capture("07b-mobile-note", readyText = "Key points") {
        RecordingDetailScreen(
            container,
            MarketingDemoData.LIBRARY_SELECTED_ID,
            onBack = {},
            initialTab = 1,
        )
    }

    @Test
    fun plaudImport() = capture("08-plaud-import", readyText = En.ux.importHubTitle) {
        ImportHubScreen(
            container = container,
            onBack = {},
            onOpenRecording = {},
            onPickWatchedFolder = {},
            onSyncWatchedFolder = { emptyList() },
        )
    }

    @Composable
    private fun BottomChrome() {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            VelumBottomBar(
                selected = ShellTab.Files,
                fabOpen = false,
                onFiles = {},
                onExplore = {},
                onFab = {},
            )
        }
    }

    /**
     * Renders [content] and captures it once [readyText] is on screen: the recordings arrive from the database
     * asynchronously, and a capture taken on the first frame showed an empty screen.
     */
    private fun capture(name: String, dark: Boolean = false, readyText: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(
                    LocalStrings provides En,
                    LocalDensity provides Density(density = 3f, fontScale = 1f),
                ) {
                    VelumTheme(darkTheme = dark) {
                        Box(Modifier.fillMaxSize().background(VelumBg)) {
                            content()
                        }
                    }
                }
            }
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(readyText).fetchSemanticsNodes().isNotEmpty() }
            composeRule.waitForIdle()
            composeRule.onRoot().captureRoboImage("site-$name.png")
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
