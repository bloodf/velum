package dev.velum.core.data

import dev.velum.core.db.VelumDatabase
import dev.velum.core.templates.BuiltinTemplateCatalog
import dev.velum.core.templates.TemplateTagVocabulary

object DemoFlags {
    /**
     * When true, also insert sample demo recordings on first launch.
     * Default false for product builds — Roborazzi / marketing screenshots seed their own fixtures.
     * Builtin templates / default AutoFlow always seed regardless of this flag.
     */
    const val SEED_DEMO_DATA: Boolean = false
}

object SeedData {
    private const val META_SEEDED = "demo_seed_version"
    /** Cleared on reseed so Discover "All" re-imports the gzip full catalog. */
    const val META_FULL_TEMPLATES_IMPORTED = "full_templates_imported"
    /** v10: tags + lang on templates for discoverability. */
    private const val SEED_VERSION = "10"

    fun maybeSeed(db: VelumDatabase) {
        val q = db.velumQueries
        if (q.getMeta(META_SEEDED).executeAsOneOrNull() == SEED_VERSION) return

        val now = 1_729_942_755L
        db.transaction {
            q.insertFolder("folder-inbox", "Inbox", now)
            q.insertFolder("folder-meetings", "Reuniões", now)

            if (DemoFlags.SEED_DEMO_DATA) {
                // Upsert demo recordings so upgraded seed versions refresh Markdown fixtures.
                q.deleteRecordingHard("rec-demo-1")
                q.deleteRecordingHard("rec-demo-2")
                q.insertRecording(
                    id = "rec-demo-1",
                    session_id = now - 3600,
                    folder_id = "folder-meetings",
                    // Friendly title matches started_at in UTC (demo / screenshot fixture).
                    title = "26 out, 10:39",
                    source = "note_pro",
                    device_serial = "881DEMO01",
                    started_at = 1_729_939_155L,
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
            } else {
                // Drop leftover demo rows from older builds that seeded with the flag on.
                q.deleteRecordingHard("rec-demo-1")
                q.deleteRecordingHard("rec-demo-2")
            }

            q.deleteAllBuiltinTemplates()
            BuiltinTemplateCatalog.LegacyAliases.keys.forEach { legacyId ->
                q.deleteTemplateHard(legacyId)
            }
            // Featured builtins only; full Plaud set is lazy-imported on Discover "All".
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
                    featured = if (t.featured) 1 else 0,
                    tags = t.tags,
                    lang = t.lang,
                    preferred_model = null,
                    preferred_endpoint_profile_id = null,
                    created_at = now,
                )
            }
            q.setMeta(META_FULL_TEMPLATES_IMPORTED, "0")

            q.insertAutoflow(
                id = "flow-default",
                name = "Transcrever gravações do Note Pro",
                enabled = 1,
                wake_phrase = null,
                duration_min_seconds = 0,
                duration_max_seconds = 3 * 3600,
                source_filter = "note_pro",
                template_id = BuiltinTemplateCatalog.DEFAULT_ID,
                auto_label_voice = 1,
                language = "auto",
                notify = 1,
                post_action = null,
                model_id = null,
                endpoint_profile_id = null,
            )

            q.setMeta(META_SEEDED, SEED_VERSION)
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
        val featured: Boolean = true,
        val tags: String = "",
        val lang: String = "und",
    )

    fun builtinTemplates(): List<BuiltinTemplate> =
        BuiltinTemplateCatalog.All.map { spec ->
            BuiltinTemplate(
                id = spec.id,
                name = spec.namePtBr,
                category = spec.categoryKey,
                // Keep description clean — Plaud author/official stay out of the seed blurb.
                description = spec.description("pt-BR"),
                // DB author is "Velum" for builtins unless a PR contributor is set.
                author = spec.contributor?.name ?: "Velum",
                systemPrompt = spec.systemPrompt,
                uses = spec.uses,
                featured = spec.featured,
                tags = TemplateTagVocabulary.encode(spec.resolvedTags()),
                lang = spec.lang.ifBlank { "und" },
            )
        }
}

private val DEMO_TRANSCRIPT_PT = """
**Heitor** · 0:00
Bom dia a todos. Vamos começar pela revisão do Velum no Note Pro?

**Marina** · 0:12
Bom dia. Status do sprint: a transcrição diarizada em Markdown já está no pipeline local.

**Heitor** · 0:28
Ótimo. O sync BLE com FE10 a cada connect ficou estável nos testes com Fake BLE?

**Marina** · 0:41
Sim. Unlock → download → decrypt no CI. Falta só o import das chaves reais do Note Pro.

**Heitor** · 1:05
Combinado. Resumo local com Gemma E4B e fallback de API por tarefa.

**Marina** · 1:22
Também: mapa mental a partir dos H2 do resumo, e gravação pelo microfone em WAV 16 kHz.

### Decisões
- Priorizar Android com modelos locais (Parakeet + pyannote + 3D-Speaker + Gemma E4B)
- Manter API só como override por tarefa
- iOS depois do Android estar completo

### Pendências
- Importar `snSignature` / bind token do app Plaud oficial
- Confirmar Ogg Opus + decrypt numa captura real do Note Pro
""".trimIndent()

private val DEMO_TRANSCRIPT_GENERATING_PT = """
**Heitor** · 0:00
Precisamos fechar o MVP Android com Fontes e Notas em Markdown.

**Marina** · 0:14
Concordo. iOS fica para depois do pipeline local estar completo.
""".trimIndent()

private val DEMO_SUMMARY_PT = """
## Participantes
- Heitor, Marina

## Pauta
- Revisão do pipeline local Velum / Note Pro

## Discussão por tópico
### Pipeline local
- Transcrição diarizada → Markdown em **Fontes**
- Resumo estruturado em **Notas** (Gemma E4B / API override)
- WorkManager com pausa térmica e retomada

### BLE / Note Pro
- Fake BLE E2E no CI (FE10 obrigatório)
- Chaves reais ainda bloqueadas no owner

## Decisões
- Default **Local** quando o modelo estiver Ready
- Embedding preferido: **3D-Speaker ERes2Net**

## Ações (responsável, prazo)
- [ ] Importar identidade do Note Pro — Heitor
- [ ] Validar Ogg Opus numa captura real — Marina
- [ ] Revisar templates Velum — equipe

## Pendências
- Confirmar firmware 1.5 / protocolo 20 no aparelho do usuário
""".trimIndent()
