package dev.velum.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import dev.velum.ai.local.LocalModelErrors
import dev.velum.app.i18n.En
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.ModelCatalogUi
import dev.velum.app.i18n.PtBr
import dev.velum.app.i18n.Strings
import dev.velum.app.ui.onboarding.FirstRunScreen
import dev.velum.app.ui.settings.modelErrorText
import dev.velum.app.ui.theme.VelumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #279: plain, translated copy for the first-run size/space message and for a full disk. */
@RunWith(RobolectricTestRunner::class)
class ModelStorageCopyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun a_storage_failure_never_shows_errno_text_in_either_locale() {
        for (s in listOf<Strings>(En, PtBr)) {
            val text = modelErrorText(LocalModelErrors.StorageFull, s)
            assertEquals(s.ux.modelStorageFull, text)
            assertFalse(text.contains("ENOSPC", ignoreCase = true))
        }
        assertTrue(En.ux.modelStorageFull != PtBr.ux.modelStorageFull)
        assertEquals("HTTP 404", modelErrorText("HTTP 404", En))
    }

    private fun show(s: Strings, notice: String?, total: Long = 0L, needed: Long = 0L) {
        val controller = org.robolectric.Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            CompositionLocalProvider(LocalStrings provides s) {
                VelumTheme(darkTheme = false) {
                    FirstRunScreen({}, {}, {}, {}, onUseApi = {}, onSkip = {}, languageHint = s.localeTag, notice = notice, recommendedTotalBytes = total, recommendedNeededBytes = needed)
                }
            }
        }
    }

    @Test fun first_run_states_the_total_download_and_space_needed_in_both_locales() {
        for (s in listOf<Strings>(En, PtBr)) {
            val ids = ModelCatalogUi.recommendedModelIds(s.localeTag)
            val total = ids.sumOf { id -> dev.velum.ai.local.DefaultLocalCatalog.first { it.id == id }.approxBytes }
            show(s, null, total = total, needed = total + 1024L * 1024 * 1024)
            val prefix = s.firstRun.recommendedTotalFormat.substringBefore("%1")
            compose.waitForIdle()
            assertTrue("${s.localeTag}: total line missing", compose.onAllNodesWithText(prefix, substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodesWithText(ModelCatalogUi.formatBytes(total), substring = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun the_not_enough_space_message_fills_both_sizes() {
        for (s in listOf<Strings>(En, PtBr)) {
            val msg = ModelCatalogUi.fill(s.firstRun.notEnoughSpaceFormat, "3.2 GB", "1.0 GB")
            assertTrue(msg.contains("3.2 GB") && msg.contains("1.0 GB") && !msg.contains("%"))
        }
    }
}
