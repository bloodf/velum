package dev.velum.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.autoflow.AutoFlowListScreen
import dev.velum.app.ui.brand.VelumLogo
import dev.velum.app.ui.explore.ExploreScreen
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.integrations.IntegrationsScreen
import dev.velum.app.ui.mindmap.MindMapView
import dev.velum.app.ui.mindmap.buildMindMap
import dev.velum.app.ui.onboarding.FirstRunScreen
import dev.velum.app.ui.recording.GenerateSheetContent
import dev.velum.app.ui.recording.RecordingDetailScreen
import dev.velum.app.ui.recording.SpeakerLabelSheetContent
import dev.velum.app.ui.settings.AiProcessingScreen
import dev.velum.app.ui.settings.ModelManagerScreen
import dev.velum.app.ui.settings.SettingsHubScreen
import dev.velum.app.ui.shell.FabOverlay
import dev.velum.app.ui.shell.ShellTab
import dev.velum.app.ui.shell.VelumBottomBar
import dev.velum.app.ui.templates.TemplatesScreen
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic phone screenshots (~1080×2400 px via w360dp-h800dp @ xxhdpi).
 * Run: `./gradlew :composeApp:recordRoborazziDebug`
 * PNGs land in composeApp/build/outputs/roborazzi/ (uploaded as CI artifacts; not committed).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class MainScreenshotsTest {

    @get:Rule
    val roborazziRule = RoborazziRule()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Fresh DB so SeedData v3 Markdown fixtures always land for Roborazzi.
        context.deleteDatabase("velum.db")
        container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
        )
    }

    @Test
    fun filesList() = capture("01-files-list") {
        FilesScreen(container, onOpenRecording = {}, onOpenSettings = {})
        BottomChrome(ShellTab.Files, fabOpen = false)
    }

    @Test
    fun fabOverlay() = capture("02-fab-overlay") {
        FilesScreen(container, onOpenRecording = {}, onOpenSettings = {})
        FabOverlay(visible = true, onStartRecording = {}, onImportAudio = {}, onDismiss = {})
        BottomChrome(ShellTab.Files, fabOpen = true)
    }

    @Test
    fun recordingFontes() = capture("03-recording-fontes") {
        // Seeded diarized Markdown transcript rendered via MarkdownPreview.
        RecordingDetailScreen(container, "rec-demo-1", onBack = {}, initialTab = 0)
    }

    @Test
    fun recordingNotas() = capture("04-recording-notas") {
        // Seeded meeting summary Markdown (headings + lists) in Notas.
        RecordingDetailScreen(container, "rec-demo-1", onBack = {}, initialTab = 1)
    }

    @Test
    fun nameSpeakersSheet() = capture("05-name-speakers") {
        Box(Modifier.fillMaxSize().background(VelumBg)) {
            SpeakerLabelSheetContent(onClose = {})
        }
    }

    @Test
    fun generateSheet() = capture("06-generate-sheet") {
        Box(Modifier.fillMaxSize().background(VelumBg)) {
            GenerateSheetContent(onClose = {})
        }
    }

    @Test
    fun templates() = capture("07-templates") {
        TemplatesScreen(container, onBack = {}, onOpenTemplate = {})
    }

    @Test
    fun explore() = capture("08-explore") {
        ExploreScreen(
            container = container,
            onTemplates = {},
            onAutoFlow = {},
            onIntegrations = {},
            onAccount = {},
        )
        BottomChrome(ShellTab.Explore, fabOpen = false)
    }

    @Test
    fun autoFlow() = capture("09-autoflow") {
        AutoFlowListScreen(container, onBack = {}, onEdit = {})
    }

    @Test
    fun integrations() = capture("10-integrations") {
        IntegrationsScreen(container = container, onBack = {})
    }

    @Test
    fun settings() = capture("11-settings") {
        SettingsHubScreen(
            container = container,
            onBack = {},
            onPersonalization = {},
            onPreferences = {},
            onAccount = {},
        )
    }

    @Test
    fun aiProcessing() = capture("12-ai-processing") {
        AiProcessingScreen(container, onBack = {}, onModelManager = {})
    }

    @Test
    fun modelManager() = capture("13-model-manager") {
        ModelManagerScreen(container, onBack = {})
    }

    @Test
    fun firstRun() = capture("14-first-run") {
        FirstRunScreen(onDownload = {}, onUseApi = {}, onSkip = {})
    }

    @Test
    fun brandLauncherLight() = capture("16-brand-launcher-light") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VelumLogo(
                    size = 160.dp,
                    dark = false,
                    modifier = Modifier.clip(RoundedCornerShape(36.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Text("Velum", fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            }
        }
    }

    @Test
    fun brandSplashDark() {
        captureRoboImage("velum-17-brand-splash-dark.png") {
            CompositionLocalProvider(
                LocalStrings provides PtBr,
                LocalDensity provides Density(density = 3f, fontScale = 1f),
            ) {
                VelumTheme(darkTheme = true) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        VelumLogo(size = 160.dp, dark = true)
                    }
                }
            }
        }
    }

    @Test
    fun mindMap() = capture("15-mind-map") {
        val map = buildMindMap(
            markdown = """
                ## Informações da reunião
                - Participantes: Heitor, Marina
                ## Decisões
                - Usar Parakeet local
                - Resumo com Gemma E4B
                ## Próximos passos
                - Importar chaves do Note Pro
                ## Sugestões da IA
                - Confirmar firmware
            """.trimIndent(),
            rootTitle = "Reunião demo",
        )
        MindMapView(map)
    }

    @Composable
    private fun BottomChrome(selected: ShellTab, fabOpen: Boolean) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            VelumBottomBar(
                selected = selected,
                fabOpen = fabOpen,
                onFiles = {},
                onExplore = {},
                onFab = {},
            )
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("velum-$name.png") {
            CompositionLocalProvider(
                LocalStrings provides PtBr,
                LocalDensity provides Density(density = 3f, fontScale = 1f),
            ) {
                VelumTheme {
                    Box(Modifier.fillMaxSize().background(VelumBg)) {
                        content()
                    }
                }
            }
        }
    }
}
