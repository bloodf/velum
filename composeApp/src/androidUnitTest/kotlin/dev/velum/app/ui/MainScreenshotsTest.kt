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
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.autoflow.AutoFlowListScreen
import dev.velum.app.ui.brand.VelumLogo
import dev.velum.app.ui.explore.ExploreScreen
import dev.velum.app.ui.files.FilesScreen
import dev.velum.app.ui.importing.ImportHubScreen
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
import dev.velum.app.ui.settings.VoiceProfilesScreen
import dev.velum.app.ui.shell.FabOverlay
import dev.velum.app.ui.shell.ShellTab
import dev.velum.app.ui.shell.VelumBottomBar
import dev.velum.app.ui.templates.TemplatesScreen
import dev.velum.app.ui.theme.VelumBg
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.ble.BleConnectionState
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.model.ComputeMode
import dev.velum.core.model.ConnectedDevice
import dev.velum.core.security.InMemorySecureStore
import dev.velum.protocol.DeviceModel
import dev.velum.protocol.SessionEntry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic phone screenshots (~1080×2400 px via w360dp-h800dp @ xxhdpi).
 *
 * Run: `./gradlew :composeApp:recordRoborazziDebug`
 * Then: `./scripts/package-screenshots.sh` → docs/screenshots/{light,dark}/ + fastlane.
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
        context.deleteDatabase("velum.db")
        container = AppContainer(
            secureStore = InMemorySecureStore(),
            driverFactory = DriverFactory(context),
        )
        runBlocking { seedScreenshotFixtures(container) }
    }

    @Test
    fun firstRun() = capture("01-onboarding") {
        FirstRunScreen(
            onPhoneMic = {},
            onImportRecorder = {},
            onConnectPlaud = {},
            onDownloadModels = {},
            onUseApi = {},
            onSkip = {},
        )
    }

    @Test
    fun filesList() = capture("02-home-recordings") {
        FilesScreen(container, onOpenRecording = {}, onOpenSettings = {})
        BottomChrome(ShellTab.Files, fabOpen = false)
    }

    @Test
    fun fabOverlay() = capture("03-fab-overlay") {
        FilesScreen(container, onOpenRecording = {}, onOpenSettings = {})
        FabOverlay(visible = true, onStartRecording = {}, onImportAudio = {}, onDismiss = {})
        BottomChrome(ShellTab.Files, fabOpen = true)
    }

    @Test
    fun recordingInProgress() = capture("04-recording-in-progress") {
        RecordingDetailScreen(container, "rec-live-mic", onBack = {}, initialTab = 0)
    }

    @Test
    fun deviceConnectedWifi() = capture("05-device-connected-wifi") {
        val device = ConnectedDevice(
            id = "note-pro-demo",
            name = "Note Pro",
            serial = "881DEMO01",
            modelCode = DeviceModel.NotePro.code,
            protocolVersion = 20,
            rssi = -48,
            batteryPercent = 76,
        )
        DeviceScreenContent(
            state = BleConnectionState.Connected(device),
            devices = listOf(device),
            sessionUi = DeviceSessionUi(
                batteryPercent = 76,
                sessions = listOf(
                    SessionEntry(sessionId = 1_729_942_000L, sizeBytes = 8_500_000),
                    SessionEntry(sessionId = 1_729_900_000L, sizeBytes = 3_200_000),
                ),
                statusMessage = "Ready · Note Pro · protocol 20 · Wi‑Fi fast path",
                protocolVersion = 20,
                modelDisplayName = "Note Pro",
                wifiFastPath = true,
                recorderDriverId = "plaud",
            ),
            bleAvailable = true,
            onStartScan = {},
            onStopScan = {},
            onDisconnect = {},
            onConnect = {},
        )
    }

    @Test
    fun importHub() = capture("06-import-hub") {
        ImportHubScreen(
            container = container,
            onBack = {},
            onOpenRecording = {},
            onPickWatchedFolder = {},
            onSyncWatchedFolder = { emptyList() },
        )
    }

    @Test
    fun transcriptSpeakers() = capture("07-transcript-speakers") {
        RecordingDetailScreen(container, "rec-demo-1", onBack = {}, initialTab = 0)
    }

    @Test
    fun summaryFromTemplate() = capture("08-summary-template") {
        RecordingDetailScreen(container, "rec-demo-1", onBack = {}, initialTab = 1)
    }

    @Test
    fun nameSpeakersSheet() = capture("09-name-speakers") {
        Box(Modifier.fillMaxSize().background(VelumBg)) {
            SpeakerLabelSheetContent(onClose = {})
        }
    }

    @Test
    fun generateSheet() = capture("10-generate-sheet") {
        Box(Modifier.fillMaxSize().background(VelumBg)) {
            GenerateSheetContent(onClose = {})
        }
    }

    @Test
    fun templates() = capture("11-template-picker") {
        TemplatesScreen(container, onBack = {}, onOpenTemplate = {})
    }

    @Test
    fun voiceProfiles() = capture("12-voice-profiles") {
        VoiceProfilesScreen(container = container, onBack = {})
    }

    @Test
    fun aiProcessing() = capture("13-ai-processing") {
        AiProcessingScreen(container, onBack = {}, onModelManager = {})
    }

    @Test
    fun explore() = capture("14-explore") {
        ExploreScreen(
            container = container,
            onTemplates = {},
            onAutoFlow = {},
            onIntegrations = {},
            onApiSettings = {},
        )
        BottomChrome(ShellTab.Explore, fabOpen = false)
    }

    @Test
    fun autoFlow() = capture("15-autoflow") {
        AutoFlowListScreen(container, onBack = {}, onEdit = {}, onBrowseTemplates = {})
    }

    @Test
    fun integrations() = capture("16-integrations") {
        IntegrationsScreen(container = container, onBack = {})
    }

    @Test
    fun settings() = capture("17-settings") {
        SettingsHubScreen(
            onBack = {},
            onDevices = {},
            onRecording = {},
            onAiProcessing = {},
            onModelManager = {},
            onMemory = {},
            onPersonalization = {},
            onPreferences = {},
            onApi = {},
            onVoiceProfiles = {},
            onTemplates = {},
            onAutoFlow = {},
            onIntegrations = {},
            onWatchedFolders = {},
            onAppearance = {},
            onStoragePrivacy = {},
            onAbout = {},
        )
    }

    @Test
    fun settingsDark() = capture("17b-settings-dark", dark = true) {
        SettingsHubScreen(
            onBack = {},
            onDevices = {},
            onRecording = {},
            onAiProcessing = {},
            onModelManager = {},
            onMemory = {},
            onPersonalization = {},
            onPreferences = {},
            onApi = {},
            onVoiceProfiles = {},
            onTemplates = {},
            onAutoFlow = {},
            onIntegrations = {},
            onWatchedFolders = {},
            onAppearance = {},
            onStoragePrivacy = {},
            onAbout = {},
        )
    }

    @Test
    fun modelManager() = capture("18-model-manager") {
        ModelManagerScreen(container, onBack = {})
    }

    @Test
    fun mindMap() = capture("19-mind-map") {
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

    @Test
    fun brandLauncherLight() = capture("20-brand-launcher-light") {
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
        captureRoboImage("velum-21-brand-splash-dark.png") {
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

    // —— English (light + dark) for 1.0 locale matrix ——

    @Test
    fun enHome() = capture("en-02-home-recordings", strings = En) {
        FilesScreen(container, onOpenRecording = {}, onOpenSettings = {})
        BottomChrome(ShellTab.Files, fabOpen = false)
    }

    @Test
    fun enSettings() = capture("en-17-settings", strings = En) {
        SettingsHubScreen(
            onBack = {},
            onDevices = {},
            onRecording = {},
            onAiProcessing = {},
            onModelManager = {},
            onMemory = {},
            onPersonalization = {},
            onPreferences = {},
            onApi = {},
            onVoiceProfiles = {},
            onTemplates = {},
            onAutoFlow = {},
            onIntegrations = {},
            onWatchedFolders = {},
            onAppearance = {},
            onStoragePrivacy = {},
            onAbout = {},
        )
    }

    @Test
    fun enSummary() = capture("en-08-summary-template", strings = En) {
        RecordingDetailScreen(container, "rec-demo-1", onBack = {}, initialTab = 1)
    }

    @Test
    fun enDarkHome() = capture("en-dark-02-home-recordings", dark = true, strings = En) {
        FilesScreen(container, onOpenRecording = {}, onOpenSettings = {})
        BottomChrome(ShellTab.Files, fabOpen = false)
    }

    @Test
    fun enDarkSettings() = capture("en-dark-17-settings", dark = true, strings = En) {
        SettingsHubScreen(
            onBack = {},
            onDevices = {},
            onRecording = {},
            onAiProcessing = {},
            onModelManager = {},
            onMemory = {},
            onPersonalization = {},
            onPreferences = {},
            onApi = {},
            onVoiceProfiles = {},
            onTemplates = {},
            onAutoFlow = {},
            onIntegrations = {},
            onWatchedFolders = {},
            onAppearance = {},
            onStoragePrivacy = {},
            onAbout = {},
        )
    }

    // —— Dark variants (1080×2400) ——

    @Test
    fun darkOnboarding() = capture("dark-01-onboarding", dark = true) {
        FirstRunScreen(
            onPhoneMic = {},
            onImportRecorder = {},
            onConnectPlaud = {},
            onDownloadModels = {},
            onUseApi = {},
            onSkip = {},
        )
    }

    @Test
    fun darkHome() = capture("dark-02-home-recordings", dark = true) {
        FilesScreen(container, onOpenRecording = {}, onOpenSettings = {})
        BottomChrome(ShellTab.Files, fabOpen = false)
    }

    @Test
    fun darkImportHub() = capture("dark-06-import-hub", dark = true) {
        ImportHubScreen(
            container = container,
            onBack = {},
            onOpenRecording = {},
            onPickWatchedFolder = {},
            onSyncWatchedFolder = { emptyList() },
        )
    }

    @Test
    fun darkTranscript() = capture("dark-07-transcript-speakers", dark = true) {
        RecordingDetailScreen(container, "rec-demo-1", onBack = {}, initialTab = 0)
    }

    @Test
    fun darkSummary() = capture("dark-08-summary-template", dark = true) {
        RecordingDetailScreen(container, "rec-demo-1", onBack = {}, initialTab = 1)
    }

    @Test
    fun darkTemplates() = capture("dark-10-templates", dark = true) {
        TemplatesScreen(container, onBack = {}, onOpenTemplate = {})
    }

    @Test
    fun darkVoiceProfiles() = capture("dark-12-voice-profiles", dark = true) {
        VoiceProfilesScreen(container, onBack = {})
    }

    @Test
    fun darkAiProcessing() = capture("dark-13-ai-processing", dark = true) {
        AiProcessingScreen(container, onBack = {}, onModelManager = {})
    }

    @Test
    fun darkSettings() = capture("dark-17-settings", dark = true) {
        SettingsHubScreen(
            onBack = {},
            onDevices = {},
            onRecording = {},
            onAiProcessing = {},
            onModelManager = {},
            onMemory = {},
            onPersonalization = {},
            onPreferences = {},
            onApi = {},
            onVoiceProfiles = {},
            onTemplates = {},
            onAutoFlow = {},
            onIntegrations = {},
            onWatchedFolders = {},
            onAppearance = {},
            onStoragePrivacy = {},
            onAbout = {},
        )
    }

    @Test
    fun darkModelManager() = capture("dark-18-model-manager", dark = true) {
        ModelManagerScreen(container, onBack = {})
    }

    @Test
    fun darkDevice() = capture("dark-05-device-connected-wifi", dark = true) {
        val device = ConnectedDevice(
            id = "note-pro-demo",
            name = "Note Pro",
            serial = "881DEMO01",
            modelCode = DeviceModel.NotePro.code,
            protocolVersion = 20,
            rssi = -48,
            batteryPercent = 76,
        )
        DeviceScreenContent(
            state = BleConnectionState.Connected(device),
            devices = listOf(device),
            sessionUi = DeviceSessionUi(
                batteryPercent = 76,
                sessions = listOf(
                    SessionEntry(sessionId = 1_729_942_000L, sizeBytes = 8_500_000),
                ),
                statusMessage = "Ready · Note Pro · protocol 20 · Wi‑Fi fast path",
                protocolVersion = 20,
                modelDisplayName = "Note Pro",
                wifiFastPath = true,
                recorderDriverId = "plaud",
            ),
            bleAvailable = true,
            onStartScan = {},
            onStopScan = {},
            onDisconnect = {},
            onConnect = {},
        )
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

    private fun capture(
        name: String,
        dark: Boolean = false,
        strings: Strings = PtBr,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage("velum-$name.png") {
            CompositionLocalProvider(
                LocalStrings provides strings,
                LocalDensity provides Density(density = 3f, fontScale = 1f),
            ) {
                VelumTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(VelumBg)) {
                        content()
                    }
                }
            }
        }
    }

    private suspend fun seedScreenshotFixtures(container: AppContainer) {
        val now = 1_729_942_755L
        container.repository.insertRecording(
            id = "rec-live-mic",
            sessionId = now,
            folderId = null,
            title = "Gravação ao vivo",
            source = "phone",
            deviceSerial = null,
            startedAt = now - 90,
            durationSeconds = 90,
            sizeBytes = 1_440_000,
            localPath = null,
            transcript = """
                **Heitor** · 0:12
                Vamos gravar pelo microfone do telefone e revisar o pipeline local.

                **Marina** · 0:28
                Combinado — Parakeet na transcrição e Gemma E4B no resumo.

                **Heitor** · 0:41
                Se o modelo local falhar, caímos para a API só nessa tarefa.
            """.trimIndent(),
            summaryMarkdown = null,
            status = "recording",
            createdAt = now - 90,
        )
        container.repository.updateSpeakers(
            "rec-demo-1",
            listOf(
                VelumRepository.SpeakerUpsert("spk-1", "S1", "Heitor", 95),
                VelumRepository.SpeakerUpsert("spk-2", "S2", "Marina", 88),
            ),
        )
        container.settingsRepository.load()
        container.settingsRepository.setTranscriptionMode(ComputeMode.Local)
        container.settingsRepository.setDiarizationMode(ComputeMode.Local)
        container.settingsRepository.setSummaryMode(ComputeMode.Api)
        container.settingsRepository.setBaseUrl("https://api.openai.com/v1")
        // Demo voice profiles (tiny synthetic embeddings — UI list only).
        val emb = FloatArray(192) { i -> (i % 17) / 17f }
        container.voiceProfileStore.upsert("Heitor", emb, role = "Product", company = "Velum")
        container.voiceProfileStore.upsert(
            "Marina",
            FloatArray(192) { i -> ((i + 3) % 19) / 19f },
            role = "Engineering",
            company = "Velum",
        )
    }
}
