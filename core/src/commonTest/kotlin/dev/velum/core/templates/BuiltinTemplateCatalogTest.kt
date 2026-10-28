package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltinTemplateCatalogTest {
    @Test
    fun velum_originals_one_per_plaud_category() {
        val velum = BuiltinTemplateCatalog.velumEntries()
        assertEquals(15, velum.size)
        assertEquals(15, BuiltinTemplateCatalog.CategoryOrder.size)
        assertEquals(
            BuiltinTemplateCatalog.CategoryOrder.toSet(),
            velum.map { it.category }.toSet(),
        )
        velum.forEach { entry ->
            assertTrue(entry.id.startsWith("velum."), entry.id)
            assertTrue(entry.name.isNotBlank())
            assertTrue(entry.body.isNotBlank())
            assertTrue(entry.featured)
            assertTrue(entry.tags.isNotEmpty(), entry.id)
            assertNull(entry.uiContributorCredit())
        }
    }

    @Test
    fun all_merges_featured_plaud_catalog_not_full() {
        assertEquals(15, BuiltinTemplateCatalog.velumEntries().size)
        assertTrue(PlaudTemplateCatalog.All.size >= 1500)
        assertEquals(
            BuiltinTemplateCatalog.velumEntries().size +
                PlaudTemplateCatalog.All.size +
                VelumContributedCatalog.All.size,
            BuiltinTemplateCatalog.All.size,
        )
        val meeting = assertNotNull(BuiltinTemplateCatalog.get("velum.meeting"))
        assertEquals("Velum", meeting.author)
        assertTrue(meeting.featured)
        assertNull(meeting.uiContributorCredit())
    }

    @Test
    fun plaud_builtin_has_no_ui_author_credit() {
        val plaud = assertNotNull(PlaudTemplateCatalog.All.firstOrNull())
        val spec = assertNotNull(BuiltinTemplateCatalog.get(plaud.id))
        assertNull(spec.uiContributorCredit())
        assertTrue(spec.featured)
        assertTrue(spec.catalogDescription.isNotBlank() || spec.description("en").isNotBlank())
        assertTrue(spec.docsCreditLine().isNotBlank())
    }

    @Test
    fun display_name_follows_locale() {
        val meeting = assertNotNull(BuiltinTemplateCatalog.get("velum.meeting"))
        assertEquals("Ata de reunião", meeting.displayName("pt-BR"))
        assertEquals("Meeting minutes", meeting.displayName("en"))
        assertEquals("Reunião", BuiltinTemplateCatalog.categoryLabel("meeting", "pt-BR"))
        assertEquals("Meeting", BuiltinTemplateCatalog.categoryLabel("meeting", "en"))
    }

    @Test
    fun legacy_aliases_map_to_catalog() {
        assertEquals("velum.meeting", BuiltinTemplateCatalog.resolveId("tpl-meeting"))
        assertEquals("velum.general", BuiltinTemplateCatalog.resolveId("tpl-general"))
        assertEquals("velum.estate", BuiltinTemplateCatalog.resolveId("tpl-realestate"))
        assertEquals("velum.meeting", BuiltinTemplateCatalog.resolveId(null))
        assertEquals("velum.meeting", BuiltinTemplateCatalog.resolveId(""))
    }

    @Test
    fun velum_system_prompts_keep_output_language_hook() {
        BuiltinTemplateCatalog.velumEntries().forEach { entry ->
            val spec = assertNotNull(BuiltinTemplateCatalog.get(entry.id))
            assertFalse(spec.systemPrompt.contains("{transcript}"), spec.id)
            assertTrue(
                spec.systemPrompt.contains("{output_language}") ||
                    spec.systemPrompt.contains("output language"),
                spec.id,
            )
        }
    }
}
