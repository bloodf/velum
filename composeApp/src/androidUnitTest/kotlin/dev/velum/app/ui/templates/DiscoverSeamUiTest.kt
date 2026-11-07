package dev.velum.app.ui.templates

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.velum.ai.local.ModelStoreDir
import dev.velum.app.di.AppContainer
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.i18n.PtBr
import dev.velum.app.ui.theme.VelumTheme
import dev.velum.core.data.DriverFactory
import dev.velum.core.data.SeedData
import dev.velum.core.security.InMemorySecureStore
import dev.velum.core.templates.DiscoverFacets
import dev.velum.core.templates.DiscoverFilter
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Discover paging, loading and reload behaviour through the [DiscoverQueries] seam (#201): counted, held or failed queries. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class DiscoverSeamUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun grid() = compose.onNodeWithTag("templates:grid")
    private fun reaches(key: String) = runCatching { grid().performScrollToKey(key) }.isSuccess

    private fun waiting(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    /** 100 featured rows pg-000..pg-099 ranked by uses, one tagged agenda; returns the container. */
    private fun seeded(): Pair<AppContainer, ModelStoreDir> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("velum.db")
        val root = ModelStoreDir(Files.createTempDirectory("velum-discover-seam-test").toString())
        val container = AppContainer(InMemorySecureStore(), DriverFactory(context), modelStoreRoot = root)
        runBlocking {
            container.repository.setMeta(SeedData.META_FULL_TEMPLATES_IMPORTED, "1")
            for (i in 0 until 100) {
                container.repository.upsertTemplate(
                    id = "pg-%03d".format(i), name = "PG %03d".format(i), category = "meeting", description = "synthetic",
                    systemPrompt = "p", builtin = true, featured = true,
                    tags = if (i == 0) listOf("agenda") else emptyList(), lang = "en", uses = 2_000_000L - i,
                )
            }
        }
        return container to root
    }

    private fun show(container: AppContainer, queries: DiscoverQueries) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            CompositionLocalProvider(LocalStrings provides PtBr) {
                VelumTheme(darkTheme = false) { TemplatesScreen(container, onBack = {}, onOpenTemplate = {}, queries = queries) }
            }
        }
        holder = activity
    }

    private var holder: org.robolectric.android.controller.ActivityController<ComponentActivity>? = null

    private fun finish(container: AppContainer, root: ModelStoreDir) {
        holder?.pause()?.stop()?.destroy()
        container.databaseHandle.driver.close()
        root.deleteRecursively()
    }

    private data class PageCall(val limit: Int, val offset: Int)

    @Test
    fun typing_waits_for_the_debounce_and_clearing_the_search_applies_at_once() {
        val (container, root) = seeded()
        val searches = CopyOnWriteArrayList<String>()
        val real = container.repository.discoverQueries()
        val queries = DiscoverQueries(
            page = { f, limit, offset -> searches += f.search; real.page(f, limit, offset) },
            count = real.count, facets = real.facets,
        )
        try {
            show(container, queries)
            waiting("first page") { reaches("pg-000") }
            compose.onNodeWithContentDescription(PtBr.search).performClick()
            waiting("search field") { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            val field = compose.onNode(hasSetTextAction())

            compose.mainClock.autoAdvance = false
            field.performTextReplacement("PG 001")
            compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS / 2)
            assertTrue("PG 001" !in searches, "typing applied before the debounce: $searches")
            compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS)
            waiting("the typed search") { searches.lastOrNull() == "PG 001" }

            // Well under the debounce: only the frames the clear needs to recompose and restart the page query.
            field.performTextClearance()
            compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS / 4)
            waiting("the cleared search") { searches.lastOrNull() == "" }
        } finally {
            compose.mainClock.autoAdvance = true
            finish(container, root)
        }
    }

    @Test
    fun paging_appends_with_offset_and_never_requeries_from_row_zero() {
        val (container, root) = seeded()
        val calls = CopyOnWriteArrayList<PageCall>()
        val real = container.repository.discoverQueries()
        val queries = DiscoverQueries(
            page = { f, limit, offset -> if (limit != 5) calls += PageCall(limit, offset); real.page(f, limit, offset) },
            count = real.count, facets = real.facets,
        )
        try {
            show(container, queries)
            waiting("first page") { reaches("pg-000") }
            grid().performScrollToIndex(61)
            waiting("page 2") { reaches("pg-099") }
            // Every request is a single page, the first page is requested exactly once, and the only continuation
            // is offset 60 (the catalog tick must not trigger a second start-up load).
            assertTrue(calls.all { it.limit == 60 }, "a request re-queried more than one page: $calls")
            assertEquals(listOf(PageCall(60, 0)), calls.filter { it.offset == 0 }, "first page loaded more than once")
            assertEquals(listOf(PageCall(60, 60)), calls.filter { it.offset > 0 })
        } finally {
            finish(container, root)
        }
    }

    @Test
    fun no_empty_state_while_the_first_page_is_held_back() {
        val (container, root) = seeded()
        val gate = CompletableDeferred<Unit>()
        val real = container.repository.discoverQueries()
        val queries = DiscoverQueries(
            page = { f, limit, offset -> if (limit != 5) gate.await(); real.page(f, limit, offset) },
            count = real.count, facets = real.facets,
        )
        try {
            show(container, queries)
            waiting("spinner") { hasTag("templates:loading") }
            repeat(10) {
                Thread.sleep(50)
                compose.waitForIdle()
                // Bring the items after the spinner into view: an empty-state item below the fold is not composed.
                runCatching { grid().performScrollToIndex(2) }
                assertTrue(!hasTag("templates:empty"), "empty state shown while the first page is still loading")
                assertTrue(hasTag("templates:loading"))
            }
            gate.complete(Unit)
            waiting("rows after release") { reaches("pg-000") && !hasTag("templates:loading") }
        } finally {
            gate.complete(Unit)
            finish(container, root)
        }
    }

    @Test
    fun failed_page_two_offers_retry_and_retry_loads_it() {
        val (container, root) = seeded()
        val real = container.repository.discoverQueries()
        var pageCalls = 0
        val queries = DiscoverQueries(
            page = { f, limit, offset ->
                if (limit != 5 && offset == 60 && ++pageCalls == 1) error("page 2 fails once")
                real.page(f, limit, offset)
            },
            count = real.count, facets = real.facets,
        )
        try {
            show(container, queries)
            waiting("first page") { reaches("pg-000") }
            grid().performScrollToIndex(61)
            // The retry item sits after the last row; scroll down to it.
            waiting("retry offered") {
                grid().performTouchInput { swipeUp() }
                hasTag("templates:retry")
            }
            assertTrue(reaches("pg-030"), "page 1 rows were wiped by the failed page")
            assertTrue(!reaches("pg-099"), "page 2 should not have loaded yet")
            waiting("retry reachable") {
                grid().performTouchInput { swipeUp() }
                hasTag("templates:retry")
            }
            compose.onNodeWithTag("templates:retry").performSemanticsAction(SemanticsActions.OnClick)
            waiting("page 2 after retry") { reaches("pg-099") }
            assertTrue(!hasTag("templates:retry"))
        } finally {
            finish(container, root)
        }
    }

    @Test
    fun catalog_tick_reloads_every_paged_in_row_instead_of_collapsing_to_one_page() {
        val (container, root) = seeded()
        val calls = CopyOnWriteArrayList<PageCall>()
        val real = container.repository.discoverQueries()
        val queries = DiscoverQueries(
            page = { f, limit, offset -> if (limit != 5) calls += PageCall(limit, offset); real.page(f, limit, offset) },
            count = real.count, facets = real.facets,
        )
        try {
            show(container, queries)
            waiting("first page") { reaches("pg-000") }
            grid().performScrollToIndex(61)
            waiting("page 2") { reaches("pg-099") }
            runBlocking {
                container.repository.upsertTemplate(
                    id = "zz-new", name = "ZZ New", category = "meeting", description = "synthetic", systemPrompt = "p",
                    builtin = true, featured = true, tags = emptyList(), lang = "en", uses = 1L,
                )
            }
            try {
                waiting("tick reload of all 120 paged-in rows") { calls.any { it.offset == 0 && it.limit == 120 } }
            } catch (e: AssertionError) {
                throw AssertionError("${e.message} calls=$calls", e)
            }
            assertTrue(reaches("pg-099"))
        } finally {
            finish(container, root)
        }
    }

    @Test
    fun facet_chips_are_not_tappable_while_a_facets_reload_is_in_flight() {
        val (container, root) = seeded()
        val real = container.repository.discoverQueries()
        var gate: CompletableDeferred<Unit>? = null
        val queries = DiscoverQueries(
            page = real.page, count = real.count,
            facets = { f -> gate?.await(); real.facets(f) },
        )
        try {
            show(container, queries)
            waiting("tag chip enabled") { compose.onAllNodes(hasTestTag("tag:agenda") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val held = CompletableDeferred<Unit>().also { gate = it }
            compose.onNodeWithTag("tier:all").performSemanticsAction(SemanticsActions.OnClick)
            try {
                waiting("tag chip disabled during the reload") { hasTag("tag:agenda") && compose.onAllNodes(hasTestTag("tag:agenda") and isEnabled()).fetchSemanticsNodes().isEmpty() }
            } catch (e: AssertionError) {
                throw AssertionError("${e.message} tagNodes=${compose.onAllNodesWithTag("tag:agenda").fetchSemanticsNodes().size} tierSelected=${compose.onAllNodes(hasTestTag("tier:all")).fetchSemanticsNodes().map { it.config }}", e)
            }
            assertTrue(compose.onAllNodes(hasTestTag("category:meeting") and isEnabled()).fetchSemanticsNodes().isEmpty())
            held.complete(Unit)
            waiting("tag chip enabled again") { compose.onAllNodes(hasTestTag("tag:agenda") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            gate?.complete(Unit)
            finish(container, root)
        }
    }

    @Test
    fun facet_chips_are_tappable_again_after_a_failed_facets_reload() {
        val (container, root) = seeded()
        val real = container.repository.discoverQueries()
        var gate: CompletableDeferred<Unit>? = null
        val queries = DiscoverQueries(
            page = real.page, count = real.count,
            facets = { f -> gate?.await(); real.facets(f) },
        )
        try {
            show(container, queries)
            waiting("tag chip enabled") { compose.onAllNodes(hasTestTag("tag:agenda") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val held = CompletableDeferred<Unit>().also { gate = it }
            compose.onNodeWithTag("tier:all").performSemanticsAction(SemanticsActions.OnClick)
            waiting("tag chip disabled during the reload") { compose.onAllNodes(hasTestTag("tag:agenda") and isEnabled()).fetchSemanticsNodes().isEmpty() }
            held.completeExceptionally(IllegalStateException("facets reload fails"))
            // A failed reload keeps the previous chips and must not leave them dimmed and dead.
            waiting("tag chip enabled after the failure") { compose.onAllNodes(hasTestTag("tag:agenda") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            finish(container, root)
        }
    }
}
