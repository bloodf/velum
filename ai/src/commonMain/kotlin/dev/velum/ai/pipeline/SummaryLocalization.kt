package dev.velum.ai.pipeline

import dev.velum.core.templates.BuiltinTemplateCatalog

/**
 * Localizes summary / template section headings and LLM language instructions.
 *
 * Resolution order for the effective language code:
 * 1. [outputLanguage] when not blank/auto
 * 2. else [audioLanguage] when not blank/auto
 * 3. else `pt` (product default pt-BR)
 *
 * Built-in [BuiltinTemplateCatalog] prompts already list sections and ask to omit empty ones;
 * this helper substitutes `{output_language}` and reinforces the language rule.
 * For unknown/custom templates, pins meeting H2s when the prompt looks like the legacy
 * meeting outline; otherwise asks for localized generic headings.
 */
object SummaryLocalization {
    const val DEFAULT_LANGUAGE = "pt"

    data class MeetingHeadings(
        val meetingInformation: String,
        val meetingNotes: String,
        val decisions: String,
        val nextArrangements: String,
        val aiSuggestions: String,
        /** Field labels under Meeting Information. */
        val date: String,
        val location: String,
        val participants: String,
    ) {
        fun asH2List(): List<String> = listOf(
            meetingInformation,
            meetingNotes,
            decisions,
            nextArrangements,
            aiSuggestions,
        )
    }

    val MeetingPtBr = MeetingHeadings(
        meetingInformation = "Informações da reunião",
        meetingNotes = "Notas da reunião",
        decisions = "Decisões",
        nextArrangements = "Próximos passos",
        aiSuggestions = "Sugestões da IA",
        date = "Data",
        location = "Local",
        participants = "Participantes",
    )

    val MeetingEn = MeetingHeadings(
        meetingInformation = "Meeting Information",
        meetingNotes = "Meeting Notes",
        decisions = "Decisions",
        nextArrangements = "Next Arrangements",
        aiSuggestions = "AI Suggestions",
        date = "Date",
        location = "Location",
        participants = "Participants",
    )

    fun normalizeLang(raw: String?): String? {
        val v = raw?.trim()?.lowercase().orEmpty()
        if (v.isEmpty() || v == "auto") return null
        return v.substringBefore('-').substringBefore('_')
    }

    fun resolveLanguage(outputLanguage: String, audioLanguage: String): String =
        normalizeLang(outputLanguage)
            ?: normalizeLang(audioLanguage)
            ?: DEFAULT_LANGUAGE

    fun meetingHeadings(language: String): MeetingHeadings? = when (normalizeLang(language) ?: DEFAULT_LANGUAGE) {
        "pt" -> MeetingPtBr
        "en" -> MeetingEn
        else -> null
    }

    /**
     * Wraps a stored template system prompt with language + heading rules.
     * Substitutes `{output_language}` / strips leftover `{transcript}` placeholders.
     * Catalog builtins keep their own section lists (omit empty); legacy meeting prompts
     * still get pinned H2s for pt/en.
     */
    fun enhanceTemplatePrompt(
        templateId: String?,
        basePrompt: String,
        language: String,
    ): String {
        val lang = normalizeLang(language) ?: DEFAULT_LANGUAGE
        val resolvedId = BuiltinTemplateCatalog.resolveId(templateId)
        val catalog = BuiltinTemplateCatalog.get(resolvedId)
        val raw = basePrompt.trim().ifBlank {
            catalog?.systemPrompt
                ?: "Summarize into Overview, Key points, Action items."
        }
        val body = applyPlaceholders(raw, lang)
        return buildString {
            append(body)
            append("\n\n")
            append(languageInstruction(lang))
            when {
                catalog != null -> {
                    append("\n")
                    append(catalogSectionInstruction(catalog.sectionsPtBr, lang))
                }
                resolvedId == "velum.meeting" ||
                    templateId == "tpl-meeting" ||
                    looksLikeMeetingPrompt(body) -> {
                    append("\n")
                    append(meetingHeadingInstruction(lang))
                }
                else -> {
                    append("\n")
                    append(genericHeadingInstruction(lang))
                }
            }
            append("\nOmit any section that has no supporting material in the transcript.")
            append(" Output Markdown only.")
        }
    }

