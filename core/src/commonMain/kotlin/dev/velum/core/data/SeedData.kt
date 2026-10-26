package dev.velum.core.data

import dev.velum.core.db.VelumDatabase

object DemoFlags {
    /** When true, seed sample recordings/templates on first launch. */
    const val SEED_DEMO_DATA: Boolean = true
}

object SeedData {
    private const val META_SEEDED = "demo_seeded_v2"

    fun maybeSeed(db: VelumDatabase) {
        if (!DemoFlags.SEED_DEMO_DATA) return
        val q = db.velumQueries
        if (q.getMeta(META_SEEDED).executeAsOneOrNull() != null) return

        val now = 1_729_942_755L
        db.transaction {
            q.insertFolder("folder-inbox", "Inbox", now)
            q.insertFolder("folder-meetings", "Reuniões", now)

            q.insertRecording(
                id = "rec-demo-1",
                session_id = now - 3600,
                folder_id = "folder-meetings",
                title = "2026-10-26 10:59:15",
                source = "note_pro",
                device_serial = "881DEMO01",
                started_at = now - 3600,
                duration_seconds = 36 * 60,
                size_bytes = 8_500_000,
                local_path = null,
                transcript = DEMO_TRANSCRIPT_PT,
                summary_markdown = DEMO_SUMMARY_PT,
                status = "ready",
                trashed = 0,
                created_at = now - 3600,
            )
            q.insertRecording(
                id = "rec-demo-2",
                session_id = now - 86400,
                folder_id = null,
                title = "Planejamento de produto",
                source = "phone",
                device_serial = null,
                started_at = now - 86400,
                duration_seconds = 26 * 60 + 5,
                size_bytes = 6_200_000,
                local_path = null,
                transcript = DEMO_TRANSCRIPT_GENERATING_PT,
                summary_markdown = null,
                status = "generating",
                trashed = 0,
                created_at = now - 86400,
            )

            builtinTemplates().forEach { t ->
                q.insertTemplate(
                    id = t.id,
                    name = t.name,
                    category = t.category,
                    description = t.description,
                    author = t.author,
                    system_prompt = t.systemPrompt,
                    uses = t.uses,
                    builtin = 1,
                    created_at = now,
                )
            }

            q.insertAutoflow(
                id = "flow-default",
                name = "Transcrever gravações do Note Pro",
                enabled = 1,
                wake_phrase = null,
                duration_min_seconds = 0,
                duration_max_seconds = 3 * 3600,
                source_filter = "note_pro",
                template_id = "tpl-meeting",
                auto_label_voice = 1,
                language = "auto",
                notify = 1,
                post_action = null,
            )

            q.setMeta(META_SEEDED, "1")
        }
    }

    data class BuiltinTemplate(
        val id: String,
        val name: String,
        val category: String,
        val description: String,
        val author: String = "Velum",
        val systemPrompt: String,
        val uses: Long = 0,
    )

    fun builtinTemplates(): List<BuiltinTemplate> = listOf(
        BuiltinTemplate("tpl-general", "Notas gerais", "General",
            "Resumo estruturado com pontos-chave e próximos passos.",
            systemPrompt = "Summarize into Overview, Key points, Action items."),
        BuiltinTemplate("tpl-meeting", "Destaques da reunião", "Meeting",
            "Informações da reunião, notas por tópico, decisões e checklist.",
            systemPrompt = "Meeting Information, Notes, Decisions, Next Arrangements, AI Suggestions.",
            uses = 942),
        BuiltinTemplate("tpl-speech", "Discurso", "Speech",
            "Tema central, argumentos e citações memoráveis.",
            systemPrompt = "Extract thesis, arguments, memorable quotes."),
        BuiltinTemplate("tpl-call", "Ligação", "Call",
            "Objetivo da chamada, acordos e follow-ups.",
            systemPrompt = "Purpose, agreements, follow-ups with owners."),
        BuiltinTemplate("tpl-interview", "Entrevista", "Interview",
            "Perguntas, respostas e insights.",
            systemPrompt = "Structure as Q&A with key insights."),
        BuiltinTemplate("tpl-medical", "Consulta (rascunho)", "Medical",
            "Rascunho de anamnese — não substitui prontuário clínico.",
            systemPrompt = "Draft clinical note. Flag uncertainties. Not a medical record."),
        BuiltinTemplate("tpl-sales", "Reunião de vendas", "Sales",
            "Dor do cliente, proposta de valor e próximos passos.",
            systemPrompt = "Pain, value prop, objections, next commercial steps.",
            uses = 401),
        BuiltinTemplate("tpl-consulting", "Consultoria", "Consulting",
            "Diagnóstico, recomendações e riscos.",
            systemPrompt = "Diagnosis, recommendations, risks, open questions."),
        BuiltinTemplate("tpl-education", "Aula / workshop", "Education",
            "Objetivos de aprendizagem e resumo didático.",
            systemPrompt = "Learning objectives, core concepts, didactic summary."),
        BuiltinTemplate("tpl-construction", "Obra / campo", "Construction",
            "Pendências de obra, segurança e responsáveis.",
            systemPrompt = "Site issues, safety notes, owners, deadlines."),
        BuiltinTemplate("tpl-it", "Reunião de TI", "IT & Engineering",
            "Decisões técnicas, riscos e action items com donos.",
            systemPrompt = "Decisions, risks, action items (owner, due).",
            uses = 512),
        BuiltinTemplate("tpl-accounting", "Aprovação / contábil", "Accounting/Approval",
            "Itens para aprovação e evidências citadas.",
            systemPrompt = "Pending approvals, amounts, cited evidence."),
        BuiltinTemplate("tpl-realestate", "Imóveis", "Real estate",
            "Preferências do cliente, imóveis citados e follow-ups.",
            systemPrompt = "Client preferences, properties, follow-ups."),
        BuiltinTemplate("tpl-financial", "Financeiro", "Financial",
            "Números mencionados, riscos e ações.",
            systemPrompt = "Figures, financial risks, action items."),
        BuiltinTemplate("tpl-functional", "Fluxo funcional", "Functional",
            "Requisitos, regras de negócio e critérios de aceite.",
            systemPrompt = "Requirements, business rules, acceptance criteria."),
    )
}

private val DEMO_TRANSCRIPT_PT = """
**Speaker 1** · 0:00
Bom dia a todos. Vamos começar?

**Speaker 2** · 0:08
Bom dia. Vamos começar pelo status do sprint.

**Speaker 1** · 0:15
O MVP Android está quase fechado. Falta o sync BLE.

**Speaker 2** · 0:28
Concordo. iOS fica para depois do Android estar completo.

### Decisões
- Priorizar Android
- Revisar templates Velum nesta semana
""".trimIndent()

private val DEMO_TRANSCRIPT_GENERATING_PT = """
**Speaker 1** · 0:00
Precisamos fechar o MVP Android.

**Speaker 2** · 0:12
Concordo, iOS fica para depois.
""".trimIndent()

private val DEMO_SUMMARY_PT = """
## Meeting Information
- **Date:** 2026-10-26
- **Location:** Remoto
- **Participants:** Speaker 1, Speaker 2

## Meeting Notes
### Status do sprint
- Alinhamento sobre o MVP Android
- iOS adiado até o Android estar completo

## Next Arrangements
- [ ] Fechar sync BLE no Android
- [ ] Revisar templates Velum

## AI Suggestions
- Confirmar firmware 1.5 / protocolo 20 no aparelho do usuário
""".trimIndent()
