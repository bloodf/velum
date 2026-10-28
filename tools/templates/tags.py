#!/usr/bin/env python3
"""
Controlled tag vocabulary + rule-based assignment for template hygiene.

Slugs are stable identifiers shipped in JSONL / DB. Localized labels (en / pt-BR)
live here and are mirrored in Kotlin `TemplateTagVocabulary` for offline UI.
Language stays on the row `lang` field — do not emit lang-* tags.
"""
from __future__ import annotations

import re
from typing import Iterable

# {slug, en, ptBr} — keep ≤80 entries; expand thoughtfully.
VOCABULARY: list[dict[str, str]] = [
    # Structure
    {"slug": "action-items", "en": "Action items", "ptBr": "Itens de ação"},
    {"slug": "decisions", "en": "Decisions", "ptBr": "Decisões"},
    {"slug": "agenda", "en": "Agenda", "ptBr": "Pauta"},
    {"slug": "key-points", "en": "Key points", "ptBr": "Pontos principais"},
    {"slug": "follow-ups", "en": "Follow-ups", "ptBr": "Follow-ups"},
    {"slug": "timeline", "en": "Timeline", "ptBr": "Cronograma"},
    {"slug": "participants", "en": "Participants", "ptBr": "Participantes"},
    {"slug": "risks", "en": "Risks", "ptBr": "Riscos"},
    {"slug": "metrics", "en": "Metrics", "ptBr": "Métricas"},
    # Meeting / interaction types
    {"slug": "1-1", "en": "1:1", "ptBr": "1:1"},
    {"slug": "standup", "en": "Standup", "ptBr": "Daily"},
    {"slug": "retrospective", "en": "Retrospective", "ptBr": "Retrospectiva"},
    {"slug": "kickoff", "en": "Kickoff", "ptBr": "Kickoff"},
    {"slug": "lecture", "en": "Lecture", "ptBr": "Aula"},
    {"slug": "interview", "en": "Interview", "ptBr": "Entrevista"},
    {"slug": "discovery", "en": "Discovery", "ptBr": "Discovery"},
    {"slug": "demo", "en": "Demo", "ptBr": "Demo"},
    {"slug": "negotiation", "en": "Negotiation", "ptBr": "Negociação"},
    {"slug": "brainstorm", "en": "Brainstorm", "ptBr": "Brainstorm"},
    {"slug": "workshop", "en": "Workshop", "ptBr": "Workshop"},
    {"slug": "presentation", "en": "Presentation", "ptBr": "Apresentação"},
    {"slug": "coaching", "en": "Coaching", "ptBr": "Coaching"},
    {"slug": "onboarding", "en": "Onboarding", "ptBr": "Onboarding"},
    {"slug": "handoff", "en": "Handoff", "ptBr": "Handoff"},
    # Domain
    {"slug": "soap", "en": "SOAP", "ptBr": "SOAP"},
    {"slug": "clinical-notes", "en": "Clinical notes", "ptBr": "Notas clínicas"},
    {"slug": "patient-intake", "en": "Patient intake", "ptBr": "Admissão do paciente"},
    {"slug": "legal-brief", "en": "Legal brief", "ptBr": "Memorial jurídico"},
    {"slug": "contract", "en": "Contract", "ptBr": "Contrato"},
    {"slug": "financial-review", "en": "Financial review", "ptBr": "Revisão financeira"},
    {"slug": "sales-discovery", "en": "Sales discovery", "ptBr": "Discovery de vendas"},
    {"slug": "sprint", "en": "Sprint", "ptBr": "Sprint"},
    {"slug": "code-review", "en": "Code review", "ptBr": "Code review"},
    {"slug": "incident", "en": "Incident", "ptBr": "Incidente"},
    {"slug": "architecture", "en": "Architecture", "ptBr": "Arquitetura"},
    {"slug": "real-estate", "en": "Real estate", "ptBr": "Imóveis"},
    {"slug": "construction", "en": "Construction", "ptBr": "Obra"},
    {"slug": "recruiting", "en": "Recruiting", "ptBr": "Recrutamento"},
    {"slug": "performance-review", "en": "Performance review", "ptBr": "Avaliação de desempenho"},
    {"slug": "compliance", "en": "Compliance", "ptBr": "Compliance"},
    {"slug": "therapy", "en": "Therapy", "ptBr": "Terapia"},
    {"slug": "research", "en": "Research", "ptBr": "Pesquisa"},
    {"slug": "customer-support", "en": "Customer support", "ptBr": "Suporte ao cliente"},
    {"slug": "product", "en": "Product", "ptBr": "Produto"},
    # Output shape
    {"slug": "bullets", "en": "Bullets", "ptBr": "Tópicos"},
    {"slug": "sections", "en": "Sections", "ptBr": "Seções"},
    {"slug": "checklist", "en": "Checklist", "ptBr": "Checklist"},
    {"slug": "email-draft", "en": "Email draft", "ptBr": "Rascunho de e-mail"},
    {"slug": "report", "en": "Report", "ptBr": "Relatório"},
    {"slug": "transcript", "en": "Transcript", "ptBr": "Transcrição"},
    {"slug": "minutes", "en": "Minutes", "ptBr": "Ata"},
    {"slug": "summary", "en": "Summary", "ptBr": "Resumo"},
    {"slug": "qa", "en": "Q&A", "ptBr": "Perguntas e respostas"},
]