    fun applyPlaceholders(prompt: String, language: String): String {
        val lang = normalizeLang(language) ?: DEFAULT_LANGUAGE
        val outputLabel = when (lang) {
            "pt" -> "pt-BR"
            "en" -> "en"
            else -> lang
        }
        return prompt
            .replace("{output_language}", outputLabel)
            .replace("{transcript}", "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun catalogSectionInstruction(sectionsPtBr: List<String>, language: String): String {
        val lang = normalizeLang(language) ?: DEFAULT_LANGUAGE
        val joined = sectionsPtBr.joinToString("; ")
        return when (lang) {
            "pt" ->
                "Use estas seções como headings Markdown nível 2 (## …), traduzidas para pt-BR " +
                    "quando necessário, e omita seções sem conteúdo: $joined."
            "en" ->
                "Use these sections as Markdown H2 headings (## …), translated into English, " +
                    "and omit empty ones: $joined."
            else ->
                "Use these sections as Markdown H2 headings translated into language \"$lang\", " +
                    "and omit empty ones: $joined."
        }
    }

    fun languageInstruction(language: String): String {
        val lang = normalizeLang(language) ?: DEFAULT_LANGUAGE
        return when (lang) {
            "pt" ->
                "Write the entire summary in Brazilian Portuguese (pt-BR). " +
                    "Do not leave section headings in English."
            "en" ->
                "Write the entire summary in English."
            else ->
                "Write the entire summary — including every Markdown section heading — in language " +
                    "code \"$lang\". Do not use English headings unless $lang is English."
        }
    }

    fun meetingHeadingInstruction(language: String): String {
        val headings = meetingHeadings(language)
        return if (headings != null) {
            buildString {
                append("Use exactly these Markdown H2 section headings (## …), in this order:\n")
                headings.asH2List().forEach { append("- ## ").append(it).append('\n') }
                append("Under \"${headings.meetingInformation}\", use bold field labels ")
                append("**${headings.date}:** / **${headings.location}:** / **${headings.participants}:**.")
            }
        } else {
            val lang = normalizeLang(language) ?: DEFAULT_LANGUAGE
            "Structure as a meeting summary with H2 sections equivalent to: " +
                "meeting information, meeting notes, decisions, next arrangements, AI suggestions — " +
                "but translate those heading titles into language \"$lang\" (never leave them in English)."
        }
    }

    fun genericHeadingInstruction(language: String): String {
        val lang = normalizeLang(language) ?: DEFAULT_LANGUAGE
        return when (lang) {
            "pt" ->
                "Use clear Portuguese Markdown headings (## / ###) for each section; " +
                    "never keep English section titles."
            "en" ->
                "Use clear English Markdown headings (## / ###) for each section."
            else ->
                "Use clear Markdown headings (## / ###) written in language \"$lang\"; " +
                    "never keep English section titles unless $lang is English."
        }
    }

    fun mapReduceChunkInstruction(language: String, index: Int, lastIndex: Int): String {
        val lang = normalizeLang(language) ?: DEFAULT_LANGUAGE
        val base =
            "Summarize this transcript section ($index/$lastIndex) into concise bullets. " +
                "Keep speaker names and action items."
        return base + "\n" + languageInstruction(lang)
    }

    /** Sample meeting summary Markdown for demos / seeds in [language] (`pt` or `en`). */
    fun demoMeetingSummaryMarkdown(
        language: String,
        date: String = "2026-10-26",
        locationPt: String = "Remoto",
        locationEn: String = "Remote",
        participants: String = "Heitor, Marina",
        notesBody: String,
        decisionsBody: String,
        nextBody: String,
        suggestionsBody: String,
    ): String {
        val h = meetingHeadings(language) ?: MeetingPtBr
        val location = if ((normalizeLang(language) ?: DEFAULT_LANGUAGE) == "en") locationEn else locationPt
        return buildString {
            append("## ").append(h.meetingInformation).append('\n')
            append("- **").append(h.date).append(":** ").append(date).append('\n')
            append("- **").append(h.location).append(":** ").append(location).append('\n')
            append("- **").append(h.participants).append(":** ").append(participants).append('\n')
            append('\n')
            append("## ").append(h.meetingNotes).append('\n')
            append(notesBody.trim()).append('\n').append('\n')
            append("## ").append(h.decisions).append('\n')
            append(decisionsBody.trim()).append('\n').append('\n')
            append("## ").append(h.nextArrangements).append('\n')
            append(nextBody.trim()).append('\n').append('\n')
            append("## ").append(h.aiSuggestions).append('\n')
            append(suggestionsBody.trim()).append('\n')
        }.trimIndent()
    }

    private fun looksLikeMeetingPrompt(prompt: String): Boolean {
        val p = prompt.lowercase()
        return p.contains("meeting information") ||
            p.contains("next arrangements") ||
            p.contains("informações da reunião") ||
            p.contains("próximos passos")
    }
}
