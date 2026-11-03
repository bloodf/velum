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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private fun screen() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent { }
    }

    @Test fun a_result_resumed_on_the_main_looper_is_delivered_while_waiting() {
        screen()
        val delivered = AtomicBoolean(false)
        // Exactly what QueryLog does with a finished query: finish off-main, then hop to Main to hand the result over.
        CoroutineScope(Dispatchers.Default).launch { withContext(Dispatchers.Main) { delivered.set(true) } }
        compose.waitWhileBusy(QueryLog(), capMs = 20_000, onTimeout = { throw AssertionError("never delivered", it) }) { delivered.get() }
        assertTrue(delivered.get())
    }

    @Test fun a_stalled_screen_fails_after_the_quiet_period_not_the_cap() {
        screen()
        val started = System.nanoTime()
        assertFailsWith<AssertionError> {
            compose.waitWhileBusy(QueryLog(), quietMs = 1_500, capMs = 120_000, onTimeout = { throw AssertionError("stalled", it) }) { false }
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 30_000, "should fail on the quiet period, long before the cap")
    }

    @Test fun a_slow_running_query_keeps_the_wait_alive_past_the_quiet_period() {
        screen()
        val log = QueryLog()
        val finished = AtomicBoolean(false)
        val base = DiscoverQueries(page = { _, _, _ -> Thread.sleep(3_000); emptyList() }, count = { 0 }, facets = { error("unused") })
        val queries = log.wrapQueries(base)
        CoroutineScope(Dispatchers.Default).launch { queries.page(dev.velum.core.templates.DiscoverFilter(), 1, 0); finished.set(true) }
        // quietMs (1 s) is shorter than the query (3 s): in flight is progress, so the wait must not give up.
        compose.waitWhileBusy(log, quietMs = 1_000, capMs = 60_000, onTimeout = { throw AssertionError("gave up on a running query", it) }) { finished.get() }
        assertTrue(finished.get())
    }
}
