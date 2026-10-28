package dev.velum.core.templates

/**
 * Controlled template tag vocabulary (mirrors `tools/templates/tags.py`).
 * Slugs are stable; [label] localizes for en / pt-BR offline UI.
 */
object TemplateTagVocabulary {
    data class Entry(val slug: String, val en: String, val ptBr: String)

    val All: List<Entry> = listOf(
        Entry("action-items", "Action items", "Itens de ação"),
        Entry("decisions", "Decisions", "Decisões"),
        Entry("agenda", "Agenda", "Pauta"),
        Entry("key-points", "Key points", "Pontos principais"),
        Entry("follow-ups", "Follow-ups", "Follow-ups"),
        Entry("timeline", "Timeline", "Cronograma"),
        Entry("participants", "Participants", "Participantes"),
        Entry("risks", "Risks", "Riscos"),
        Entry("metrics", "Metrics", "Métricas"),
        Entry("1-1", "1:1", "1:1"),
        Entry("standup", "Standup", "Daily"),
        Entry("retrospective", "Retrospective", "Retrospectiva"),
        Entry("kickoff", "Kickoff", "Kickoff"),
        Entry("lecture", "Lecture", "Aula"),
        Entry("interview", "Interview", "Entrevista"),
        Entry("discovery", "Discovery", "Discovery"),
        Entry("demo", "Demo", "Demo"),
        Entry("negotiation", "Negotiation", "Negociação"),
        Entry("brainstorm", "Brainstorm", "Brainstorm"),
        Entry("workshop", "Workshop", "Workshop"),
        Entry("presentation", "Presentation", "Apresentação"),
        Entry("coaching", "Coaching", "Coaching"),
        Entry("onboarding", "Onboarding", "Onboarding"),
        Entry("handoff", "Handoff", "Handoff"),
        Entry("soap", "SOAP", "SOAP"),
        Entry("clinical-notes", "Clinical notes", "Notas clínicas"),
        Entry("patient-intake", "Patient intake", "Admissão do paciente"),
        Entry("legal-brief", "Legal brief", "Memorial jurídico"),
        Entry("contract", "Contract", "Contrato"),
        Entry("financial-review", "Financial review", "Revisão financeira"),
        Entry("sales-discovery", "Sales discovery", "Discovery de vendas"),
        Entry("sprint", "Sprint", "Sprint"),
        Entry("code-review", "Code review", "Code review"),
        Entry("incident", "Incident", "Incidente"),
        Entry("architecture", "Architecture", "Arquitetura"),
        Entry("real-estate", "Real estate", "Imóveis"),
        Entry("construction", "Construction", "Obra"),
        Entry("recruiting", "Recruiting", "Recrutamento"),
        Entry("performance-review", "Performance review", "Avaliação de desempenho"),
        Entry("compliance", "Compliance", "Compliance"),
        Entry("therapy", "Therapy", "Terapia"),
        Entry("research", "Research", "Pesquisa"),
        Entry("customer-support", "Customer support", "Suporte ao cliente"),
        Entry("product", "Product", "Produto"),
        Entry("bullets", "Bullets", "Tópicos"),
        Entry("sections", "Sections", "Seções"),
        Entry("checklist", "Checklist", "Checklist"),
        Entry("email-draft", "Email draft", "Rascunho de e-mail"),
        Entry("report", "Report", "Relatório"),
        Entry("transcript", "Transcript", "Transcrição"),
        Entry("minutes", "Minutes", "Ata"),
        Entry("summary", "Summary", "Resumo"),
        Entry("qa", "Q&A", "Perguntas e respostas"),
    )

    private val bySlug: Map<String, Entry> = All.associateBy { it.slug }

    fun get(slug: String): Entry? = bySlug[slug]

    fun label(slug: String, languageTag: String): String {
        val entry = bySlug[slug] ?: return slug
        val en = languageTag.lowercase().startsWith("en")
        return if (en) entry.en else entry.ptBr
    }

    /** Category priors when a built-in has no tags yet (Velum originals). */
    fun defaultsForCategory(categoryKey: String): List<String> = when (categoryKey) {
        "general" -> listOf("summary", "key-points")
        "meeting" -> listOf("minutes", "agenda", "action-items")
        "call" -> listOf("summary", "follow-ups")
        "sales" -> listOf("sales-discovery", "follow-ups")
        "interview" -> listOf("interview", "qa")
        "consulting" -> listOf("key-points", "action-items")
        "education" -> listOf("lecture", "key-points")
        "speech" -> listOf("presentation", "key-points")
        "medical" -> listOf("clinical-notes", "soap")
        "legal" -> listOf("legal-brief", "decisions")
        "financial" -> listOf("financial-review", "metrics")
        "estate" -> listOf("real-estate", "negotiation")
        "construction" -> listOf("construction", "action-items")
        "it" -> listOf("architecture", "action-items")
        "extractive" -> listOf("key-points", "bullets")
        else -> listOf("summary")
    }

    fun encode(tags: List<String>): String =
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
