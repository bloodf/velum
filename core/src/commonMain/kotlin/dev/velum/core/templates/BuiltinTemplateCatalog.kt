package dev.velum.core.templates

/**
 * Built-in Velum meeting templates (one per Plaud category key).
 * Source of truth: docs/velum_templates.json — all Velum originals, no Plaud text.
 */
object BuiltinTemplateCatalog {
    data class Spec(
        val id: String,
        val categoryKey: String,
        val namePtBr: String,
        val nameEn: String,
        val sectionsPtBr: List<String>,
        /** System prompt with optional {output_language}; transcript is the chat user message. */
        val systemPrompt: String,
        val uses: Long = 0,
    ) {
        fun displayName(languageTag: String): String {
            val lang = languageTag.lowercase().substringBefore("-").substringBefore("_")
            return if (lang == "en") nameEn else namePtBr
        }

        fun description(languageTag: String): String {
            val sections = sectionsPtBr.joinToString("; ")
            return if (languageTag.lowercase().startsWith("en")) {
                "Markdown summary · omit empty sections · $sections"
            } else {
                "Resumo em Markdown · omitir seções vazias · $sections"
            }
        }
    }

    /** Plaud category key order for Discover UI. */
    val CategoryOrder: List<String> = listOf(
        "general", "meeting", "call", "sales", "interview", "consulting", "education", "speech", "medical", "legal", "financial", "estate", "construction", "it", "extractive"
    )

    const val DEFAULT_ID: String = "velum.meeting"

