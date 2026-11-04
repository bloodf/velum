package dev.velum.app.ui.recording

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.ai.pipeline.INSUFFICIENT_STORAGE_MESSAGE
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "en-rUS-w360dp-h1800dp-xxhdpi")
class ProcessingLocaleUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun render(status: String, strings: Strings, expected: String, otherLanguage: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("en", context.resources.configuration.locales[0].language)
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-locale-ui-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.settingsRepository.load()
            container.repository.insertRecording(
                id = "rec", sessionId = null, folderId = null, title = "Synthetic recording", source = "phone", deviceSerial = null,
                startedAt = 1, durationSeconds = 60, sizeBytes = 0, localPath = null,
                transcript = null, summaryMarkdown = null, status = status,
            )
            if (status == "failed") container.repository.setProcessingError("rec", INSUFFICIENT_STORAGE_MESSAGE)
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides strings) {
                    VelumTheme(darkTheme = false) {
                        RecordingDetailScreen(container, recordingId = "rec", onBack = {})
                    }
                }
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodesWithText(otherLanguage).fetchSemanticsNodes().isEmpty())
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    @Test fun portuguese_failure_on_english_device() = render(
        "failed", PtBr,
        "Não há espaço livre suficiente para processar esta gravação. Libere espaço e tente de novo.",
        "Not enough free storage to process this recording. Free up space and try again.",
    )

    @Test fun english_failure_on_english_device() = render(
        "failed", En,
        "Not enough free storage to process this recording. Free up space and try again.",
        "Não há espaço livre suficiente para processar esta gravação. Libere espaço e tente de novo.",
    )

    @Test fun portuguese_thermal_pause_on_english_device() = render(
        "paused_thermal", PtBr,
        "Pausado — aparelho aquecido; retoma automaticamente",
        "Paused — device is hot; will resume automatically",
    )

    @Test fun english_thermal_pause_on_english_device() = render(
        "paused_thermal", En,
        "Paused — device is hot; will resume automatically",
        "Pausado — aparelho aquecido; retoma automaticamente",
    )
}
