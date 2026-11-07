package dev.velum.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.velum.ai.OpenAiCompatibleClient
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.settings.ApiSettingsScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class ApiModelsCountLocaleUiTest(
    private val locale: String,
    private val modelCount: Int,
    private val expected: String,
) {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun refresh_renders_the_catalog_count_in_the_selected_locale() {
        val strings: Strings = if (locale == "en") En else PtBr
        val requests = AtomicInteger()
        val engine = MockEngine { request ->
            assertEquals("https://catalog.invalid/v1/models", request.url.toString())
            requests.incrementAndGet()
            val models = (1..modelCount).joinToString(",") { """{"id":"fixture-$it"}""" }
            respond(
                content = """{"data":[$models]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(OpenAiCompatibleClient.defaultJson) }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-api-count-test").toString())
        val container = AppContainer(
            InMemorySecureStore(),
            DriverFactory(context),
            modelStoreRoot = root,
            injectedAiClient = OpenAiCompatibleClient(http),
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            runBlocking {
                container.settingsRepository.load()
                container.settingsRepository.setBaseUrl("https://catalog.invalid/v1")
                container.settingsRepository.setApiKey("fixture-key")
            }
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides strings) {
                    VelumTheme(darkTheme = false) { ApiSettingsScreen(container, onBack = {}) }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(expected).assertDoesNotExist()
            compose.onNodeWithContentDescription(strings.refreshModels).performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
            assertEquals(1, requests.get())
            assertEquals(modelCount, container.settingsViewModel.settings.value.cachedModelsByProfile.values.sumOf { it.size })
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            http.close()
            engine.close()
            root.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}, {1} models")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("en", 0, "0 models loaded"),
            arrayOf("en", 1, "1 model loaded"),
            arrayOf("en", 3, "3 models loaded"),
            arrayOf("pt-BR", 0, "0 modelos carregados"),
            arrayOf("pt-BR", 1, "1 modelo carregado"),
            arrayOf("pt-BR", 3, "3 modelos carregados"),
        )
    }
}
