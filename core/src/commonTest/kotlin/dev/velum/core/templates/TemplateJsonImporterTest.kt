package dev.velum.core.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateJsonImporterTest {

    @Test
    fun parsesWrappedArray() {
        val specs = TemplateJsonImporter.parse(
            """
            {"templates":[
              {"name":"A","category":"Meeting","description":"d","body":"## Hello","author":"Me"}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, specs.size)
        assertEquals("A", specs[0].name)
        assertEquals("## Hello", specs[0].resolvedPrompt())
    }

    @Test
    fun parsesBareArray_andOutlineAlias() {
        val specs = TemplateJsonImporter.parse(
            """[{"name":"B","outline":"- item","category":"Notes"}]""",
        )
        // No bodyType → raw body/outline used as the prompt.
        assertEquals("- item", specs.single().resolvedPrompt())
        assertTrue(TemplateJsonImporter.stableId(specs.single(), 0).startsWith("user-import-"))
    }

    @Test
    fun bodyType_promptIsSkeleton_outlineIsInstructions() {
        val prompt = TemplateJsonImporter.parse(
            """[{"name":"P","body":"## H","bodyType":"prompt"}]""",
        ).single().resolvedPrompt()
        assertTrue(prompt.contains("Skeleton:"))
        assertTrue(prompt.contains("## H"))

        val outline = TemplateJsonImporter.parse(
            """[{"name":"O","body":"List wins","bodyType":"outline"}]""",
        ).single().resolvedPrompt()
        assertTrue(outline.contains("Instructions:"))
        assertTrue(outline.contains("List wins"))
    }

    @Test
    fun rejectsMissingBody() {
        assertFailsWith<IllegalArgumentException> {
            TemplateJsonImporter.parse("""[{"name":"Empty"}]""").single().resolvedPrompt()
        }
    }
}
