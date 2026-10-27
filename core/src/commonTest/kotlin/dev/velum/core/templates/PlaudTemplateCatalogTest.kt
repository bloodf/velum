package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaudTemplateCatalogTest {

    @Test
    fun parseJsonl_withAttributionFields() {
        val line = """
            {"id":"plaud.demo","categoryId":"meeting","category":"meeting","name":"Demo","author":"Ada","sourceTemplateId":"tpl_1","official":true,"description":"d","bodyType":"prompt","body":"Summarize","source":"Plaud template community","attribution":"By Ada · official"}
        """.trimIndent()
        val entries = PlaudTemplateCatalog.parseJsonl(line)
        assertEquals(1, entries.size)
        assertEquals("Ada", entries[0].author)
        assertTrue(entries[0].official)
        assertEquals("tpl_1", entries[0].sourceTemplateId)
        assertTrue(entries[0].creditLine().contains("Ada"))
    }

    @Test
    fun outlineBodyType_wrapsPrompt() {
        val line =
            """{"id":"plaud.o","category":"general","name":"O","author":"Bob","bodyType":"outline","body":"## Wins\n- x","source":"Plaud template community"}"""
        val entry = PlaudTemplateCatalog.parseJsonl(line).single()
        assertTrue(entry.systemPrompt().contains("## Wins"))
        assertTrue(entry.systemPrompt().contains("outline"))
    }

    @Test
    fun emptyJsonl_ok() {
        assertTrue(PlaudTemplateCatalog.parseJsonl("").isEmpty())
        assertTrue(PlaudTemplateCatalog.All.isEmpty())
    }

    @Test
    fun creditsMarkdown_listsVelumAndPlaudSections() {
        val md = PlaudTemplateCatalog.toCreditsMarkdown(emptyList())
        assertTrue(md.contains("Velum originals"))
        assertTrue(md.contains("velum.meeting"))
        assertTrue(md.contains("Awaiting collected JSONL"))
    }

    @Test
    fun builtinCatalog_mergesPlaudWhenPresent() {
        assertEquals(15, BuiltinTemplateCatalog.velumEntries().size)
        // Empty dump → All is still the 15 Velum originals
        assertEquals(15, BuiltinTemplateCatalog.All.size)
        val meeting = BuiltinTemplateCatalog.get("velum.meeting")
        assertEquals("Velum", meeting?.author)
        assertTrue(meeting?.creditLine()?.isNotBlank() == true)
    }
}
