package dev.velum.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeMarkdownTest {
    @Test
    fun parses_diarized_transcript_with_bold_speakers_and_lists() {
        val md = """
            **Heitor** · 0:00
            Bom dia.

            **Marina** · 0:12
            Status ok.

            ### Decisões
            - Priorizar Android
            - Revisar templates
        """.trimIndent()
        val blocks = parseMarkdownBlocks(md)
        assertTrue(blocks.any { it is MdBlock.Paragraph && it.text.contains("**Heitor**") })
        assertTrue(blocks.any { it is MdBlock.Heading && it.text == "Decisões" })
        assertEquals(2, blocks.count { it is MdBlock.Bullet })
        val inline = inlineMarkdown("**Heitor** · 0:00")
        assertTrue(inline.text.startsWith("Heitor"))
        assertTrue(inline.spanStyles.any { it.item.fontWeight != null })
    }

    @Test
    fun parses_summary_headings_and_checkboxes() {
        val blocks = parseMarkdownBlocks(
            """
            ## Próximos passos
            - [ ] Importar chaves
            - Fechar sync BLE
            """.trimIndent(),
        )
        assertTrue(blocks.first() is MdBlock.Heading)
        assertEquals("Próximos passos", (blocks.first() as MdBlock.Heading).text)
        assertTrue(blocks.any { it is MdBlock.Checkbox && !it.checked && it.text.contains("Importar") })
        assertTrue(blocks.any { it is MdBlock.Bullet && it.text.contains("Fechar") })
        val checked = parseMarkdownBlocks("- [x] Done\n- [ ] Todo")
        assertTrue(checked.any { it is MdBlock.Checkbox && it.checked && it.text == "Done" })
        assertTrue(checked.any { it is MdBlock.Checkbox && !it.checked && it.text == "Todo" })
    }
}
