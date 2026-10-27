package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaudTemplateCatalogTest {

    @Test
    fun parseJsonl_withAttributionFields_keepsDocsCredit_hidesUiCredit() {
        val line = """
            {"id":"plaud.demo","categoryId":"cat_meeting","category":"Meeting","name":"Demo","author":"Ada","sourceTemplateId":"tpl_1","official":true,"usageCount":9,"description":"d","bodyType":"prompt","body":"Summarize","source":"Plaud template community","attribution":"By Ada · official"}
        """.trimIndent()
        val entries = PlaudTemplateCatalog.parseJsonl(line)
        assertEquals(1, entries.size)
        assertEquals("Ada", entries[0].author)
        assertTrue(entries[0].official)
        assertEquals("tpl_1", entries[0].sourceTemplateId)
        assertEquals(9L, entries[0].uses)
        assertEquals("meeting", entries[0].category)
        assertTrue(entries[0].docsCreditLine().contains("Ada"))
        assertNull(entries[0].uiContributorCredit())
        assertNull(entries[0].contributor)
    }

    @Test
    fun promptBodyType_wrapsAsSkeleton_outlineAsInstructions() {
        val prompt = PlaudTemplateCatalog.parseJsonl(
            """{"id":"p","category":"general","name":"P","author":"A","bodyType":"prompt","body":"## Title\n- item","usageCount":1}""",
        ).single()
        assertTrue(prompt.systemPrompt().contains("Skeleton:"))
        assertTrue(prompt.systemPrompt().contains("## Title"))
        assertFalse(prompt.systemPrompt().contains("Instructions:"))

        val outline = PlaudTemplateCatalog.parseJsonl(
            """{"id":"o","category":"general","name":"O","author":"Bob","bodyType":"outline","body":"List wins and blockers","usageCount":2}""",
        ).single()
        assertTrue(outline.systemPrompt().contains("Instructions:"))
        assertTrue(outline.systemPrompt().contains("List wins and blockers"))
        assertFalse(outline.systemPrompt().contains("Skeleton:"))
    }

    @Test
    fun mapCategory_coversAllCuratedIds() {
        assertEquals("estate", PlaudTemplateCatalog.mapCategoryKey("cat_real_estate", "Real Estate"))
        assertEquals("it", PlaudTemplateCatalog.mapCategoryKey("cat_it_engineering", "IT & Engineering"))
        assertEquals("extractive", PlaudTemplateCatalog.mapCategoryKey("cat_functional", "Functional"))
        assertEquals("construction", PlaudTemplateCatalog.mapCategoryKey("cat_construction", "Construction"))
    }

    @Test
    fun emptyJsonl_ok() {
        assertTrue(PlaudTemplateCatalog.parseJsonl("").isEmpty())
    }

    @Test
    fun creditsMarkdown_listsVelumAndPlaudSections() {
        val md = PlaudTemplateCatalog.toCreditsMarkdown(emptyList())
        assertTrue(md.contains("Velum originals"))
        assertTrue(md.contains("velum.meeting"))
        assertTrue(md.contains("does **not** show Plaud author"))
    }

    @Test
    fun fullCatalog_loadsAndValidatesEveryEntry() {
        val entries = PlaudTemplateCatalog.All
        assertEquals(1500, entries.size, "expected curated 100×15 catalog")
        val ids = mutableSetOf<String>()
        val perCategory = mutableMapOf<String, Int>()
        var official = 0
        entries.forEach { e ->
            assertTrue(e.id.isNotBlank(), "blank id")
            assertTrue(ids.add(e.id), "duplicate id ${e.id}")
            assertTrue(e.name.isNotBlank(), e.id)
            assertTrue(e.author.isNotBlank(), e.id)
            assertTrue(e.body.isNotBlank(), e.id)
            assertTrue(e.uses >= 0, e.id)
            assertTrue(
                e.category in BuiltinTemplateCatalog.CategoryOrder,
                "${e.id} category=${e.category}",
            )
            assertNotNull(e.bodyType)
            val prompt = e.systemPrompt()
            assertTrue(prompt.isNotBlank(), e.id)
            when (e.bodyType) {
                TemplateBodyType.Prompt -> {
                    assertTrue(prompt.contains("Skeleton:"), e.id)
                    assertTrue(prompt.contains(e.body), e.id)
                }
                TemplateBodyType.Outline -> {
                    assertTrue(prompt.contains("Instructions:"), e.id)
                    assertTrue(prompt.contains(e.body), e.id)
                }
            }
            assertNull(e.contributor, "Plaud rows must not carry contributor: ${e.id}")
            assertNull(e.uiContributorCredit(), e.id)
            assertTrue(e.docsCreditLine().isNotBlank(), e.id)
            perCategory[e.category] = (perCategory[e.category] ?: 0) + 1
            if (e.official) official++
        }
        assertEquals(15, perCategory.size)
        perCategory.values.forEach { assertEquals(100, it) }
        assertEquals(41, official)
        // Unique display names within each category; bodies unique globally.
        val namesByCategory = mutableMapOf<String, MutableSet<String>>()
        val bodies = mutableSetOf<String>()
        entries.forEach { e ->
            val names = namesByCategory.getOrPut(e.category) { mutableSetOf() }
            assertTrue(names.add(e.name), "duplicate name in ${e.category}: ${e.name}")
            assertTrue(bodies.add(e.body.trim()), "duplicate body: ${e.id}")
        }
        assertTrue(BuiltinTemplateCatalog.All.size >= 1515)
        assertEquals(15, BuiltinTemplateCatalog.velumEntries().size)
    }

    @Test
    fun contributedCatalog_surfacesUiCreditOnly() {
        val line =
            """{"id":"standup","category":"meeting","name":"Standup","description":"d","bodyType":"outline","body":"Wins and blockers","contributor":{"name":"Ada Lovelace","github":"ada"}}"""
        val entry = VelumContributedCatalog.parseJsonl(line).single()
        assertEquals("velum.contrib.standup", entry.id)
        assertEquals("Ada Lovelace (@ada)", entry.uiContributorCredit())
        assertFalse(entry.official)
    }
}