    val All: List<Spec> = listOf(
        Spec(
            id = "velum.general",
            categoryKey = "general",
            namePtBr = "Resumo geral",
            nameEn = "General summary",
            sectionsPtBr = listOf("Visão geral", "Pontos principais", "Decisões", "Próximos passos"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a general summary in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Visão geral; Pontos principais; Decisões; Próximos passos. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 220,
        ),
        Spec(
            id = "velum.meeting",
            categoryKey = "meeting",
            namePtBr = "Ata de reunião",
            nameEn = "Meeting minutes",
            sectionsPtBr = listOf("Participantes", "Pauta", "Discussão por tópico", "Decisões", "Ações (responsável, prazo)", "Pendências"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a meeting minutes in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Participantes; Pauta; Discussão por tópico; Decisões; Ações (responsável, prazo); Pendências. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 942,
        ),
        Spec(
            id = "velum.call",
            categoryKey = "call",
            namePtBr = "Resumo de ligação",
            nameEn = "Call summary",
            sectionsPtBr = listOf("Objetivo da ligação", "Pontos discutidos", "Acordos", "Follow-up"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a call summary in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Objetivo da ligação; Pontos discutidos; Acordos; Follow-up. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 180,
        ),
        Spec(
            id = "velum.sales",
            categoryKey = "sales",
            namePtBr = "Chamada de vendas",
            nameEn = "Sales call",
            sectionsPtBr = listOf("Perfil do cliente", "Necessidades e dores", "Objeções", "Orçamento e prazo", "Concorrentes citados", "Próximos passos"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a sales call in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Perfil do cliente; Necessidades e dores; Objeções; Orçamento e prazo; Concorrentes citados; Próximos passos. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 401,
        ),
        Spec(
            id = "velum.interview",
            categoryKey = "interview",
            namePtBr = "Entrevista",
            nameEn = "Interview",
            sectionsPtBr = listOf("Contexto", "Perguntas e respostas principais", "Pontos fortes", "Pontos de atenção", "Avaliação geral"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a interview in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Contexto; Perguntas e respostas principais; Pontos fortes; Pontos de atenção; Avaliação geral. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.consulting",
            categoryKey = "consulting",
            namePtBr = "Sessão de consultoria",
            nameEn = "Consulting session",
            sectionsPtBr = listOf("Situação atual", "Problemas identificados", "Recomendações", "Plano de ação"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a consulting session in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Situação atual; Problemas identificados; Recomendações; Plano de ação. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.education",
            categoryKey = "education",
            namePtBr = "Aula / palestra",
            nameEn = "Lecture notes",
            sectionsPtBr = listOf("Tema", "Conceitos-chave", "Explicações e exemplos", "Termos e definições", "Perguntas para revisão"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a lecture notes in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Tema; Conceitos-chave; Explicações e exemplos; Termos e definições; Perguntas para revisão. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.speech",
            categoryKey = "speech",
            namePtBr = "Discurso / apresentação",
            nameEn = "Speech / talk",
            sectionsPtBr = listOf("Mensagem central", "Argumentos", "Citações marcantes", "Conclusão"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a speech / talk in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Mensagem central; Argumentos; Citações marcantes; Conclusão. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.medical",
            categoryKey = "medical",
            namePtBr = "Consulta médica",
            nameEn = "Medical consultation",
            sectionsPtBr = listOf("Queixa principal", "Histórico relatado", "Exame / achados", "Avaliação", "Plano e orientações"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a medical consultation in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Queixa principal; Histórico relatado; Exame / achados; Avaliação; Plano e orientações. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.legal",
            categoryKey = "legal",
            namePtBr = "Reunião jurídica",
            nameEn = "Legal meeting",
            sectionsPtBr = listOf("Partes envolvidas", "Fatos relatados", "Questões jurídicas", "Riscos", "Próximas providências"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a legal meeting in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Partes envolvidas; Fatos relatados; Questões jurídicas; Riscos; Próximas providências. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.financial",
            categoryKey = "financial",
            namePtBr = "Reunião financeira",
            nameEn = "Financial review",
            sectionsPtBr = listOf("Indicadores citados", "Análise", "Riscos e oportunidades", "Decisões", "Ações"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a financial review in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Indicadores citados; Análise; Riscos e oportunidades; Decisões; Ações. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.estate",
            categoryKey = "estate",
            namePtBr = "Visita / negociação imobiliária",
            nameEn = "Real estate meeting",
            sectionsPtBr = listOf("Imóvel", "Requisitos do cliente", "Condições e valores", "Pontos de negociação", "Próximos passos"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a real estate meeting in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Imóvel; Requisitos do cliente; Condições e valores; Pontos de negociação; Próximos passos. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.construction",
            categoryKey = "construction",
            namePtBr = "Reunião de obra",
            nameEn = "Construction site meeting",
            sectionsPtBr = listOf("Andamento", "Problemas e riscos", "Segurança", "Materiais e recursos", "Ações e prazos"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a construction site meeting in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Andamento; Problemas e riscos; Segurança; Materiais e recursos; Ações e prazos. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
        Spec(
            id = "velum.it",
            categoryKey = "it",
            namePtBr = "Reunião técnica (TI)",
            nameEn = "Technical / IT meeting",
            sectionsPtBr = listOf("Contexto técnico", "Problemas e incidentes", "Soluções discutidas", "Decisões de arquitetura", "Tarefas"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a technical / it meeting in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Contexto técnico; Problemas e incidentes; Soluções discutidas; Decisões de arquitetura; Tarefas. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 512,
        ),
        Spec(
            id = "velum.extractive",
            categoryKey = "extractive",
            namePtBr = "Extração de itens",
            nameEn = "Key item extraction",
            sectionsPtBr = listOf("Datas e prazos", "Números e valores", "Nomes e entidades", "Tarefas", "Citações literais relevantes"),
            systemPrompt = "You will receive a transcript of a recording, possibly with speaker labels and timestamps. Write a key item extraction in Markdown. Write everything, including headings, in the same language as the transcript unless an output language is specified: {output_language}. Use these sections as level-2 headings, translated into the output language, and leave out any section the transcript gives no material for: Datas e prazos; Números e valores; Nomes e entidades; Tarefas; Citações literais relevantes. Rely only on what the transcript says. Never invent facts, names or figures. Where something is unclear, mark it as unclear. Use bullet lists, bold for names and key terms, and keep it concise.",
            uses = 0,
        ),
    )

    private val byId: Map<String, Spec> = All.associateBy { it.id }

    fun get(id: String): Spec? = byId[id]

    /**
     * Resolve blank → default meeting; map legacy `tpl-*` ids to `velum.*`;
     * leave user / unknown ids unchanged.
     */
    fun resolveId(id: String?): String {
        if (id.isNullOrBlank()) return DEFAULT_ID
        if (byId.containsKey(id)) return id
        return LegacyAliases[id] ?: id
    }

    val LegacyAliases: Map<String, String> = mapOf(
        "tpl-general" to "velum.general",
        "tpl-meeting" to "velum.meeting",
        "tpl-call" to "velum.call",
        "tpl-sales" to "velum.sales",
        "tpl-interview" to "velum.interview",
        "tpl-consulting" to "velum.consulting",
        "tpl-education" to "velum.education",
        "tpl-speech" to "velum.speech",
        "tpl-medical" to "velum.medical",
        "tpl-financial" to "velum.financial",
        "tpl-realestate" to "velum.estate",
        "tpl-construction" to "velum.construction",
        "tpl-it" to "velum.it",
        "tpl-accounting" to "velum.financial",
        "tpl-functional" to "velum.extractive",
    )

    fun categoryLabel(categoryKey: String, languageTag: String): String {
        val en = languageTag.lowercase().startsWith("en")
        return when (categoryKey) {
            "general" -> if (en) "General" else "Geral"
            "meeting" -> if (en) "Meeting" else "Reunião"
            "call" -> if (en) "Call" else "Ligação"
            "sales" -> if (en) "Sales" else "Vendas"
            "interview" -> if (en) "Interview" else "Entrevista"
            "consulting" -> if (en) "Consulting" else "Consultoria"
            "education" -> if (en) "Education" else "Educação"
            "speech" -> if (en) "Speech" else "Discurso"
            "medical" -> if (en) "Medical" else "Médico"
            "legal" -> if (en) "Legal" else "Jurídico"
            "financial" -> if (en) "Financial" else "Financeiro"
            "estate" -> if (en) "Real estate" else "Imóveis"
            "construction" -> if (en) "Construction" else "Obra"
            "it" -> if (en) "IT" else "TI"
            "extractive" -> if (en) "Extractive" else "Extração"
            else -> categoryKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
