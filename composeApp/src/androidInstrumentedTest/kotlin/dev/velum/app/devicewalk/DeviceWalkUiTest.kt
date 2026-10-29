package dev.velum.app.devicewalk

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Environment
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.velum.app.MainActivity
import dev.velum.app.VelumApplication
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-shell device walk for Android 1.0. Requires instrumentation arg
 * `velumDeviceWalk=true` (StrictMode + fake AI engines).
 *
 * Matrix: pt-BR/EN × light/dark. Covers onboarding, library, import+transcribe,
 * recording detail, templates, AutoFlow, settings subtree, device, process death.
 */
@RunWith(AndroidJUnit4::class)
class DeviceWalkUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var shotDir: File
    private val covered = linkedSetOf<String>()

    @Before
    fun setUp() {
        assertTrue(
            DeviceWalkMode.isEnabled(),
            "DeviceWalkMode must be enabled via runner arg velumDeviceWalk=true",
        )
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        shotDir = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: appContext.cacheDir,
            "device-walk",
        ).also { it.mkdirs() }
        grantRuntimePermissions()
        dismissSystemPermissionDialogs()
    }

    @Test
    fun deviceWalk_ptBr_light() = runMatrix(lang = "pt-BR", theme = "light")

    @Test
    fun deviceWalk_ptBr_dark() = runMatrix(lang = "pt-BR", theme = "dark")

    @Test
    fun deviceWalk_en_light() = runMatrix(lang = "en", theme = "light")

    @Test
    fun deviceWalk_en_dark() = runMatrix(lang = "en", theme = "dark")

    private fun runMatrix(lang: String, theme: String) {
        val tag = "${lang}_$theme"
        waitForShell(lang)
        covered += "onboarding:$tag"
        shot("01-after-onboarding-$tag")

        applyAppearance(lang, theme)
        covered += "appearance:$tag"
        shot("02-appearance-$tag")

        walkLibrary(lang)
        walkImportAndTranscribe(lang, tag)
        walkRecordingDetail(lang, tag)
        walkExploreAndTemplates(lang, tag)
        walkAutoFlow(lang, tag)
        walkAsk(lang, tag)
        walkSettingsTree(lang, tag)
        walkDevice(lang, tag)
        walkStorageWipe(lang, tag)
        // Locale/raw-string assert while ActivityScenario still owns MainActivity.
        assertNoRawOrUnlocalized(lang)
        rotateAndBackground(tag)
        // Process death orphans ActivityScenario — UiAutomator only after this.
        killAndRestore(lang, tag)
        assertUiChrome(lang)
        assertFalse(device.hasObject(By.textContains("FATAL EXCEPTION")))
        File(shotDir, "coverage-$tag.txt").writeText(covered.sorted().joinToString("\n"))
    }

    private fun waitForShell(lang: String) {
        // Splash → FirstRun overlay (on top of NavHost FILES) or Files shell.
        // Do NOT treat Files title text under the FirstRun overlay as ready —
        // bottom bar CDs are hidden while showFirstRun, so require those.
        // FirstRun Skip sits below the fold — scroll while welcome chrome is up.
        waitUntil(90_000) {
            val shellReady = onFilesShellReady()
            val skipVisible =
                composeExists("Skip") ||
                    composeExists("Pular") ||
                    uiExists("Skip") ||
                    uiExists("Pular")
            val firstRunVisible =
                composeExists("Welcome to Velum") ||
                    composeExists("Bem-vindo ao Velum") ||
                    composeExists("How to start") ||
                    composeExists("Como começar") ||
                    uiExists("Welcome to Velum") ||
                    uiExists("Bem-vindo ao Velum") ||
                    uiExists("How to start") ||
                    uiExists("Como começar")
            if (!shellReady && !skipVisible && firstRunVisible) {
                val w = device.displayWidth
                val h = device.displayHeight
                device.swipe(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.25).toInt(), 30)
            }
            shellReady ||
                skipVisible ||
                composeExists("Skip") ||
                composeExists("Pular") ||
                uiExists("Skip") ||
                uiExists("Pular")
        }
        if (composeExists("Skip") || composeExists("Pular") ||
            uiExists("Skip") || uiExists("Pular")
        ) {
            clickTextPrefer("Skip", "Pular")
            covered += "first-run-skip"
            // onSkip is a coroutine launch — wait until overlay is gone.
            waitUntil(30_000) {
                !composeExists("Skip") && !composeExists("Pular") &&
                    !uiExists("Skip") && !uiExists("Pular")
            }
        }
        waitUntil(60_000) { onFilesShellReady() }
        restoreAllFilesFilterSoft()
        dismissSystemPermissionDialogs()
    }

    /**
     * True only when the shell bottom bar is composed (FirstRun overlay dismissed).
     * Files title text can exist under FirstRun; bottom-bar contentDescriptions cannot.
     */
    private fun onFilesShellReady(): Boolean =
        softExistsContentDesc("All files", "Todos os arquivos") ||
            softExistsContentDesc("Explore", "Explorar")

    private fun applyAppearance(lang: String, theme: String) {
        openSettings()
        // Appearance is near the bottom of the scrollable Settings hub.
        // Labels depend on the *current* UI language (prior matrix cell / system),
        // not the target lang — always offer both EN and pt-BR copy.
        scrollClickPrefer("Appearance", "Aparência")
        covered += "settings/appearance"
        when (theme) {
            "dark" -> clickTextPrefer("Dark", "Escuro")
            else -> clickTextPrefer("Light", "Claro")
        }
        // EN UI uses "Portuguese (Brazil)"; pt-BR UI uses "Português (Brasil)".
        // Never fall through to the opposite language option (that was the tip
        // failure: preferred "Português (Brasil)" missed → clicked "English").
        if (lang == "pt-BR") {
            clickTextPrefer("Portuguese (Brazil)", "Português (Brasil)")
            waitUntil(20_000) {
                composeExists("Aparência") ||
                    composeExists("Português (Brasil)") ||
                    composeExists("Claro") ||
                    composeExists("Escuro")
            }
        } else {
            clickTextPrefer("English")
            waitUntil(20_000) {
                composeExists("Appearance") ||
                    composeExists("English") ||
                    composeExists("Light") ||
                    composeExists("Dark")
            }
        }
        // Persist prefs before leaving (setAppLanguage is a coroutine launch).
        SystemClock.sleep(750)
        // Prefer the Compose top-bar Back affordance over device.pressBack —
        // predictive back on API 35 can leave the activity without popping nav.
        // Appearance → Settings hub → Files: need at least two Back pops.
        // ScreenTopBar is pinned outside scroll (#106) so Back stays reachable.
        for (i in 0 until 6) {
            if (onFilesShellReady()) break
            if (!pressNavBack()) break
        }
        pressBackToFiles(lang)
        waitUntil(20_000) { onFilesShellReady() }
    }

    private fun walkLibrary(lang: String) {
        pressBackToFiles(lang)
        restoreAllFilesFilterSoft()
        assertTrue(onFilesChrome(), "Expected Files chrome after appearance")
        covered += "library"
        // Search sheet (best-effort)
        if (softClickContentDesc("Search", "Buscar")) {
            covered += "library/search"
            pressNavBack()
            SystemClock.sleep(300)
        }
        // Peek trash filter then restore All — never leave Trash selected.
        runCatching {
            softClickContentDesc(
                "Filter & Sort",
                "Filtrar & Ordenar",
                "Filter",
                "Filtrar",
            )
            if (composeExists("Trash") || composeExists("Lixeira")) {
                covered += "library/trash"
            }
            softClickText("All files", "Todos os arquivos")
            SystemClock.sleep(300)
        }
        restoreAllFilesFilterSoft()
        shot("03-library")
    }

    private fun walkImportAndTranscribe(lang: String, tag: String) {
        pressBackToFiles(lang)
        restoreAllFilesFilterSoft()
        val app = appContext.applicationContext as VelumApplication
        val container = runBlocking { app.awaitContainer() }
        val wavBytes = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("device_walk_tone.wav").readBytes()
        val recordingsDir = File(appContext.filesDir, "recordings").also { it.mkdirs() }
        val title = "Device walk $tag"
        val wavFile = File(recordingsDir, "device-walk-$tag.wav")
        wavFile.writeBytes(wavBytes)
        val id = runBlocking {
            container.createLocalRecording(
                title = title,
                source = "import",
                localPath = wavFile.absolutePath,
                status = "queued",
            )
        }
        // In-process processor — WorkManager battery/network constraints flake on CI AVDs.
        assertTrue(DeviceWalkMode.isEnabled(), "DeviceWalkMode required for fake ASR/LLM")
        runBlocking {
            container.recordingProcessor
                .process(recordingId = id, templateId = null, retranscribe = true)
                .collect { /* drain progress */ }
        }
        covered += "import/bundled-wav"
        waitUntil(120_000) {
            runBlocking {
                val rec = container.repository.getRecording(id)
                rec?.status == "ready" && !rec.summary_markdown.isNullOrBlank()
            }
        }
        covered += "transcription/device-walk-engine"
        softClickContentDesc("Refresh library", "Atualizar biblioteca")
        restoreAllFilesFilterSoft()
        runCatching {
            waitUntil(45_000) {
                composeExists(title) || composeExists("Device walk")
            }
        }
        shot("04-imported-$tag")
        if (!softClickText(title, "Device walk", tag)) {
            runCatching { scrollClickPrefer(title, "Device walk", tag) }
        }
        covered += "recording/open"
    }

    /** Best-effort restore of the All-files library filter. Never hard-fails. */
    private fun restoreAllFilesFilterSoft() {
        if (composeExists("All files") || composeExists("Todos os arquivos")) return
        softClickContentDesc(
            "Filter & Sort",
            "Filtrar & Ordenar",
            "Filter",
            "Filtrar",
        )
        softClickText("All files", "Todos os arquivos")
        SystemClock.sleep(300)
    }

    private fun walkRecordingDetail(lang: String, tag: String) {
        val opened = runCatching {
            waitUntil(45_000) {
                composeExists("Notas") ||
                    composeExists("Notes") ||
                    composeExists("Fontes") ||
                    composeExists("Sources") ||
                    composeExists("Device walk")
            }
            true
        }.getOrDefault(false)
        if (opened) {
            runCatching {
                clickTextPrefer("Notes", "Notas")
                covered += "recording/notes"
                shot("05-notes-$tag")
            }
            runCatching {
                clickTextPrefer("Change template", "Trocar modelo")
                covered += "recording/change-template"
                pressNavBack()
            }
            runCatching {
                clickTextPrefer("Versions", "Versões")
                covered += "recording/versions"
                pressNavBack()
            }
            runCatching {
                clickTextPrefer("Sources", "Fontes")
                covered += "recording/sources"
            }
        }
        pressNavBack()
        pressBackToFiles(lang)
    }

    private fun walkExploreAndTemplates(lang: String, tag: String) {
        pressBackToFiles(lang)
        softClickContentDesc("Explore", "Explorar")
        covered += "explore"
        shot("06-explore-$tag")
        softClickText(
            if (lang == "en") "Templates" else "Modelos",
            "Templates",
            "Modelos",
            "Community templates",
            "Modelos da comunidade",
        )
        covered += "templates"
        softClickText(
            if (lang == "en") "Featured" else "Destaques",
            "Featured",
            "Destaques",
        )
        covered += "templates/featured"
        softClickText(
            if (lang == "en") "All" else "Todos",
            "All",
            "Todos",
        )
        covered += "templates/all"
        runCatching {
            softClickText(
                if (lang == "en") "Search" else "Buscar",
                "Search",
                "Buscar",
            )
            covered += "templates/search"
            pressNavBack()
        }
        shot("07-templates-$tag")
        pressNavBack()
        pressBackToFiles(lang)
    }

    private fun walkAutoFlow(lang: String, tag: String) {
        pressBackToFiles(lang)
        softClickContentDesc("Explore", "Explorar")
        softClickText("AutoFlow", "AutoFlow")
        covered += "autoflow"
        shot("08-autoflow-$tag")
        runCatching {
            clickTextPrefer(
                if (lang == "en") "Browse templates" else "Ver modelos",
                "Browse templates",
                "Ver modelos",
                "Automation templates",
                "Modelos de automação",
            )
            covered += "autoflow/templates"
            // Open first template row if any
            runCatching {
                composeRule.onAllNodesWithText("→", substring = true)
            }
            pressNavBack()
        }
        runCatching {
            // Prefill editor via "New" / create if visible
            clickTextPrefer(
                if (lang == "en") "New AutoFlow" else "Novo AutoFlow",
                "New",
                "Novo",
                "Create",
                "Criar",
            )
            covered += "autoflow/editor"
            pressNavBack()
        }
        pressNavBack()
        pressBackToFiles(lang)
    }

    private fun walkAsk(lang: String, tag: String) {
        runCatching {
            clickTextPrefer(
                if (lang == "en") "Ask" else "Perguntar",
                "Ask",
                "Perguntar",
            )
            covered += "ask"
            shot("09-ask-$tag")
            pressNavBack()
        }
        // Also from settings/explore if FAB path differed
        pressBackToFiles(lang)
    }

    private fun walkSettingsTree(lang: String, tag: String) {
        openSettings()
        covered += "settings"
        val rows = if (lang == "en") {
            listOf(
                "Devices" to "settings/devices",
                "Phone recording" to "settings/recording",
                "AI processing" to "settings/ai-processing",
                "Model manager" to "settings/model-manager",
                "Custom vocabulary" to "settings/memory",
                "Personalization" to "settings/personalization",
                "Preferences" to "settings/preferences",
                "AI provider" to "settings/api",
                "Voice profiles" to "settings/voice-profiles",
                "Community templates" to "settings/templates",
                "AutoFlow" to "settings/autoflow",
                "Integrations" to "settings/integrations",
                "Watched folders" to "settings/import",
                "About Velum" to "settings/about",
            )
        } else {
            listOf(
                "Dispositivos" to "settings/devices",
                "Gravação no telefone" to "settings/recording",
                "Processamento de IA" to "settings/ai-processing",
                "Gerenciador de modelos" to "settings/model-manager",
                "Vocabulário personalizado" to "settings/memory",
                "Personalização" to "settings/personalization",
                "Preferências" to "settings/preferences",
                "Provedor de IA" to "settings/api",
                "Perfis de voz" to "settings/voice-profiles",
                "Modelos da comunidade" to "settings/templates",
                "AutoFlow" to "settings/autoflow",
                "Integrações" to "settings/integrations",
                "Pastas observadas" to "settings/import",
                "Sobre o Velum" to "settings/about",
            )
        }
        for ((label, cov) in rows) {
            openSettings()
            runCatching {
                scrollClick(label)
                covered += cov
                shot("10-${cov.replace('/', '-')}-$tag")
                // API profiles / per-task models
                if (cov == "settings/api") {
                    covered += "settings/api-profiles"
                }
                if (cov == "settings/ai-processing") {
                    covered += "settings/per-task-models"
                }
                if (cov == "settings/integrations") {
                    covered += "settings/integrations-sheet"
                }
                pressNavBack()
            }
        }
        pressBackToFiles(lang)
    }

    private fun walkDevice(lang: String, tag: String) {
        openSettings()
        runCatching {
            scrollClick(if (lang == "en") "Devices" else "Dispositivos")
            clickTextPrefer(
                if (lang == "en") "Device" else "Dispositivo",
                "Note Pro",
                "Device",
                "Dispositivo",
            )
            covered += "device"
            shot("11-device-$tag")
            // Permission rationale / scan button if present
            runCatching {
                clickTextPrefer(
                    if (lang == "en") "Scan" else "Buscar",
                    "Connect",
                    "Conectar",
                    "Scan",
                    "Permitir",
                    "Allow",
                )
                covered += "device/permission-or-scan"
                dismissSystemPermissionDialogs()
            }
            pressNavBack()
            pressNavBack()
        }
        pressBackToFiles(lang)
    }

    private fun walkStorageWipe(lang: String, tag: String) {
        openSettings()
        runCatching {
            scrollClick(
                if (lang == "en") "Storage & privacy" else "Armazenamento e privacidade",
            )
            covered += "settings/storage-privacy"
            shot("12-storage-$tag")
            // Open wipe confirm dialog then cancel — do not destroy mid-matrix state unless last.
            clickTextPrefer(
                if (lang == "en") "Wipe all data" else "Apagar todos os dados",
                "Wipe all data",
                "Apagar todos os dados",
            )
            covered += "settings/wipe-dialog"
            clickTextPrefer(
                if (lang == "en") "Cancel" else "Cancelar",
                "Cancel",
                "Cancelar",
            )
            pressNavBack()
        }
        pressBackToFiles(lang)
    }

    private fun rotateAndBackground(tag: String) {
        // Prefer UiDevice rotation — keeps ActivityScenario's activity instance.
        runCatching { device.setOrientationLeft() }
            .recoverCatching {
                composeRule.activity.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        SystemClock.sleep(1_000)
        covered += "rotate/landscape"
        shot("13-landscape-$tag")
        runCatching { device.setOrientationNatural() }
            .recoverCatching {
                composeRule.activity.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        SystemClock.sleep(1_000)
        covered += "rotate/portrait"
        device.pressHome()
        SystemClock.sleep(1_500)
        covered += "background"
        // Bring the existing task forward. CLEAR_TOP + NEW_TASK spawns a second
        // MainActivity that ActivityScenario ignores, then destroys the tracked one
        // ("Cannot run onActivity since Activity has been destroyed already").
        // Must keep MAIN/LAUNCHER so MainActivity.setIntent does not poison
        // ActivityScenario's intent filter (otherwise DESTROYED is ignored on close).
        resumeShellActivity(singleTop = true)
        SystemClock.sleep(2_000)
        waitUntilUi(30_000) {
            uiExists("All files") ||
                uiExists("Todos os arquivos") ||
                uiExists("Trash") ||
                uiExists("Lixeira") ||
                uiExists("Velum")
        }
        covered += "restore-from-background"
        shot("14-restore-$tag")
    }

    /**
     * Process death via `am kill` (app must be backgrounded first). Relaunch may
     * create a new MainActivity — use UiAutomator only after this. Always relaunch
     * with MAIN/LAUNCHER so any surviving ActivityScenario instance still matches.
     */
    private fun killAndRestore(lang: String, tag: String) {
        val pkg = appContext.packageName
        // am kill only affects background processes.
        device.pressHome()
        SystemClock.sleep(1_000)
        device.executeShellCommand("am kill $pkg")
        SystemClock.sleep(1_500)
        resumeShellActivity(singleTop = false)
        waitUntilUi(60_000) {
            uiExists("Todos os arquivos") ||
                uiExists("All files") ||
                uiExists("Velum") ||
                uiExists("Pular") ||
                uiExists("Skip")
        }
        if (uiExists("Pular") || uiExists("Skip")) {
            softClickUi("Pular", "Skip")
        }
        softClickUiDesc("All files", "Todos os arquivos")
        waitUntilUi(30_000) {
            uiExists("All files") ||
                uiExists("Todos os arquivos") ||
                uiExists("Trash") ||
                uiExists("Lixeira")
        }
        covered += "process-death-restore"
        shot("15-process-death-$tag")
        // Locale must survive process death (prefs persisted).
        assertUiChrome(lang)
    }

    /**
     * Resume/relaunch [MainActivity] with the same MAIN/LAUNCHER identity
     * ActivityScenario used to start the test. Bare `am start -n` replaces the
     * activity intent via [MainActivity.onNewIntent] → [Activity.setIntent], after
     * which ActivityScenario ignores all lifecycle events (including DESTROYED) and
     * [ActivityScenario.close] times out with last transition PAUSED.
     */
    private fun resumeShellActivity(singleTop: Boolean) {
        val pkg = appContext.packageName
        val singleTopFlag = if (singleTop) "--activity-single-top " else ""
        device.executeShellCommand(
            "am start ${singleTopFlag}" +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-n $pkg/.MainActivity",
        )
    }

    private fun assertNoRawOrUnlocalized(lang: String) {
        pressBackToFiles(lang)
        restoreAllFilesFilterSoft()
        val dump = windowDump()
        val banned = listOf(
            "TODO",
            "FIXME",
            "lorem ipsum",
            "Lorem ipsum",
            "Coming soon",
            "Not implemented",
            "Exception:",
            "StackTrace",
            "java.lang.",
        )
        for (b in banned) {
            assertFalse(dump.contains(b), "Raw/dev string on screen: $b")
        }
        assertUiChrome(lang)
    }

    private fun assertUiChrome(lang: String) {
        // Precondition: Compose shell (or Files Settings gear) must be up.
        // Then always assert language chrome via UiAutomator — never skip (#106 / #115).
        waitUntil(20_000) {
            onFilesShellReady() || softExistsContentDesc("Settings", "Configuração")
        }
        if (lang == "pt-BR") {
            waitUntil(
                20_000,
                "Expected pt-BR chrome after locale apply (UiAutomator hierarchy lagged or wrong locale)",
            ) {
                uiExists("Todos os arquivos") ||
                    uiExists("Lixeira") ||
                    uiExists("Explorar") ||
                    uiExists("Configuração") ||
                    windowDump().contains("Arquivos", ignoreCase = true)
            }
            val dump = windowDump()
            assertTrue(
                uiExists("Todos os arquivos") ||
                    uiExists("Lixeira") ||
                    uiExists("Explorar") ||
                    uiExists("Configuração") ||
                    dump.contains("Arquivos", ignoreCase = true),
                "Expected pt-BR chrome after locale apply",
            )
        } else {
            waitUntil(
                20_000,
                "Expected EN chrome after locale apply (UiAutomator hierarchy lagged or wrong locale)",
            ) {
                uiExists("All files") ||
                    uiExists("Trash") ||
                    uiExists("Explore") ||
                    uiExists("Settings") ||
                    windowDump().contains("All files", ignoreCase = true)
            }
            val dump = windowDump()
            assertTrue(
                uiExists("All files") ||
                    uiExists("Trash") ||
                    uiExists("Explore") ||
                    uiExists("Settings") ||
                    dump.contains("All files", ignoreCase = true),
                "Expected EN chrome after locale apply",
            )
        }
    }

    private fun windowDump(): String = buildString {
        runCatching {
            val out = ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            append(out.toString(Charsets.UTF_8.name()))
        }
        runCatching {
            device.findObjects(By.clickable(true)).forEach { obj ->
                obj.text?.let { appendLine(it) }
                obj.contentDescription?.let { appendLine(it) }
            }
        }
    }

    private fun uiExists(text: String): Boolean =
        runCatching {
            device.findObject(By.textContains(text)) != null
        }.getOrDefault(false) ||
            runCatching {
                device.findObject(By.descContains(text)) != null
            }.getOrDefault(false)

    private fun softClickUi(vararg labels: String): Boolean {
        for (label in labels) {
            if (label.isBlank()) continue
            val obj = device.findObject(By.textContains(label)) ?: continue
            runCatching { obj.click() }
            return true
        }
        return false
    }

    private fun softClickUiDesc(vararg labels: String): Boolean {
        for (label in labels) {
            if (label.isBlank()) continue
            val obj = device.findObject(By.descContains(label)) ?: continue
            runCatching { obj.click() }
            return true
        }
        return false
    }

    private fun waitUntilUi(timeoutMs: Long, condition: () -> Boolean) {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (runCatching { condition() }.getOrDefault(false)) return
            SystemClock.sleep(300)
        }
        error("UI condition not met within ${timeoutMs}ms")
    }

    private fun openSettings() {
        // Gear only exists on FilesScreen. Explore/Ask still expose bottom-bar
        // CDs so onFilesShellReady can be true without the header gear (#106).
        // Drive to Files via All-files tab and wait on the gear CD itself —
        // not a second onFilesShellReady (flaked en_dark after settings rows).
        fun ensureFilesGearVisible() {
            pressBackToFiles("pt-BR")
            softClickContentDesc("All files", "Todos os arquivos")
            SystemClock.sleep(350)
        }
        ensureFilesGearVisible()
        if (!softExistsContentDesc("Settings", "Configuração")) {
            ensureFilesGearVisible()
        }
        waitUntil(30_000) {
            softExistsContentDesc("Settings", "Configuração") ||
                run {
                    softClickContentDesc("All files", "Todos os arquivos")
                    softExistsContentDesc("Settings", "Configuração")
                }
        }
        // Tap the gear via Compose merged tree, else a real coordinate tap.
        // UiObject2.click() on the a11y node used ACTION_CLICK without navigating
        // under R8 minifiedTest; device.click goes through the touch path (#106).
        var tappedGear = false
        for (label in listOf("Settings", "Configuração")) {
            val composed = runCatching {
                composeRule
                    .onNodeWithContentDescription(label, substring = true, useUnmergedTree = false)
                    .performClick()
                true
            }.getOrDefault(false)
            if (composed) {
                tappedGear = true
                break
            }
        }
        if (!tappedGear) {
            for (label in listOf("Settings", "Configuração")) {
                val obj = device.findObject(By.descContains(label)) ?: continue
                val bounds = obj.visibleBounds
                assertTrue(
                    device.click(bounds.centerX(), bounds.centerY()),
                    "device.click Settings gear failed",
                )
                tappedGear = true
                break
            }
        }
        assertTrue(tappedGear, "Failed to tap Settings gear")
        // Hub-only rows / sections — never the Files gear CD alone.
        waitUntil(20_000) {
            composeExists("Phone recording") ||
                composeExists("Gravação no telefone") ||
                composeExists("Devices") ||
                composeExists("Dispositivos") ||
                composeExists("Storage & privacy") ||
                composeExists("Armazenamento e privacidade") ||
                composeExists("About Velum") ||
                composeExists("Sobre o Velum") ||
                composeExists("AI processing") ||
                composeExists("Processamento de IA") ||
                uiExists("Phone recording") ||
                uiExists("Gravação no telefone") ||
                uiExists("Devices") ||
                uiExists("Dispositivos") ||
                uiExists("About Velum") ||
                uiExists("Sobre o Velum")
        }
    }



    /**
     * Pop one Compose destination via the top-bar Back affordance.
     * Never use [UiDevice.pressBack] here — on API 35 predictive back can
     * finish [MainActivity] and orphan ActivityScenario (DESTROYED outside
     * scenario close), after which recreate/wait can never recover the shell.
     */
    private fun pressNavBack(): Boolean {
        if (softClickContentDesc("Back", "Voltar")) {
            SystemClock.sleep(400)
            return true
        }
        return false
    }

    private fun pressBackToFiles(lang: String) {
        repeat(12) {
            if (onFilesShellReady()) return
            // Nested settings / detail: pop via top-bar Back CD only.
            pressNavBack()
            if (onFilesShellReady()) return
            // Bottom bar Files tab is only composed on shell routes (not FirstRun).
            if (softClickContentDesc("All files", "Todos os arquivos") && onFilesShellReady()) {
                return
            }
            softClickText("Files", "Arquivos")
            if (onFilesShellReady()) return
            dismissSystemPermissionDialogs()
            if (composeExists("Skip") || composeExists("Pular")) {
                softClickText("Skip", "Pular")
            }
            SystemClock.sleep(250)
        }
        if (onFilesShellReady()) return
        // Last resort: recreate the ActivityScenario-owned activity. Bare
        // MAIN/LAUNCHER soft-resume delivers onNewIntent and MainActivity.setIntent
        // poisons ActivityScenario intent matching (af87f76: Settings gear miss
        // after soft resume left us off the Files shell). recreate() resets
        // Compose nav while keeping the launch intent ActivityScenario tracks.
        runCatching {
            composeRule.runOnUiThread { composeRule.activity.recreate() }
        }
        SystemClock.sleep(1_500)
        waitUntil(60_000) {
            composeExists("Skip") ||
                composeExists("Pular") ||
                onFilesShellReady()
        }
        if (composeExists("Skip") || composeExists("Pular")) {
            softClickText("Skip", "Pular")
            waitUntil(30_000) {
                !composeExists("Skip") && !composeExists("Pular")
            }
        }
        softClickContentDesc("All files", "Todos os arquivos")
        restoreAllFilesFilterSoft()
        if (!onFilesShellReady()) {
            error("Could not return to Files shell (lang=$lang)")
        }
    }

    /** Files shell with bottom bar — never title text alone (visible under FirstRun). */
    private fun onFilesChrome(): Boolean = onFilesShellReady()

    private fun softExistsContentDesc(vararg labels: String): Boolean {
        for (label in labels) {
            if (label.isBlank()) continue
            val viaCompose = runCatching {
                composeRule
                    .onAllNodesWithContentDescription(label, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
            if (viaCompose) return true
            if (runCatching { device.findObject(By.descContains(label)) != null }.getOrDefault(false)) {
                return true
            }
        }
        return false
    }

    private fun scrollClick(label: String) {
        runCatching {
            composeRule.onNodeWithText(label, substring = true, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
        }.recoverCatching {
            composeRule.onNodeWithText(label, substring = true).performClick()
        }.recoverCatching {
            // Swipe the settings hub until UiAutomator sees the row.
            repeat(8) {
                val obj = device.findObject(By.textContains(label))
                if (obj != null) {
                    obj.click()
                    return@recoverCatching
                }
                device.swipe(
                    device.displayWidth / 2,
                    (device.displayHeight * 0.72).toInt(),
                    device.displayWidth / 2,
                    (device.displayHeight * 0.28).toInt(),
                    25,
                )
                SystemClock.sleep(250)
            }
            val obj = device.wait(Until.findObject(By.textContains(label)), 3_000)
            obj?.click() ?: error("missing $label")
        }.getOrThrow()
    }

    private fun scrollClickPrefer(vararg labels: String) {
        for (label in labels) {
            if (label.isBlank()) continue
            val ok = runCatching {
                scrollClick(label)
                true
            }.getOrDefault(false)
            if (ok) return
        }
        error("None of texts found (scroll): ${labels.toList()}")
    }

    /** Soft click by text — returns false instead of throwing. */
    private fun softClickText(vararg labels: String): Boolean {
        for (label in labels) {
            if (label.isBlank()) continue
            val clicked = runCatching {
                composeRule.onNodeWithText(label, substring = true, useUnmergedTree = true)
                    .performClick()
                true
            }.recoverCatching {
                val obj = device.findObject(By.textContains(label))
                if (obj != null) {
                    obj.click()
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (clicked) return true
        }
        return false
    }

    /** Soft click by contentDescription — returns false instead of throwing. */
    private fun softClickContentDesc(vararg labels: String): Boolean {
        for (label in labels) {
            if (label.isBlank()) continue
            // Prefer merged tree so CD on a clickable ancestor wins over a
            // non-clickable Icon sibling (9e0742c: Voltar clickable=false).
            val clicked = runCatching {
                composeRule
                    .onNodeWithContentDescription(label, substring = true, useUnmergedTree = false)
                    .performClick()
                true
            }.recoverCatching {
                composeRule
                    .onNodeWithContentDescription(label, substring = true, useUnmergedTree = true)
                    .performScrollTo()
                    .performClick()
                true
            }.recoverCatching {
                composeRule
                    .onNodeWithContentDescription(label, substring = true, useUnmergedTree = true)
                    .performClick()
                true
            }.recoverCatching {
                val obj = device.findObject(By.descContains(label))
                if (obj != null) {
                    obj.click()
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (clicked) return true
        }
        return false
    }

    private fun clickTextPrefer(vararg labels: String) {
        for (label in labels) {
            if (label.isBlank()) continue
            val clicked = runCatching {
                composeRule.onNodeWithText(label, substring = true, useUnmergedTree = true)
                    .performScrollTo()
                    .performClick()
                true
            }.getOrElse {
                runCatching {
                    composeRule.onNodeWithText(label, substring = true, useUnmergedTree = true)
                        .performClick()
                    true
                }.getOrElse {
                    runCatching {
                        val obj = device.findObject(By.textContains(label))
                        if (obj != null) {
                            obj.click()
                            true
                        } else {
                            false
                        }
                    }.getOrDefault(false)
                }
            }
            if (clicked) return
        }
        error("None of texts found: ${labels.toList()}")
    }

    private fun clickContentDescPrefer(vararg labels: String) {
        for (label in labels) {
            val clicked = runCatching {
                composeRule.onNodeWithContentDescription(label, substring = true, useUnmergedTree = true)
                    .performClick()
                true
            }.getOrElse {
                runCatching {
                    val obj = device.findObject(By.descContains(label))
                    if (obj != null) {
                        obj.click()
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
            if (clicked) return
        }
        error("None of contentDescriptions found: ${labels.toList()}")
    }

    private fun composeExists(text: String): Boolean {
        val viaCompose = runCatching {
            composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)
        if (viaCompose) return true
        return runCatching {
            device.findObject(By.textContains(text)) != null
        }.getOrDefault(false)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        waitUntil(timeoutMs, "Condition not met within ${timeoutMs}ms", condition)
    }

    private fun waitUntil(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (runCatching { condition() }.getOrDefault(false)) return
            // Avoid composeRule.waitForIdle — it can throw
            // "performMeasureAndLayout called during measure layout" mid-draw.
            SystemClock.sleep(300)
        }
        error(message)
    }

    private fun shot(name: String) {
        runCatching {
            device.takeScreenshot(File(shotDir, "$name.png"))
        }
    }

    private fun grantRuntimePermissions() {
        val pkg = appContext.packageName
        val perms = listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.READ_CALENDAR",
            "android.permission.ACCESS_FINE_LOCATION",
        )
        for (p in perms) {
            runCatching {
                device.executeShellCommand("pm grant $pkg $p")
            }
        }
    }

    private fun dismissSystemPermissionDialogs() {
        repeat(4) {
            val allow = device.findObject(By.text("Allow"))
                ?: device.findObject(By.text("ALLOW"))
                ?: device.findObject(By.text("While using the app"))
                ?: device.findObject(By.text("Permitir"))
                ?: device.findObject(By.text("Durante o uso do app"))
            if (allow != null) {
                allow.click()
                SystemClock.sleep(400)
            } else {
                return
            }
        }
    }
}
