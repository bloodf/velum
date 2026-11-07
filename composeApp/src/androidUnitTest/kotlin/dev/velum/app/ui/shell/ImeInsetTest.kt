package dev.velum.app.ui.shell

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.App
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.En
import dev.velum.core.data.DriverFactory
import dev.velum.core.security.InMemorySecureStore
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * #305: the app window is edge-to-edge, so the keyboard does not resize it; the shell must lift itself above the
 * keyboard. Checked on the real [App]: with a keyboard inset the bottom bar rides on top of the keyboard, and the
 * navigation-bar padding is not added on top of the keyboard height.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h640dp")
class ImeInsetTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun withApp(imePx: Int, navPx: Int, block: () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-ime-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        container.markFirstRunDone()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val a = activity.get()
            a.runOnUiThread {
                WindowCompat.setDecorFitsSystemWindows(a.window, false)
                a.setContent { App(container, stringsOverride = En, darkThemeOverride = false) }
            }
            compose.waitForIdle()
            a.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                    .setVisible(WindowInsetsCompat.Type.ime(), imePx > 0)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(a.window.decorView, insets)
            }
            compose.waitForIdle()
            block()
        } finally {
            activity.pause().stop().destroy()
            container.databaseHandle.driver.close()
            root.deleteRecursively()
        }
    }

    private fun rootBottom() = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
    private fun tabBottom() = compose.onNodeWithContentDescription(En.allFiles).fetchSemanticsNode().boundsInRoot.bottom

    @Test fun the_bottom_bar_rides_above_the_keyboard_without_adding_the_navigation_bar_twice() {
        val ime = 300
        val nav = 63
        withApp(imePx = ime, navPx = nav) {
            val limit = rootBottom() - ime
            val bottom = tabBottom()
            assertTrue(bottom <= limit, "tab bottom $bottom must end above the keyboard top $limit")
            // The tab icon sits inside a bar that ends at the keyboard; a second navigation-bar pad would push it
            // up by another $nav px (the bar's own inner padding is far smaller than that).
            assertTrue(limit - bottom < 64f + 1f, "tab bottom $bottom is more than a bar above the keyboard top $limit (double padding?)")
        }
    }

    @Test fun without_a_keyboard_the_bar_stays_put() {
        withApp(imePx = 0, navPx = 0) {
            assertTrue(rootBottom() - tabBottom() < 64f + 1f)
        }
    }
}