VOCAB_SLUGS: set[str] = {t["slug"] for t in VOCABULARY}
VOCAB_BY_SLUG: dict[str, dict[str, str]] = {t["slug"]: t for t in VOCABULARY}

# Always ≥1 tag: category priors applied when rules miss.
CATEGORY_DEFAULT_TAGS: dict[str, list[str]] = {
    "general": ["summary", "key-points"],
    "meeting": ["minutes", "agenda", "action-items"],
    "call": ["summary", "follow-ups"],
    "sales": ["sales-discovery", "follow-ups"],
    "interview": ["interview", "qa"],
    "consulting": ["key-points", "action-items"],
    "education": ["lecture", "key-points"],
    "speech": ["presentation", "key-points"],
    "medical": ["clinical-notes", "soap"],
    "legal": ["legal-brief", "decisions"],
    "financial": ["financial-review", "metrics"],
    "estate": ["real-estate", "negotiation"],
    "construction": ["construction", "action-items"],
    "it": ["architecture", "action-items"],
    "extractive": ["key-points", "bullets"],
}

# (slug, patterns) — patterns matched casefold against name+description+body.
# Keep patterns specific enough to avoid flooding every row with the same tags.
_TAG_PATTERNS: list[tuple[str, list[re.Pattern[str]]]] = [
    ("action-items", [
        r"\baction items?\b", r"\bnext steps?\b", r"\btarefas?\b", r"\bitens? de a[cç][aã]o\b",
        r"\bpr[oó]ximos passos\b", r"\bto-?dos?\b", r"\bowner\b.{0,20}\bdeadline\b",
    ]),
    ("decisions", [
        r"\bdecisions?\b", r"\bdecis[oõ]es\b", r"\bagreed\b", r"\bacordos?\b",
    ]),
    ("agenda", [
        r"\bagenda\b", r"\bpauta\b", r"\btopics? (?:to )?cover\b", r"\bmeeting topics?\b",
    ]),
    ("key-points", [
        r"\bkey points?\b", r"\bmain points?\b", r"\bpontos? (?:principais|chave)\b",
        r"\btakeaways?\b", r"\bhighlights?\b",
    ]),
    ("follow-ups", [
        r"\bfollow[ -]?ups?\b", r"\bfollow up\b", r"\bacompanhamento\b",
    ]),
    ("timeline", [
        r"\btimeline\b", r"\bcronograma\b", r"\bschedule\b", r"\bmilestones?\b", r"\bprazos?\b",
    ]),
    ("participants", [
        r"\bparticipants?\b", r"\battendees?\b", r"\bparticipantes?\b", r"\bspeakers?\b",
    ]),
    ("risks", [
        r"\brisks?\b", r"\briscos?\b", r"\bblockers?\b", r"\bimpediments?\b",
    ]),
    ("metrics", [
        r"\bmetrics?\b", r"\bkpis?\b", r"\bm[eé]tricas?\b", r"\bindicadores?\b", r"\bnumbers?\b",
    ]),
    ("1-1", [
        r"\b1\s*[:\-–]\s*1\b", r"\bone[ -]?on[ -]?one\b", r"\b1:1\b", r"\bone to one\b",
    ]),
    ("standup", [
        r"\bstand[ -]?ups?\b", r"\bdaily\b", r"\bscrum daily\b",
    ]),
    ("retrospective", [
        r"\bretrospectives?\b", r"\bretros?\b", r"\bretrospectiva\b", r"\bpost[ -]?mortem\b",
    ]),
    ("kickoff", [
        r"\bkick[ -]?offs?\b", r"\blaunch meeting\b", r"\bin[ií]cio (?:do|de) projeto[oa]\b",
    ]),
    ("lecture", [
        r"\blectures?\b", r"\bclass notes?\b", r"\baula\b", r"\blesson\b", r"\bcourse\b",
    ]),
    ("interview", [
        r"\binterviews?\b", r"\bentrevistas?\b", r"\bcandidate\b", r"\bhiring\b",
    ]),
    ("discovery", [
        r"\bdiscovery\b", r"\bneeds assessment\b", r"\blevantamento de necessidades\b",
    ]),
    ("demo", [
        r"\bdemos?\b", r"\bproduct demo\b", r"\bwalkthrough\b", r"\bdemonstra[cç][aã]o\b",
    ]),
    ("negotiation", [
        r"\bnegotiations?\b", r"\bnegocia[cç][aã]o\b", r"\bbargain\b", r"\bdeal terms?\b",
    ]),
    ("brainstorm", [
        r"\bbrainstorm(?:ing)?\b", r"\bideation\b", r"\btorrente de ideias\b",
    ]),
    ("workshop", [
        r"\bworkshops?\b", r"\boficina\b",
    ]),
    ("presentation", [
        r"\bpresentations?\b", r"\bpalestra\b", r"\btalk\b", r"\bkeynote\b", r"\bdiscurso\b",
    ]),
    ("coaching", [
        r"\bcoaching\b", r"\bmentoring\b", r"\bmentor(?:ia|ship)?\b",
    ]),
    ("onboarding", [
        r"\bonboarding\b", r"\bnew hire\b", r"\bintegra[cç][aã]o\b",
    ]),
    ("handoff", [
        r"\bhandoffs?\b", r"\bhand[ -]?over\b", r"\btransa[ií][cç][aã]o\b",
    ]),
    ("soap", [
        r"\bsoap\b", r"\bsubjective\b.{0,40}\bobjective\b.{0,40}\bassessment\b",
    ]),
    ("clinical-notes", [
        r"\bclinical notes?\b", r"\bnotas? cl[ií]nicas?\b", r"\bconsultation notes?\b",
        r"\bpatient notes?\b", r"\bprogress notes?\b",
    ]),
    ("patient-intake", [
        r"\bpatient intake\b", r"\bintake\b", r"\banamnese\b", r"\badmiss[aã]o\b",
    ]),
    ("legal-brief", [
        r"\blegal brief\b", r"\bcase brief\b", r"\bmemorial\b", r"\bpleading\b",
        r"\bjuridic", r"\blegal (?:meeting|summary|notes?)\b",
    ]),
    ("contract", [
        r"\bcontracts?\b", r"\bagreements?\b", r"\bclausulas?\b", r"\bcl[aá]usulas?\b",
    ]),
    ("financial-review", [
        r"\bfinancial (?:review|meeting|summary)\b", r"\bbudget\b", r"\bforecast\b",
        r"\brevis[aã]o financeira\b", r"\bor[cç]amento\b",
    ]),
    ("sales-discovery", [
        r"\bsales discovery\b", r"\bsales call\b", r"\bqualifica[cç][aã]o\b",
        r"\bpain points?\b", r"\bobjection", r"\bpipeline\b", r"\bprospect\b",
    ]),
    ("sprint", [
        r"\bsprints?\b", r"\bsprint (?:planning|review|retro)\b", r"\bbacklog\b",
    ]),
    ("code-review", [
        r"\bcode reviews?\b", r"\bpull requests?\b", r"\bprs?\b", r"\bmerge request\b",
    ]),
    ("incident", [
        r"\bincidents?\b", r"\boutage\b", r"\bsev[1-3]\b", r"\broot cause\b", r"\bp[oó]s.?mortem\b",
    ]),
    ("architecture", [
        r"\barchitecture\b", r"\bdesign (?:doc|review)\b", r"\bsystem design\b", r"\barquitetura\b",
    ]),
    ("real-estate", [
        r"\breal estate\b", r"\bproperty\b", r"\bim[oó]ve", r"\blisting\b", r"\bbroker\b",
    ]),
    ("construction", [
        r"\bconstruction\b", r"\bsite meeting\b", r"\bobra\b", r"\bjobsite\b", r"\bbuilding site\b",
    ]),
    ("recruiting", [
        r"\brecruiting\b", r"\brecrutamento\b", r"\btalent\b", r"\bjob interview\b",
    ]),
    ("performance-review", [
        r"\bperformance reviews?\b", r"\bavalia[cç][aã]o de desempenho\b", r"\b360 review\b",
    ]),
    ("compliance", [
        r"\bcompliance\b", r"\baudit\b", r"\bregulatory\b", r"\blgpd\b", r"\bgdpr\b",
    ]),
    ("therapy", [
        r"\btherapy\b", r"\btherap(?:ist|eutic)\b", r"\bcounsel(?:ing|ling)\b", r"\bterapia\b",
    ]),
    ("research", [
        r"\bresearch\b", r"\bstudy notes?\b", r"\bliterature\b", r"\bpesquisa\b",
    ]),
    ("customer-support", [
        r"\bcustomer support\b", r"\bsupport call\b", r"\bticket\b", r"\bsuporte\b",
        r"\bhelp ?desk\b",
    ]),
    ("product", [
        r"\bproduct (?:review|meeting|sync|roadmap)\b", r"\broadmap\b", r"\bprd\b",
        r"\buser stor(?:y|ies)\b",
    ]),
    ("bullets", [
        r"\bbullet(?:ed|s)?\b", r"\bt[oó]picos?\b", r"\bbullet list\b",
    ]),
    ("sections", [
        r"\bsections?\b", r"\bheadings?\b", r"\bse[cç][oõ]es\b", r"\boutline\b",
    ]),
    ("checklist", [
        r"\bchecklists?\b", r"\bcheck list\b", r"\b\[ \]\b", r"\btodo list\b",
    ]),
    ("email-draft", [
        r"\bemail\b", r"\be-?mail\b", r"\bdraft (?:a |an )?mail\b", r"\breply draft\b",
    ]),
    ("report", [
        r"\breports?\b", r"\brelat[oó]rios?\b", r"\bstatus report\b",
    ]),
    ("transcript", [
        r"\btranscripts?\b", r"\btranscription\b", r"\btranscri[cç][aã]o\b", r"\bverbatim\b",
    ]),
    ("minutes", [
        r"\bminutes\b", r"\bmeeting minutes\b", r"\bata\b", r"\bata de reuni",
    ]),
    ("summary", [
        r"\bsummar(?:y|ies|ize)\b", r"\bresum(?:o|ir)\b", r"\boverview\b",
    ]),
    ("qa", [
        r"\bq\s*&\s*a\b", r"\bquestions? and answers?\b", r"\bperguntas? e respostas?\b",
        r"\bfaq\b",
    ]),
]

