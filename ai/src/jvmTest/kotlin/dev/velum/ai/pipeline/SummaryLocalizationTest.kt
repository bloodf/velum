package dev.velum.ai.pipeline

import dev.velum.core.templates.BuiltinTemplateCatalog
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
    fun catalog_meeting_uses_section_list_and_language() {
        val prompt = SummaryLocalization.enhanceTemplatePrompt(
            templateId = "velum.meeting",
            basePrompt = BuiltinTemplateCatalog.get("velum.meeting")!!.systemPrompt,
            language = "pt",
        )
        assertContains(prompt, "Participantes")
        assertContains(prompt, "Decisões")
        assertContains(prompt, "omit")
        assertContains(prompt, "Brazilian Portuguese")
        assertContains(prompt, "pt-BR")
        assertFalse(prompt.contains("{output_language}"))
        assertFalse(prompt.contains("{transcript}"))
    }

    @Test
    fun legacy_tpl_meeting_alias_resolves_to_catalog() {
        val prompt = SummaryLocalization.enhanceTemplatePrompt(
            templateId = "tpl-meeting",
            basePrompt = BuiltinTemplateCatalog.get("velum.meeting")!!.systemPrompt,
            language = "en",
        )
        assertContains(prompt, "Participantes")
        assertContains(prompt, "English")
        assertFalse(prompt.contains("{output_language}"))
    }

    @Test
    fun custom_meeting_like_prompt_still_pins_classic_h2() {
        val prompt = SummaryLocalization.enhanceTemplatePrompt(
            templateId = "tpl-user-custom",
            basePrompt = "Meeting Information, Notes, Decisions, Next Arrangements, AI Suggestions.",
            language = "pt",
        )
        assertContains(prompt, "## Informações da reunião")
        assertContains(prompt, "## Decisões")
        assertContains(prompt, "## Próximos passos")
        assertFalse(prompt.contains("## Meeting Information"))
    }

    @Test
    fun other_language_asks_llm_to_translate_headings() {
        val prompt = SummaryLocalization.enhanceTemplatePrompt(
            templateId = "tpl-user-custom",
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
