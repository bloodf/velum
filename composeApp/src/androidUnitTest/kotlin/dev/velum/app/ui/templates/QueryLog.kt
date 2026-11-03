package dev.velum.app.ui.templates

import dev.velum.app.di.AppContainer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Records every Discover query (start/end, filter, outcome) so a timeout message can say what the screen asked for. */
internal class QueryLog {
    private val t0 = System.nanoTime()
    private val lines = CopyOnWriteArrayList<String>()
    private val running = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastActivity = System.nanoTime()

    /** How many logged lines contain [fragment], e.g. "start facets(" for facets reloads begun so far. */
    fun count(fragment: String): Int = lines.count { it.contains(fragment) }

    /** Queries started and not yet finished. */
    fun inFlight(): Int = running.get()

    /** Milliseconds since a query last started or finished. */
    fun quietForMs(): Long = (System.nanoTime() - lastActivity) / 1_000_000

    private fun touch() { lastActivity = System.nanoTime() }

    private fun stamp() = "+${(System.nanoTime() - t0) / 1_000_000}ms"

    private suspend fun <T> logged(what: String, block: suspend () -> T): T {
        lines += "${stamp()} start $what"
        running.incrementAndGet(); touch()
        return try {
            // The compose-test recomposer resumes effects unconfined, on the Default worker that ran the repository's
            // withContext; production resumes on the UI thread. Hop to Main so the screen's state writes match.
            val result = block()
            withContext(Dispatchers.Main) { result }.also { lines += "${stamp()} done  $what [resumed on ${Thread.currentThread().name}]" }
        } catch (e: CancellationException) {
            lines += "${stamp()} CANCELLED $what"
            throw e
        } catch (e: Throwable) {
            // A failure resumes the screen's catch block, which writes state: that must happen on Main too, as in
            // production (off-main state writes are the mechanism behind #202). NonCancellable so a cancelled
            // caller still gets its failure delivered on the right thread.
            withContext(NonCancellable + Dispatchers.Main) { lines += "${stamp()} FAIL  $what: ${e::class.simpleName}" }
            throw e
        } finally {
            running.decrementAndGet(); touch()
        }
    }

    fun wrap(container: AppContainer, base: DiscoverQueries = container.repository.discoverQueries()) = wrapQueries(base)

    fun wrapQueries(base: DiscoverQueries) = DiscoverQueries(
        page = { f, l, o -> logged("page(search='${f.search}' tier=${f.tier} cat=${f.category} limit=$l offset=$o)") { base.page(f, l, o) } },
        count = { f -> logged("count(search='${f.search}' cat=${f.category})") { base.count(f) } },
        facets = { f -> logged("facets(search='${f.search}' cat=${f.category})") { base.facets(f) } },
    )

    fun dump(): String = "queries:\n" + lines.joinToString("\n")
}

/**
 * waitUntil for a loaded machine: slow queries are progress, not a hang, and a finished query is delivered. It fails only when the condition is still
 * false and the screen has been quiet (no query running, none started or finished) for [quietMs], or after [capMs]
 * in total. A fixed 10 s wall-clock limit failed whenever a single SQLite query took seconds under CI load.
 * [onTimeout] builds the failure message (query log, semantics tree).
 */
internal fun androidx.compose.ui.test.junit4.ComposeTestRule.waitWhileBusy(
    queryLog: QueryLog,
    quietMs: Long = 10_000,
    capMs: Long = 180_000,
    onTimeout: (reason: String, cause: Throwable) -> Nothing,
    condition: () -> Boolean,
) {
    val started = System.nanoTime()
    var reportedSlow = false
    while (true) {
        try {
            // Query results resume on the Main looper (see logged()). Robolectric's paused looper runs a posted task
            // only when something idles it, and waitUntil's own sleep does not: under load a finished query could sit
            // there unresumed until the timeout (the timeout dump idles it, which is why "done" showed up after
            // "Timed out"). Idle it on every poll, so a result that is ready is always delivered.
            waitUntil(1_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                condition()
            }
            return
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            // Quiet is measured inside this wait: a query that ended long before the wait began is not "quiet for
            // 10 s of waiting", so the budget of a fresh wait is always the full [quietMs].
            val stalled = queryLog.inFlight() == 0 && minOf(queryLog.quietForMs(), elapsedMs) >= quietMs
            if (stalled) onTimeout("stalled: no query running or finishing for ${quietMs} ms", e)
            if (elapsedMs >= capMs) onTimeout("cap: still false after $capMs ms", e)
            if (elapsedMs >= SLOW_WAIT_MS && !reportedSlow) {
                reportedSlow = true
                println("waitWhileBusy: still waiting after ${elapsedMs / 1000}s (queries in flight: ${queryLog.inFlight()})")
            }
        }
    }
}

private const val SLOW_WAIT_MS = 30_000L
