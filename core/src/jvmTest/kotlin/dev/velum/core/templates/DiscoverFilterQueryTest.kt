package dev.velum.core.templates

import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #102: composed Discover filters run in SQL; ids are unique (`d102-*`) and categories private (`zz102-*`). */
class DiscoverFilterQueryTest {
    private suspend fun repo(): VelumRepository {
        val r = VelumRepository(createDatabase(DriverFactory()))
        suspend fun add(
            id: String, cat: String, tags: List<String>, lang: String,
            featured: Boolean = true, builtin: Boolean = true, name: String = id, desc: String = "",
        ) = r.upsertTemplate(
            id = id, name = name, category = cat, description = desc, systemPrompt = "p",
            builtin = builtin, featured = featured, tags = tags, lang = lang,
        )
        add("d102-a", "zz102-1", listOf("agenda", "decisions"), "en", name = "Alpha board", desc = "quarterly roadmap")
        add("d102-b", "zz102-1", listOf("agenda"), "pt", name = "Beta board")
        add("d102-c", "zz102-2", listOf("agenda", "decisions"), "en", featured = false, name = "Gamma")
        add("d102-d", "zz102-2", emptyList(), "und", name = "Delta")
        add("d102-e", "zz102-2", listOf("risks"), "EN ", name = "Epsilon")
        add("d102-p", "zz102-1", emptyList(), "pt", name = "Água Mineral")
        add("d102-q", "zz102-1", emptyList(), "pt", name = "ÁGUA TÔNICA")
        add("d102-w", "zz102-3", emptyList(), "en", name = "Sale 50% off_now", desc = "C:\\temp\\path")
        add("d102-m", "zz102-1", listOf("agenda"), "en", builtin = false, featured = false, name = "Mine")
        return r
    }

    private suspend fun VelumRepository.ids(f: DiscoverFilter, limit: Int = 1_000_000, offset: Int = 0) =
        queryDiscoverTemplates(f, limit, offset).map { it.id }.filter { it.startsWith("d102-") }

    private val all = DiscoverFilter(tier = DiscoverTier.All)

    @Test
    fun tiersSeparateFeaturedAllAndMine() = runBlocking {
        val r = repo()
        assertEquals(setOf("d102-a", "d102-b", "d102-d", "d102-e", "d102-p", "d102-q", "d102-w"), r.ids(DiscoverFilter(DiscoverTier.Featured)).toSet())
        assertEquals(setOf("d102-a", "d102-b", "d102-c", "d102-d", "d102-e", "d102-p", "d102-q", "d102-w"), r.ids(all).toSet())
        assertEquals(listOf("d102-m"), r.ids(DiscoverFilter(DiscoverTier.Mine)))
    }

    @Test
    fun eachFilterAlone() = runBlocking {
        val r = repo()
        assertEquals(setOf("d102-a", "d102-b", "d102-p", "d102-q"), r.ids(all.copy(category = "zz102-1")).toSet())
        assertEquals(setOf("d102-a", "d102-b", "d102-c"), r.ids(all.copy(tags = setOf("agenda"))).toSet())
        assertEquals(setOf("d102-a", "d102-c", "d102-e", "d102-w"), r.ids(all.copy(lang = "en")).toSet())
        assertEquals(setOf("d102-a"), r.ids(all.copy(search = "roadmap")).toSet())
    }

    @Test
    fun combinedFiltersIntersectWithinTier() = runBlocking {
        val r = repo()
        assertEquals(setOf("d102-a", "d102-c"), r.ids(all.copy(tags = setOf("agenda", "decisions"))).toSet())
        assertEquals(listOf("d102-a"), r.ids(all.copy(category = "zz102-1", tags = setOf("agenda"), lang = "en", search = "board")))
        // c is non-featured: the same constraints in the Featured tier exclude it.
        val f = DiscoverFilter(DiscoverTier.Featured, category = "zz102-2", tags = setOf("agenda"), lang = "en")
        assertEquals(emptyList(), r.ids(f))
        assertEquals(listOf("d102-c"), r.ids(f.copy(tier = DiscoverTier.All)))
    }

    @Test
    fun noMatchReturnsEmpty() = runBlocking {
        val r = repo()
        assertEquals(emptyList(), r.ids(all.copy(category = "zz102-1", lang = "es")))
        assertEquals(emptyList(), r.ids(all.copy(tags = setOf("agenda", "risks"))))
        assertEquals(emptyList(), r.ids(all.copy(search = "zzqqxx-nothing")))
    }

