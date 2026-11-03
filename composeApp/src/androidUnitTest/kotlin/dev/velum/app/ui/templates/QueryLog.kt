package dev.velum.app.ui.templates

import dev.velum.app.di.AppContainer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Records every Discover query (start/end, filter, outcome) so a timeout message can say what the screen asked for. */
internal class QueryLog {
    private val t0 = System.nanoTime()
    private val lines = CopyOnWriteArrayList<String>()

    private fun stamp() = "+${(System.nanoTime() - t0) / 1_000_000}ms"

    private suspend fun <T> logged(what: String, block: suspend () -> T): T {
        lines += "${stamp()} start $what"
        return try {
            // The compose-test recomposer resumes effects unconfined, on the Default worker that ran the repository's
            // withContext; production resumes on the UI thread. Hop to Main so the screen's state writes match.
            val result = block()
            withContext(Dispatchers.Main) { result }.also { lines += "${stamp()} done  $what [resumed on ${Thread.currentThread().name}]" }
        } catch (e: CancellationException) {
            lines += "${stamp()} CANCELLED $what"
            throw e
        } catch (e: Throwable) {
            lines += "${stamp()} FAIL  $what: ${e::class.simpleName}"
            throw e
        }
    }

    fun wrap(container: AppContainer, base: DiscoverQueries = container.repository.discoverQueries()) = DiscoverQueries(
        page = { f, l, o -> logged("page(search='${f.search}' tier=${f.tier} cat=${f.category} limit=$l offset=$o)") { base.page(f, l, o) } },
        count = { f -> logged("count(search='${f.search}' cat=${f.category})") { base.count(f) } },
        facets = { f -> logged("facets(search='${f.search}' cat=${f.category})") { base.facets(f) } },
    )

    fun dump(): String = "queries:\n" + lines.joinToString("\n")
}
