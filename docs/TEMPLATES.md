# Templates

## Shipping policy

Velum ships **Velum originals**, optional **Velum PR contributions** (`contributor` credit in-app), and the curated **Plaud** catalog (1,500) as built-ins. Plaud author/official are documented in `docs/TEMPLATES_CREDITS.md` only — the app UI does not show them. Sync with `./scripts/sync-plaud-templates.sh` (lazy classpath assets). See `docs/templates/README.md` and `CONTRIBUTING.md`. Local JSON import remains for personal exports (`TemplateJsonImporter`).

## Plaud APK 3.30.0 note

**0 templates are embedded in the APK.** Templates are server-side (endpoints in plaud_templates_raw.json). The APK contains only 15 category icon keys. No server was contacted for the original Velum set.

## Velum originals (one per Plaud category)

| id | pt-BR | en | category | sections | languages | Plaud prompt found? |
|---|---|---|---|---|---|---|
| velum.general | Resumo geral | General summary | general | Visão geral; Pontos principais; Decisões; Próximos passos | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.meeting | Ata de reunião | Meeting minutes | meeting | Participantes; Pauta; Discussão por tópico; Decisões; Ações (responsável, prazo); Pendências | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.call | Resumo de ligação | Call summary | call | Objetivo da ligação; Pontos discutidos; Acordos; Follow-up | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.sales | Chamada de vendas | Sales call | sales | Perfil do cliente; Necessidades e dores; Objeções; Orçamento e prazo; Concorrentes citados; Próximos passos | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.interview | Entrevista | Interview | interview | Contexto; Perguntas e respostas principais; Pontos fortes; Pontos de atenção; Avaliação geral | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.consulting | Sessão de consultoria | Consulting session | consulting | Situação atual; Problemas identificados; Recomendações; Plano de ação | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.education | Aula / palestra | Lecture notes | education | Tema; Conceitos-chave; Explicações e exemplos; Termos e definições; Perguntas para revisão | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.speech | Discurso / apresentação | Speech / talk | speech | Mensagem central; Argumentos; Citações marcantes; Conclusão | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.medical | Consulta médica | Medical consultation | medical | Queixa principal; Histórico relatado; Exame / achados; Avaliação; Plano e orientações | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.legal | Reunião jurídica | Legal meeting | legal | Partes envolvidas; Fatos relatados; Questões jurídicas; Riscos; Próximas providências | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.financial | Reunião financeira | Financial review | financial | Indicadores citados; Análise; Riscos e oportunidades; Decisões; Ações | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.estate | Visita / negociação imobiliária | Real estate meeting | estate | Imóvel; Requisitos do cliente; Condições e valores; Pontos de negociação; Próximos passos | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.construction | Reunião de obra | Construction site meeting | construction | Andamento; Problemas e riscos; Segurança; Materiais e recursos; Ações e prazos | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.it | Reunião técnica (TI) | Technical / IT meeting | it | Contexto técnico; Problemas e incidentes; Soluções discutidas; Decisões de arquitetura; Tarefas | pt-BR, en names; output in the recording's language | no (Velum original) |
| velum.extractive | Extração de itens | Key item extraction | extractive | Datas e prazos; Números e valores; Nomes e entidades; Tarefas; Citações literais relevantes | pt-BR, en names; output in the recording's language | no (Velum original) |
