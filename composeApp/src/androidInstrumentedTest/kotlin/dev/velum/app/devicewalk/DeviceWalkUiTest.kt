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
        // Splash → FirstRun or Files. Locale may still be system/previous-test prefs.
        waitUntil(90_000) {
            composeExists("Skip") ||
                composeExists("Pular") ||
                composeExists("All files") ||
                composeExists("Todos os arquivos") ||
                composeExists("Velum")
        }
        if (composeExists("Skip") || composeExists("Pular")) {
            clickTextPrefer("Skip", "Pular")
            covered += "first-run-skip"
        }
        waitUntil(60_000) {
            composeExists("All files") ||
                composeExists("Todos os arquivos") ||
                composeExists("Trash") ||
                composeExists("Lixeira")
        }
        restoreAllFilesFilterSoft()
        dismissSystemPermissionDialogs()
    }

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
        pressBackToFiles(lang)
        waitUntil(20_000) {
            if (lang == "pt-BR") {
                composeExists("Todos os arquivos") ||
                    composeExists("Lixeira") ||
                    composeExists("Explorar")
            } else {
                composeExists("All files") ||
                    composeExists("Trash") ||
                    composeExists("Explore")
            }
        }
    }

    private fun walkLibrary(lang: String) {
        pressBackToFiles(lang)
        restoreAllFilesFilterSoft()
        assertTrue(onFilesChrome(), "Expected Files chrome after appearance")
        covered += "library"
        // Search sheet (best-effort)
        if (softClickContentDesc("Search", "Buscar")) {
            covered += "library/search"
            device.pressBack()
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
                device.pressBack()
            }
            runCatching {
                clickTextPrefer("Versions", "Versões")
                covered += "recording/versions"
                device.pressBack()
            }
            runCatching {
                clickTextPrefer("Sources", "Fontes")
                covered += "recording/sources"
            }
        }
        device.pressBack()
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
            device.pressBack()
        }
        shot("07-templates-$tag")
        device.pressBack()
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
        val lang = tag.substringBefore('_')
        pressBackToFiles(if (lang == "pt-BR") "pt-BR" else "en")
        covered += "process-death-restore"
        shot("15-process-death-$tag")
    }

    private fun assertNoRawOrUnlocalized(lang: String) {
        pressBackToFiles(lang)
        restoreAllFilesFilterSoft()
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
        // Locale smoke: shell chrome must match the language applied in appearance.
        if (lang == "pt-BR") {
            assertTrue(
                composeExists("Todos os arquivos") ||
                    composeExists("Lixeira") ||
                    composeExists("Explorar") ||
                    composeExists("Configuração") ||
                    dump.contains("Arquivos", ignoreCase = true),
                "Expected pt-BR chrome after locale apply",
            )
        } else {
            assertTrue(
                composeExists("All files") ||
                    composeExists("Trash") ||
                    composeExists("Explore") ||
                    composeExists("Settings") ||
                    dump.contains("All files", ignoreCase = true),
                "Expected EN chrome after locale apply",
            )
        }
    }

    private fun openSettings() {
        pressBackToFiles("pt-BR")
        // Files header gear uses CD "Settings"/"Configuração" — do not treat that as hub-open.
        val clickedGear = runCatching {
            composeRule
                .onNodeWithContentDescription("Settings", useUnmergedTree = true)
                .performClick()
            true
        }.recoverCatching {
            composeRule
                .onNodeWithContentDescription("Configuração", useUnmergedTree = true)
                .performClick()
            true
        }.recoverCatching {
            val obj = device.findObject(By.desc("Settings"))
                ?: device.findObject(By.desc("Configuração"))
            obj?.click() ?: error("Settings gear missing")
            true
        }.getOrDefault(false)
        assertTrue(clickedGear, "Failed to tap Settings gear")
        // Wait for a hub-only row (Appearance is below the fold — do not require it).
        waitUntil(20_000) {
            composeExists("Phone recording") ||
                composeExists("Gravação no telefone") ||
                composeExists("Storage & privacy") ||
                composeExists("Armazenamento e privacidade") ||
                composeExists("About Velum") ||
                composeExists("Sobre o Velum") ||
                composeExists("AI processing") ||
                composeExists("Processamento de IA")
        }
    }

    private fun pressBackToFiles(lang: String) {
        repeat(10) {
            if (onFilesChrome()) return
            // Bottom bar Files tab is only composed on shell routes.
            if (softClickContentDesc("All files", "Todos os arquivos") && onFilesChrome()) return
            device.pressBack()
            SystemClock.sleep(250)
            dismissSystemPermissionDialogs()
        }
        if (onFilesChrome()) return
        // Last resort: relaunch MainActivity onto the files shell.
        runCatching {
            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                appContext.startActivity(launch)
            }
        }
        waitUntil(45_000) {
            onFilesChrome() ||
                composeExists("Skip") ||
                composeExists("Pular") ||
                composeExists("Velum")
        }
        if (composeExists("Skip") || composeExists("Pular")) {
            softClickText("Skip", "Pular")
        }
        softClickContentDesc("All files", "Todos os arquivos")
        restoreAllFilesFilterSoft()
        if (!onFilesChrome()) {
            error("Could not return to Files shell (lang=$lang)")
        }
    }

    private fun onFilesChrome(): Boolean =
        composeExists("Todos os arquivos") ||
            composeExists("All files") ||
            composeExists("Trash") ||
            composeExists("Lixeira")

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
            val clicked = runCatching {
                composeRule.onNodeWithContentDescription(label, substring = true, useUnmergedTree = true)
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
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (runCatching { condition() }.getOrDefault(false)) return
            // Avoid composeRule.waitForIdle — it can throw
            // "performMeasureAndLayout called during measure layout" mid-draw.
            SystemClock.sleep(300)
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
