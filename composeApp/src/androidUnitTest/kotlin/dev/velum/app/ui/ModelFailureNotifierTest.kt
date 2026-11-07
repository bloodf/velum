package dev.velum.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.velum.ai.local.LocalModelKind
import dev.velum.ai.local.LocalModelErrors
import dev.velum.ai.local.LocalModelState
import dev.velum.ai.local.LocalModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import dev.velum.ai.local.LocalModelSpec
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.navigation.Routes
import dev.velum.app.ui.settings.ModelFailureNotifier
import dev.velum.app.ui.settings.modelFailureNotice
import dev.velum.app.ui.theme.VelumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** #301: a failed download is announced outside Model manager, with a way back, and not inside it. */
@RunWith(RobolectricTestRunner::class)
class ModelFailureNotifierTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val spec = LocalModelSpec(
        id = "big", displayName = "Synthetic Big", kind = LocalModelKind.Asr, approxBytes = 10_000_000_000L, license = "MIT",
        notes = "", languages = "", downloadUrl = "https://example.test/big.bin", archiveFileName = "big.bin", sha256 = "ab".repeat(32),
    )

    private fun state(status: LocalModelStatus, error: String? = null) = LocalModelState(spec, status, error = error)

    private fun show(s: Strings, states: MutableStateFlow<List<LocalModelState>>, route: String?, opened: () -> Unit) {
        val host = SnackbarHostState()
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            CompositionLocalProvider(LocalStrings provides s) {
                VelumTheme(darkTheme = false) {
                    SnackbarHost(host)
                    ModelFailureNotifier(states, host, currentRoute = { route }, onOpenModelManager = opened)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun a_failure_elsewhere_shows_plain_copy_and_the_action_opens_model_manager_in_both_locales() {
        for (s in listOf<Strings>(En, PtBr)) {
            val states = MutableStateFlow(listOf(state(LocalModelStatus.Downloading))); var opened = 0
            show(s, states, route = Routes.FILES, opened = { opened++ })
            val failed = state(LocalModelStatus.Failed, LocalModelErrors.StorageFull)
            states.value = listOf(failed)
            val text = modelFailureNotice(failed, s)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(s.modelManager).performClick()
            compose.waitUntil(10_000) { opened == 1 }
            assertEquals(1, opened)
        }
    }

    @Test fun a_failure_inside_model_manager_is_not_announced() {
        val states = MutableStateFlow(listOf(state(LocalModelStatus.Downloading)))
        show(En, states, route = Routes.MODEL_MANAGER, opened = {})
        states.value = listOf(state(LocalModelStatus.Failed, "HTTP 500"))
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(En.modelManager).fetchSemanticsNodes().isEmpty())
    }

    // The effect lives at the app level; the whole App cannot be composed here, so pin that it stays wired with a way back.
    @Test fun app_installs_the_notifier_with_a_single_top_way_back_to_model_manager() {
        val src = listOf(
            java.io.File("src/commonMain/kotlin/dev/velum/app/App.kt"),
            java.io.File("composeApp/src/commonMain/kotlin/dev/velum/app/App.kt"),
        ).first { it.isFile }.readText()
        assertTrue(src.contains("ModelFailureNotifier("))
        assertTrue(src.contains("navigate(Routes.MODEL_MANAGER) { launchSingleTop = true }"))
    }
}
