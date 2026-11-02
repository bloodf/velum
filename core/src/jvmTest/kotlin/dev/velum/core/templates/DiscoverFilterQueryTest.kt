package dev.velum.core.templates

import dev.velum.core.data.DriverFactory
import dev.velum.core.data.VelumRepository
import dev.velum.core.data.createDatabase
import kotlinx.coroutines.runBlocking
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
        add("d102-m", "zz102-1", listOf("agenda"), "en", builtin = false, featured = false, name = "Mine")
        return r
    }

    private suspend fun VelumRepository.ids(f: DiscoverFilter, limit: Int = 1_000_000, offset: Int = 0) =
        queryDiscoverTemplates(f, limit, offset).map { it.id }.filter { it.startsWith("d102-") }

    private val all = DiscoverFilter(tier = DiscoverTier.All)

    @Test
    fun tiersSeparateFeaturedAllAndMine() = runBlocking {
        val r = repo()
        assertEquals(setOf("d102-a", "d102-b", "d102-d", "d102-e"), r.ids(DiscoverFilter(DiscoverTier.Featured)).toSet())
        assertEquals(setOf("d102-a", "d102-b", "d102-c", "d102-d", "d102-e"), r.ids(all).toSet())
        assertEquals(listOf("d102-m"), r.ids(DiscoverFilter(DiscoverTier.Mine)))
    }

    @Test
    fun eachFilterAlone() = runBlocking {
        val r = repo()
        assertEquals(setOf("d102-a", "d102-b"), r.ids(all.copy(category = "zz102-1")).toSet())
        assertEquals(setOf("d102-a", "d102-b", "d102-c"), r.ids(all.copy(tags = setOf("agenda"))).toSet())
        assertEquals(setOf("d102-a", "d102-c", "d102-e"), r.ids(all.copy(lang = "en")).toSet())
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
    fun likeWildcardsInSearchAreLiteral() = runBlocking {
        val r = repo()
        assertEquals(emptyList(), r.ids(all.copy(search = "%")))
        assertEquals(emptyList(), r.ids(all.copy(search = "_")))
    }
}
