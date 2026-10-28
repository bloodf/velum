package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateTagFilterTest {
    private val rows = listOf(
        TemplateTagFilter.Row("a", listOf("action-items", "minutes"), "en", "meeting"),
        TemplateTagFilter.Row("b", listOf("soap", "clinical-notes"), "pt", "medical"),
        TemplateTagFilter.Row("c", listOf("action-items", "sales-discovery"), "en", "sales"),
        TemplateTagFilter.Row("d", listOf("lecture"), "und", "education"),
    )

    @Test
    fun filter_by_single_tag() {
        val out = TemplateTagFilter.filter(rows, selectedTags = setOf("action-items"))
        assertEquals(listOf("a", "c"), out.map { it.id })
    }

    @Test
    fun filter_by_multiple_tags_and() {
        val out = TemplateTagFilter.filter(
            rows,
            selectedTags = setOf("action-items", "minutes"),
        )
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun filter_by_lang() {
        val out = TemplateTagFilter.filter(rows, selectedLang = "pt")
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test
    fun filter_by_category_and_tag() {
        val out = TemplateTagFilter.filter(
            rows,
            selectedTags = setOf("action-items"),
            selectedCategory = "sales",
        )
        assertEquals(listOf("c"), out.map { it.id })
    }

    @Test
    fun tags_and_langs_present() {
        assertTrue(TemplateTagFilter.tagsPresentIn(rows).contains("soap"))
        assertEquals(listOf("en", "pt", "und"), TemplateTagFilter.langsPresentIn(rows))
    }

    @Test
    fun vocabulary_encode_decode_roundtrip() {
        val encoded = TemplateTagVocabulary.encode(listOf("soap", "action-items", "soap", " "))
        assertEquals("action-items,soap", encoded)
        assertEquals(listOf("action-items", "soap"), TemplateTagVocabulary.decode(encoded))
    }

    @Test
    fun vocabulary_label_localizes() {
        assertEquals("Action items", TemplateTagVocabulary.label("action-items", "en"))
        assertEquals("Itens de ação", TemplateTagVocabulary.label("action-items", "pt-BR"))
    }

    @Test
    fun category_defaults_non_empty() {
        BuiltinTemplateCatalog.CategoryOrder.forEach { cat ->
            assertTrue(
                TemplateTagVocabulary.defaultsForCategory(cat).isNotEmpty(),
                "missing defaults for $cat",
            )
        }
    }
}
