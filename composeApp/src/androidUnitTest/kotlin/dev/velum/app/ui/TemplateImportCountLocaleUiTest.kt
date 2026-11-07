package dev.velum.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.templates.TemplatesScreen
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h1800dp-xxhdpi")
class TemplateImportCountLocaleUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun exercise(strings: Strings, count: Int, expected: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-import-count").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        val file = Files.createTempFile("velum-import-count", ".json").toFile()
        val entries = (1..count).joinToString(",") { index ->
            """{"id":"count-import-$index","name":"Imported $index","category":"meeting","system_prompt":"Summarize $index"}"""
        }
        file.writeText("[$entries]")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalStrings provides strings) {
                    VelumTheme(darkTheme = false) {
                        TemplatesScreen(container, onBack = {}, onOpenTemplate = {})
                    }
                }
            }
            compose.onNodeWithText("JSON").performClick()
            compose.runOnIdle {
                val shadow = shadowOf(activity.get())
                val request = shadow.nextStartedActivityForResult
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
                shadow.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(expected).assertIsDisplayed()
            val queries = container.databaseHandle.database.velumQueries
            for (index in 1..count) {
                val imported = queries.selectTemplateById("count-import-$index").executeAsOne()
                assertEquals("Imported $index", imported.name)
                assertEquals("Summarize $index", imported.system_prompt)
            }
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
            file.delete()
        }
    }

    @Test fun english_one() = exercise(En, 1, "Imported 1 template")
    @Test fun english_many() = exercise(En, 2, "Imported 2 templates")
    @Test fun portuguese_one() = exercise(PtBr, 1, "Importado 1 modelo")
    @Test fun portuguese_many() = exercise(PtBr, 2, "Importados 2 modelos")
    @Test fun english_zero() = exercise(En, 0, "Imported 0 templates")
    @Test fun portuguese_zero() = exercise(PtBr, 0, "Importados 0 modelos")
}