# Compile once.
TAG_RULES: list[tuple[str, list[re.Pattern[str]]]] = [
    (slug, [re.compile(p, re.I) for p in pats])
    for slug, pats in _TAG_PATTERNS
    if slug in VOCAB_SLUGS
]

MIN_TAGS = 1
MAX_TAGS = 8


def assign_tags(
    name: str,
    description: str,
    body: str,
    category: str,
    *,
    max_tags: int = MAX_TAGS,
) -> list[str]:
    """Return sorted unique vocabulary slugs (1..max_tags) for a cleaned template row."""
    blob = f"{name}\n{description}\n{body}".casefold()
    matched: list[str] = []
    seen: set[str] = set()
    for slug, patterns in TAG_RULES:
        if slug in seen:
            continue
        if any(p.search(blob) for p in patterns):
            matched.append(slug)
            seen.add(slug)
            if len(matched) >= max_tags:
                break

    for slug in CATEGORY_DEFAULT_TAGS.get(category, ["summary"]):
        if slug not in VOCAB_SLUGS:
            continue
        if slug not in seen:
            matched.append(slug)
            seen.add(slug)
        if len(matched) >= max_tags:
            break

    if not matched:
        matched = ["summary"] if "summary" in VOCAB_SLUGS else [next(iter(VOCAB_SLUGS))]

    # Prefer stable alpha order for deterministic JSONL / diffs.
    out = sorted(set(matched))[:max_tags]
    if len(out) < MIN_TAGS:
        out = sorted(set(out) | {"summary"})[:max_tags]
    return out


def normalize_tags(tags: Iterable[str]) -> list[str]:
    """Filter unknown slugs, unique, sorted."""
    return sorted({t.strip().lower() for t in tags if t and t.strip().lower() in VOCAB_SLUGS})


def vocabulary_json() -> list[dict[str, str]]:
    return [dict(t) for t in VOCABULARY]
