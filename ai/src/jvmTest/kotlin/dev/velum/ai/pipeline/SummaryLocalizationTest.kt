package dev.velum.ai.pipeline

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryLocalizationTest {
    @Test
    fun resolve_prefers_output_then_audio_then_pt() {
        assertEquals("en", SummaryLocalization.resolveLanguage("en", "pt"))
        assertEquals("es", SummaryLocalization.resolveLanguage("auto", "es-ES"))
        assertEquals("pt", SummaryLocalization.resolveLanguage("auto", "auto"))
    }

    @Test
    fun meeting_prompt_pins_pt_headings() {
        val prompt = SummaryLocalization.enhanceTemplatePrompt(
            templateId = "tpl-meeting",
            basePrompt = "Meeting Information, Notes, Decisions, Next Arrangements, AI Suggestions.",
            language = "pt",
        )
        assertContains(prompt, "## Informações da reunião")
        assertContains(prompt, "## Decisões")
        assertContains(prompt, "## Próximos passos")
        assertContains(prompt, "Brazilian Portuguese")
        assertFalse(prompt.contains("## Meeting Information"))
    }

    @Test
    fun meeting_prompt_pins_en_headings() {
        val prompt = SummaryLocalization.enhanceTemplatePrompt(
            templateId = "tpl-meeting",
            basePrompt = "Meeting Information, Notes, Decisions.",
            language = "en-US",
        )
        assertContains(prompt, "## Meeting Information")
        assertContains(prompt, "## Next Arrangements")
    }

    @Test
    fun other_language_asks_llm_to_translate_headings() {
        val prompt = SummaryLocalization.enhanceTemplatePrompt(
            templateId = "tpl-meeting",
            basePrompt = "Meeting Information, Notes, Decisions, Next Arrangements, AI Suggestions.",
            language = "ja",
        )
        assertContains(prompt, "\"ja\"")
        assertTrue(prompt.contains("translate") || prompt.contains("never leave them in English"))
        assertFalse(prompt.contains("## Meeting Information"))
    }

    @Test
    fun demo_summary_uses_pt_headings() {
        val md = SummaryLocalization.demoMeetingSummaryMarkdown(
            language = "pt",
            notesBody = "### Pipeline\n- ok",
            decisionsBody = "- Local default",
            nextBody = "- [ ] Importar chaves",
            suggestionsBody = "- Confirmar firmware",
        )
        assertContains(md, "## Informações da reunião")
        assertContains(md, "**Data:**")
        assertContains(md, "## Sugestões da IA")
        assertFalse(md.contains("Meeting Information"))
    }
}
