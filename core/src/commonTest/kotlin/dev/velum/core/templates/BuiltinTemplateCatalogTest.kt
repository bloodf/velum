package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltinTemplateCatalogTest {
    @Test
    fun has_exactly_fifteen_templates_one_per_plaud_category() {
        assertEquals(15, BuiltinTemplateCatalog.All.size)
        assertEquals(15, BuiltinTemplateCatalog.CategoryOrder.size)
        assertEquals(
            BuiltinTemplateCatalog.CategoryOrder.toSet(),
            BuiltinTemplateCatalog.All.map { it.categoryKey }.toSet(),
        )
        BuiltinTemplateCatalog.All.forEach { spec ->
            assertTrue(spec.id.startsWith("velum."), spec.id)
            assertTrue(spec.namePtBr.isNotBlank())
            assertTrue(spec.nameEn.isNotBlank())
            assertTrue(spec.sectionsPtBr.isNotEmpty())
            assertTrue(spec.systemPrompt.isNotBlank())
            assertFalse(spec.systemPrompt.contains("{transcript}"), spec.id)
            assertTrue(
                spec.systemPrompt.contains("{output_language}") ||
                    spec.systemPrompt.contains("output language"),
                spec.id,
            )
        }
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
}
