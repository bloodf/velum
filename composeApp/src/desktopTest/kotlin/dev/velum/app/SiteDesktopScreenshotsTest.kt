package dev.velum.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.marketing.MarketingDemoData
import dev.velum.app.ui.library.LibraryAdaptiveScreen
import dev.velum.app.ui.library.LibraryRailDestination
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat

/**
 * Desktop marketing captures via [ImageComposeScene] at 1440×900 dp @2x → 2880×1800.
 *
 * Run: `./gradlew :composeApp:desktopTest --tests '*SiteDesktopScreenshotsTest'`
 * Output: `composeApp/build/outputs/site-desktop/`
 */
class SiteDesktopScreenshotsTest {

    private lateinit var container: AppContainer
    private val outDir = File("build/outputs/site-desktop").apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(),
        )
        runBlocking {
            container.settingsRepository.load()
            MarketingDemoData.seed(container)
            val n = container.repository.getAllRecordings().size
            assertTrue(n >= 5, "expected marketing seed recordings, got $n")
        }
    }

    @Test
    fun desktopLibrary() = capture("01-desktop-library") {
        LibraryShell(
            selectedId = MarketingDemoData.LIBRARY_SELECTED_ID,
            rail = LibraryRailDestination.Library,
            detailTab = 0,
            // Distinct from transcript shot: filtered list + result count.
            listSearch = "sprint",
        )
    }

    @Test
    fun desktopLibraryDark() = capture("01-desktop-library-dark", dark = true) {
        LibraryShell(
            selectedId = MarketingDemoData.LIBRARY_SELECTED_ID,
            rail = LibraryRailDestination.Library,
            detailTab = 0,
            listSearch = "sprint",
        )
    }

    @Test
    fun desktopRecording() = capture("02-desktop-recording") {
        LibraryShell(
            selectedId = MarketingDemoData.LIVE_ID,
            rail = LibraryRailDestination.Library,
            detailTab = 0,
        )
    }

    @Test
    fun desktopTranscript() = capture("03-desktop-transcript") {
        LibraryShell(
            selectedId = MarketingDemoData.LIBRARY_SELECTED_ID,
            rail = LibraryRailDestination.Library,
            detailTab = 0,
        )
    }

    @Test
    fun desktopAiNote() = capture("04-desktop-ai-note") {
        LibraryShell(
            selectedId = MarketingDemoData.LIBRARY_SELECTED_ID,
            rail = LibraryRailDestination.Library,
            detailTab = 1,
        )
    }

    @Test
    fun desktopSearchAsk() = capture("05-desktop-search-ask") {
        LibraryShell(
            selectedId = MarketingDemoData.LIBRARY_SELECTED_ID,
            rail = LibraryRailDestination.Ask,
            detailTab = 0,
            askPreview = true,
        )
    }

    @Composable
    private fun LibraryShell(
        selectedId: String?,
        rail: LibraryRailDestination,
        detailTab: Int,
        askPreview: Boolean = false,
        listSearch: String = "",
    ) {
        LibraryAdaptiveScreen(
            container = container,
            selectedRecordingId = selectedId,
            onSelectRecording = {},
            onOpenRecordingCompact = {},
            onOpenSettings = {},
            onOpenDevice = {},
            onOpenAskCompact = {},
            railDestination = rail,
            detailInitialTab = detailTab,
            askPreviewAnswer = if (askPreview) MarketingDemoData.AskPreview else null,
            askInitialQuestion = if (askPreview) {
                "What budget and hiring decisions did we make?"
            } else {
                ""
            },
            listSearchQuery = listSearch,
        )
    }

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        val density = Density(density = 2f, fontScale = 1f)
        ImageComposeScene(
            width = 2880,
            height = 1800,
            density = density,
        ) {
            CompositionLocalProvider(LocalStrings provides En) {
                VelumTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(VelumBg)) {
                        content()
                    }
                }
            }
        }.use { scene ->
            runBlocking {
                var nanos = 0L
                scene.render(nanos)
                repeat(60) {
                    withContext(Dispatchers.Default) { delay(5) }
                    nanos += 16_666_667L
                    scene.render(nanos)
                }
                repeat(15) {
                    withContext(Dispatchers.Default) { delay(5) }
                    nanos += 48_000_000L
                    scene.render(nanos)
                }
                val image = scene.render(nanos)
                val data = image.encodeToData(EncodedImageFormat.PNG)
                    ?: error("PNG encode failed for $name")
                File(outDir, "$name.png").writeBytes(data.bytes)
            }
        }
    }
}
