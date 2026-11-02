package dev.velum.app.ui.templates

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #102 on a device: Discover over the real bundled catalog (no fixture, no network). Switching to the All tier
 * imports the bundled asset; the filter chips are then derived from it. Own database file, never velum.db.
 *
 * Run: adb shell am instrument -w -e class dev.velum.app.ui.templates.DiscoverBundledCatalogDeviceTest \
 *   -e velumDeviceWalk false dev.velum.app.test/dev.velum.app.devicewalk.DeviceWalkTestRunner
 */
@RunWith(AndroidJUnit4::class)
class DiscoverBundledCatalogDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var container: AppContainer

    @Before fun open() {
        context.deleteDatabase(DB_NAME)
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-device").toString())
        container = AppContainer(InMemorySecureStore(), DriverFactory(context, DB_NAME), modelStoreRoot = root)
    }

    @After fun close() {
        container.databaseHandle.driver.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun all_tier_category_filter_and_clear_on_bundled_catalog() {
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides En) {
                VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}) }
            }
        }
        compose.onNodeWithTag("tier:all").performClick()
        // The first All switch imports the whole bundled catalog; "meeting" is its largest category.
        compose.waitUntil(IMPORT_TIMEOUT_MS) { has("category:meeting") }
        compose.onNodeWithTag("category:meeting").performScrollTo().performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { has("active:category") }
        check(!has("templates:empty")) { "meeting category must match bundled templates" }
        compose.onNodeWithTag("filters:clear").performScrollTo().performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { !has("active:category") }
    }

    private companion object {
        const val DB_NAME = "discover102-instrumented.db"
        const val IMPORT_TIMEOUT_MS = 90_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
