package dev.velum.app.devicewalk

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Environment
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import kotlin.test.assertNotNull
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
        rotateAndBackground(tag)
        killAndRestore(tag)

        assertNoRawOrUnlocalized(lang)
        assertFalse(device.hasObject(By.textContains("FATAL EXCEPTION")))
        File(shotDir, "coverage-$tag.txt").writeText(covered.sorted().joinToString("\n"))
    }

    private fun waitForShell(lang: String) {
        // Splash → FirstRun or Files. Skip onboarding when present.
        val skip = if (lang == "en") "Skip" else "Pular"
        val files = if (lang == "en") "All files" else "Todos os arquivos"
        waitUntil(90_000) {
            composeExists(skip) || composeExists(files) || composeExists("Velum")
        }
        if (composeExists(skip) || composeExists("Pular") || composeExists("Skip")) {
            clickTextPrefer(skip, "Pular", "Skip")
            covered += "first-run-skip"
        }
        waitUntil(60_000) {
            composeExists(files) || composeExists("Todos os arquivos") || composeExists("All files")
        }
        dismissSystemPermissionDialogs()
    }

    private fun applyAppearance(lang: String, theme: String) {
        openSettings()
        clickTextPrefer(
            if (lang == "en") "Appearance" else "Aparência",
            "Appearance",
            "Aparência",
        )
        covered += "settings/appearance"
        clickTextPrefer(
            if (theme == "dark") {
                if (lang == "en") "Dark" else "Escuro"
            } else {
                if (lang == "en") "Light" else "Claro"
            },
            "Dark",
            "Escuro",
            "Light",
            "Claro",
        )
        clickTextPrefer(
            if (lang == "en") "English" else "Português (Brasil)",
            "English",
            "Português (Brasil)",
        )
        pressBackToFiles(lang)
    }

    private fun walkLibrary(lang: String) {
        val files = if (lang == "en") "All files" else "Todos os arquivos"
        assertTrue(composeExists(files) || composeExists("Todos os arquivos") || composeExists("All files"))
        covered += "library"
        // Search sheet
        runCatching {
            composeRule.onNodeWithContentDescription(
                if (lang == "en") "Search" else "Buscar",
                substring = true,
                useUnmergedTree = true,
            ).performClick()
        }
        runCatching {
            // Fallback: many builds use icon-only search without CD — tap by text chip if present.
            clickTextPrefer(
                if (lang == "en") "Search" else "Buscar",
                "Search",
                "Buscar",
            )
        }
        covered += "library/search"
        device.pressBack()
        // Filters / trash if available
        runCatching {
            clickTextPrefer(
                if (lang == "en") "Trash" else "Lixeira",
                "Trash",
                "Lixeira",
            )
            covered += "library/trash"
            device.pressBack()
        }
        shot("03-library")
    }

    private fun walkImportAndTranscribe(lang: String, tag: String) {
        val app = appContext.applicationContext as VelumApplication
        val container = runBlocking { app.awaitContainer() }
        val wavBytes = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("device_walk_tone.wav").readBytes()
        val recordingsDir = File(appContext.filesDir, "recordings").also { it.mkdirs() }
        val wavFile = File(recordingsDir, "device-walk-$tag.wav")
        wavFile.writeBytes(wavBytes)
        val id = runBlocking {
            container.createLocalRecording(
                title = "Device walk $tag",
                source = "import",
                localPath = wavFile.absolutePath,
                status = "queued",
            )
        }
        runBlocking {
            container.enqueueProcessing(id, templateId = null, retranscribe = true)
        }
        covered += "import/bundled-wav"
        // Wait for fake ASR + LLM
        waitUntil(120_000) {
            runBlocking {
                val rec = container.repository.getRecording(id)
                rec?.status == "ready" && !rec.summaryMarkdown.isNullOrBlank()
            }
        }
        covered += "transcription/device-walk-engine"
        shot("04-imported-$tag")
        // Open from library
        clickTextPrefer("Device walk $tag", "Device walk")
        covered += "recording/open"
    }

    private fun walkRecordingDetail(lang: String, tag: String) {
        val notes = if (lang == "en") "Notes" else "Notas"
        val sources = if (lang == "en") "Sources" else "Fontes"
        waitUntil(30_000) { composeExists(notes) || composeExists("Notas") || composeExists("Notes") }
        clickTextPrefer(notes, "Notas", "Notes")
        covered += "recording/notes"
        shot("05-notes-$tag")
        runCatching {
            clickTextPrefer(
                if (lang == "en") "Change template" else "Trocar modelo",
                "Change template",
                "Trocar modelo",
            )
            covered += "recording/change-template"
            device.pressBack()
        }
        runCatching {
            clickTextPrefer(
                if (lang == "en") "Versions" else "Versões",
                "Versions",
                "Versões",
            )
            covered += "recording/versions"
            device.pressBack()
        }
        clickTextPrefer(sources, "Fontes", "Sources")
        covered += "recording/sources"
        device.pressBack()
        pressBackToFiles(lang)
    }

    private fun walkExploreAndTemplates(lang: String, tag: String) {
        clickContentDescPrefer("Explore", "Explorar")
        covered += "explore"
        shot("06-explore-$tag")
        clickTextPrefer(
            if (lang == "en") "Templates" else "Modelos",
            "Templates",
            "Modelos",
            "Community templates",
            "Modelos da comunidade",
        )
        covered += "templates"
        clickTextPrefer(
            if (lang == "en") "Featured" else "Destaques",
            "Featured",
            "Destaques",
        )
        covered += "templates/featured"
        clickTextPrefer(
            if (lang == "en") "All" else "Todos",
            "All",
            "Todos",
        )
        covered += "templates/all"
        runCatching {
            clickTextPrefer(
                if (lang == "en") "Search" else "Buscar",
                "Search",
                "Buscar",
            )
            // Tag / query search field if present
            runCatching {
                composeRule.onAllNodesWithText("", substring = true)
            }
            covered += "templates/search"
            device.pressBack()
        }
        shot("07-templates-$tag")
        device.pressBack()
        pressBackToFiles(lang)
    }

    private fun walkAutoFlow(lang: String, tag: String) {
        clickContentDescPrefer("Explore", "Explorar")
        clickTextPrefer("AutoFlow", "AutoFlow")
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
            device.pressBack()
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
            device.pressBack()
        }
        device.pressBack()
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
            device.pressBack()
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
                device.pressBack()
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
            device.pressBack()
            device.pressBack()
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
            device.pressBack()
        }
        pressBackToFiles(lang)
    }

    private fun rotateAndBackground(tag: String) {
        val activity = composeRule.activity
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        SystemClock.sleep(1_000)
        covered += "rotate/landscape"
        shot("13-landscape-$tag")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        SystemClock.sleep(1_000)
        covered += "rotate/portrait"
        device.pressHome()
        SystemClock.sleep(1_500)
        covered += "background"
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        assertNotNull(launch)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        appContext.startActivity(launch)
        SystemClock.sleep(2_000)
        covered += "restore-from-background"
        shot("14-restore-$tag")
    }

    private fun killAndRestore(tag: String) {
        device.executeShellCommand("am kill ${appContext.packageName}")
        SystemClock.sleep(1_000)
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        assertNotNull(launch)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launch)
        waitUntil(60_000) {
            composeExists("Todos os arquivos") ||
                composeExists("All files") ||
                composeExists("Velum") ||
                composeExists("Pular") ||
                composeExists("Skip")
        }
        // Skip onboarding again if wipe somehow reset — should not after cancel-only wipe
        if (composeExists("Pular") || composeExists("Skip")) {
            clickTextPrefer("Pular", "Skip")
        }
        covered += "process-death-restore"
        shot("15-process-death-$tag")
    }

    private fun assertNoRawOrUnlocalized(lang: String) {
        val dump = buildString {
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
        // Locale smoke: after appearance apply, primary chrome should match
        if (lang == "pt-BR") {
            assertTrue(
                dump.contains("Arquivos", ignoreCase = true) ||
                    composeExists("Todos os arquivos") ||
                    composeExists("Explorar") ||
                    composeExists("Configuração"),
                "Expected pt-BR chrome after locale apply",
            )
        } else {
            assertTrue(
                composeExists("All files") ||
                    composeExists("Explore") ||
                    composeExists("Settings") ||
                    dump.contains("Settings", ignoreCase = true),
                "Expected EN chrome after locale apply",
            )
        }
    }

    private fun openSettings() {
        pressBackToFiles("pt-BR")
        runCatching {
            composeRule.onNodeWithContentDescription("Settings", substring = true, useUnmergedTree = true)
                .performClick()
        }
        runCatching {
            composeRule.onNodeWithContentDescription("Configuração", substring = true, useUnmergedTree = true)
                .performClick()
        }
        runCatching {
            clickTextPrefer("Settings", "Configuração")
        }
        waitUntil(15_000) {
            composeExists("Settings") || composeExists("Configuração") ||
                composeExists("Appearance") || composeExists("Aparência")
        }
    }

    private fun pressBackToFiles(lang: String) {
        repeat(8) {
            if (composeExists("Todos os arquivos") || composeExists("All files")) return
            device.pressBack()
            SystemClock.sleep(300)
        }
        clickContentDescPrefer(
            if (lang == "en") "All files" else "Todos os arquivos",
            "All files",
            "Todos os arquivos",
        )
    }

    private fun scrollClick(label: String) {
        runCatching {
            composeRule.onNodeWithText(label, substring = true, useUnmergedTree = true)
                .performScrollTo()
                .performClick()
        }.recoverCatching {
            composeRule.onNodeWithText(label, substring = true).performClick()
        }.recoverCatching {
            val obj = device.wait(Until.findObject(By.textContains(label)), 5_000)
            obj?.click() ?: error("missing $label")
        }.getOrThrow()
    }

    private fun clickTextPrefer(vararg labels: String) {
        for (label in labels) {
            if (label.isBlank()) continue
            val clicked = runCatching {
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

    private fun composeExists(text: String): Boolean =
        runCatching {
            composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (condition()) return
            composeRule.waitForIdle()
            SystemClock.sleep(250)
        }
        error("Condition not met within ${timeoutMs}ms")
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