    @Test
    fun missingLanguageIsNeverInventedOrMatched() = runBlocking {
        val r = repo()
        // und rows are not matched by any language, and und/all/blank selections do not filter.
        assertTrue("d102-d" !in r.ids(all.copy(lang = "en")))
        assertTrue("d102-d" !in r.ids(all.copy(lang = "pt")))
        assertTrue("d102-d" in r.ids(all.copy(lang = "und")))
        assertTrue("d102-d" in r.ids(all.copy(lang = "")))
        assertTrue("und" !in r.discoverFacets(all.copy(category = "zz102-2")).langs)
    }

    @Test
    fun facetsComeFromCatalogMetadata() = runBlocking {
        val r = repo()
        val facets = r.discoverFacets(all.copy(category = "zz102-2"))
        assertEquals(listOf("agenda", "decisions", "risks"), facets.tags)
        assertEquals(listOf("en"), facets.langs)
        assertTrue(setOf("zz102-1", "zz102-2").all { it in r.discoverFacets(all).categories })
    }

    @Test
    fun pagingHasNoOverlapOrOmissionAndFiltersResetIt() = runBlocking {
        val r = repo()
        val f = all.copy(category = "zz102-2")
        val full = r.ids(f)
        val paged = (0 until full.size).flatMap { r.ids(f, limit = 1, offset = it) }
        assertEquals(full, paged)
        assertEquals(full.distinct(), full)
        assertEquals(listOf("d102-c"), r.ids(f.copy(lang = "en", tags = setOf("agenda")), limit = 1, offset = 0))
    }

    @Test
    fun countMatchesQuery() = runBlocking {
        val r = repo()
        val f = all.copy(category = "zz102-1", tags = setOf("agenda"))
        assertEquals(2L, r.countDiscoverTemplates(f))
        assertEquals(0L, r.countDiscoverTemplates(f.copy(lang = "es")))
    }

    @Test
    fun likeWildcardsAndBackslashAreLiteral() = runBlocking {
        val r = repo()
        assertEquals(listOf("d102-w"), r.ids(all.copy(search = "%")))
        assertEquals(listOf("d102-w"), r.ids(all.copy(search = "_")))
        assertEquals(listOf("d102-w"), r.ids(all.copy(search = "\\")))
        assertEquals(listOf("d102-w"), r.ids(all.copy(search = "50% off_now")))
    }

    @Test
    fun searchIsCaseInsensitiveForAccentsAndAndsTokens() = runBlocking {
        val r = repo()
        for (q in listOf("água", "ÁGUA", "Água")) {
            assertEquals(setOf("d102-p", "d102-q"), r.ids(all.copy(search = q)).toSet(), q)
        }
        assertEquals(listOf("d102-q"), r.ids(all.copy(search = "água tônica")))
        // Partial word: FTS has no hit, so the substring fallback applies.
        assertTrue("d102-p" in r.ids(all.copy(search = "inera")))
    }

    @Test
    fun discoverRowsMatchSelectTemplateById() = runBlocking {
        val r = repo()
        val rows = r.queryDiscoverTemplates(all.copy(category = "zz102-1"), limit = 1_000_000)
        assertTrue(rows.size >= 4)
        for (row in rows) assertEquals(r.getTemplate(row.id), row)
    }

    @Test
    fun changeFlowEmitsOnEditEvenWhenRowCountIsUnchanged() = runBlocking {
        val r = repo()
        var seen = 0
        val emissions = withTimeout(15_000) {
            r.observeTemplateChanges().onEach {
                if (++seen == 1) {
                    r.upsertTemplate(
                        id = "d102-a", name = "Alpha EDITED", category = "zz102-1", description = "",
                        systemPrompt = "p", builtin = true, featured = true, tags = listOf("agenda"), lang = "en",
                    )
                }
            }.take(2).toList()
        }
        assertEquals(2, emissions.size)
        assertTrue(emissions[1] > emissions[0])
        assertEquals("Alpha EDITED", r.queryDiscoverTemplates(all.copy(category = "zz102-1"), 1_000_000).first { it.id == "d102-a" }.name)
    }
}
