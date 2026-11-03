package dev.velum.app.ui.templates

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The wait helper behind the Discover UI tests (#285): it must deliver Main-looper work and judge stalls by activity. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34])
class WaitWhileBusyTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var activity: org.robolectric.android.controller.ActivityController<ComponentActivity>? = null

    private fun screen() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { it.get().setContent { } }
    }

    @After fun tearDown() {
        scope.cancel()
        activity?.pause()?.stop()?.destroy()
    }

    @Test fun a_result_resumed_on_the_main_looper_is_delivered_while_waiting() {
        screen()
        val delivered = AtomicBoolean(false)
        // Exactly what QueryLog does with a finished query: finish off-main, then hop to Main to hand the result over.
        scope.launch { withContext(Dispatchers.Main) { delivered.set(true) } }
        compose.waitWhileBusy(QueryLog(), capMs = 20_000, onTimeout = { reason, e -> throw AssertionError("never delivered ($reason)", e) }) { delivered.get() }
        assertTrue(delivered.get())
    }

    @Test fun a_stalled_screen_fails_after_the_quiet_period_not_the_cap() {
        screen()
        val started = System.nanoTime()
        assertFailsWith<AssertionError> {
            compose.waitWhileBusy(QueryLog(), quietMs = 1_500, capMs = 120_000, onTimeout = { reason, e -> throw AssertionError("stalled ($reason)", e) }) { false }
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 30_000, "should fail on the quiet period, long before the cap")
    }

    @Test fun a_slow_running_query_keeps_the_wait_alive_past_the_quiet_period() {
        screen()
        val log = QueryLog()
        val finished = AtomicBoolean(false)
        val base = DiscoverQueries(page = { _, _, _ -> Thread.sleep(3_000); emptyList() }, count = { 0 }, facets = { error("unused") })
        val queries = log.wrapQueries(base)
        scope.launch { queries.page(dev.velum.core.templates.DiscoverFilter(), 1, 0); finished.set(true) }
        // quietMs (1 s) is shorter than the query (3 s): in flight is progress, so the wait must not give up.
        compose.waitWhileBusy(log, quietMs = 1_000, capMs = 60_000, onTimeout = { reason, e -> throw AssertionError("gave up on a running query ($reason)", e) }) { finished.get() }
        assertTrue(finished.get())
    }

    @Test fun a_fresh_wait_gets_its_full_quiet_budget_even_when_the_last_query_ended_long_ago() {
        screen()
        val log = QueryLog()
        val base = DiscoverQueries(page = { _, _, _ -> emptyList() }, count = { 0 }, facets = { error("unused") })
        // Off the test thread: the result hops to Main, which is the thread this test is on.
        val query = scope.launch { log.wrapQueries(base).page(dev.velum.core.templates.DiscoverFilter(), 1, 0) }
        compose.waitWhileBusy(log, onTimeout = { reason, e -> throw AssertionError("query never finished ($reason)", e) }) { query.isCompleted }
        Thread.sleep(2_500) // the log has now been quiet for longer than quietMs below
        val becomesTrueAt = System.nanoTime() + 1_800_000_000L // after more than 1 s of waiting, less than quietMs
        compose.waitWhileBusy(log, quietMs = 2_000, capMs = 60_000, onTimeout = { reason, e -> throw AssertionError("gave up early ($reason)", e) }) {
            System.nanoTime() >= becomesTrueAt
        }
    }
}
