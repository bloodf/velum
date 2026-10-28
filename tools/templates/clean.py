#!/usr/bin/env python3
"""
Full data-hygiene pass over the collected template dump.

Writes cleaned per-category JSONL under --out-dir and a Markdown report to --report.
Exact duplicate bodies (whitespace/CRLF-normalized; case-sensitive; see body_dedupe.py)
collapse to one deterministic survivor.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from body_dedupe import dedupe_by_exact_body
from tags import VOCABULARY, assign_tags

CATEGORY_MAP = {
    "cat_general": "general",
    "cat_meeting": "meeting",
    "cat_call": "call",
    "cat_sales": "sales",
    "cat_interview": "interview",
    "cat_consulting": "consulting",
    "cat_education": "education",
    "cat_speech": "speech",
    "cat_medical": "medical",
    "cat_legal": "legal",
    "cat_financial": "financial",
    "cat_real_estate": "estate",
    "cat_construction": "construction",
    "cat_it_engineering": "it",
    "cat_functional": "extractive",
}

CATEGORY_ORDER = list(CATEGORY_MAP.values())

# Canonical dump categoryId per Velum category key (matches PlaudTemplateCatalog.mapCategoryKey).
CATEGORY_IDS = {
    "general": "cat_general",
    "meeting": "cat_meeting",
    "call": "cat_call",
    "sales": "cat_sales",
    "interview": "cat_interview",
    "consulting": "cat_consulting",
    "education": "cat_education",
    "speech": "cat_speech",
    "medical": "cat_medical",
    "legal": "cat_legal",
    "financial": "cat_financial",
    "estate": "cat_real_estate",
    "construction": "cat_construction",
    "it": "cat_it_engineering",
    "extractive": "cat_functional",
}

CATEGORY_ALIASES = {
    "general": "general",
    "meeting": "meeting",
    "call": "call",
    "sales": "sales",
    "interview": "interview",
    "consulting": "consulting",
    "education": "education",
    "speech": "speech",
    "medical": "medical",
    "legal": "legal",
    "financial": "financial",
    "real estate": "estate",
    "realestate": "estate",
    "real_estate": "estate",
    "estate": "estate",
    "construction": "construction",
    "it": "it",
    "it & engineering": "it",
    "it and engineering": "it",
    "it_engineering": "it",
    "functional": "extractive",
    "extractive": "extractive",
}

VALID_BODY_TYPES = {"prompt", "outline"}

# --- Patterns -----------------------------------------------------------------

RE_EMAIL = re.compile(r"\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b", re.I)
RE_PHONE = re.compile(
    r"(?<!\w)(?:\+?\d{1,3}[\s\-.]*)?(?:\(?\d{2,4}\)?[\s\-.]*)?\d{3,4}[\s\-.]?\d{3,4}(?!\w)"
)
RE_URL = re.compile(
    r"(?i)\b(?:https?://|www\.)[^\s<>\]\"']+|\b[a-z0-9\-]+\.(?:com|net|org|io|co|app|ai|dev|me|xyz|info)(?:/[^\s]*)?"
)
RE_MD_LINK = re.compile(r"\[([^\]]*)\]\(([^)]+)\)")
RE_PLAUID = re.compile(
    r"(?i)\bplaud(?:\s+(?:note(?:\s*pro)?|app|ai|inc\.?|official|community|template(?:s)?))?\b"
)
RE_PLAUID_UI = re.compile(
    r"(?i)\b(?:plaud\s+(?:home|library|files|ask\s*ai|autoflow|generate|template\s*community))\b"
)
RE_SPAM = re.compile(
    r"(?i)\b("
    r"buy now|limited offer|subscribe|click here|promo code|discount code|"
    r"only \$|free trial|sign up today|make money|crypto pump|seo services|"
    r"follow me on|instagram\.com|tiktok\.com|onlyfans|telegram\.me|"
    r"whatsapp\.me|bit\.ly|tinyurl|affiliate"
    r")\b"
)
RE_PAID = re.compile(
    r"(?i)\b("
    r"chatgpt\s*plus|openai\s*api\s*key|claude\s*pro|gemini\s*advanced|"
    r"notion\s*ai\s*paid|zapier\s*pro|make\.com\s*paid|n8n\s*cloud"
    r")\b"
)
RE_TEST = re.compile(
    r"(?i)^(test|asdf|xxx+|sample|demo|untitled|new template|template\s*\d*|foo|bar|tmp|placeholder|draft)$"
)
RE_GIBBERISH = re.compile(r"(.)\1{6,}")  # aaaaaaa
RE_PERSONAL_HANDLE = re.compile(r"(?i)(?:^|\s)@[a-z0-9_]{3,}\b")
RE_BYLINE = re.compile(
    r"(?im)^(?:by|author|created by|written by)\s*[:\-]?\s*.+$"
)

# Simple language word lists
EN_MARKERS = {
    "the", "and", "with", "for", "this", "that", "from", "meeting", "summary",
    "please", "should", "would", "action", "items", "notes",
}
PT_MARKERS = {
    "que", "para", "com", "uma", "não", "são", "reunião", "resumo", "ação",
    "próximos", "passos", "participantes", "decisões", "pauta",
}
ES_MARKERS = {
    "que", "para", "con", "una", "reunión", "resumen", "acción", "próximos",
    "pasos", "participantes", "decisiones",
}
DE_MARKERS = {
    "und", "der", "die", "das", "mit", "für", "nicht", "zusammenfassung", "besprechung",
}
FR_MARKERS = {
    "les", "des", "une", "pour", "avec", "réunion", "résumé", "actions", "prochaines",
}
ZH_RE = re.compile(r"[\u4e00-\u9fff]")
JA_RE = re.compile(r"[\u3040-\u30ff]")
KO_RE = re.compile(r"[\uac00-\ud7af]")
AR_RE = re.compile(r"[\u0600-\u06ff]")
CYR_RE = re.compile(r"[\u0400-\u04ff]")

MOJIBAKE_MARKERS = ("Ã", "Â", "â€™", "â€", "Ã©", "Ã£", "�")


@dataclass
class Stats:
    before_total: int = 0
    after_total: int = 0
    before_per_category: Counter = field(default_factory=Counter)
    after_per_category: Counter = field(default_factory=Counter)
    removed: Counter = field(default_factory=Counter)
    stripped_fields: Counter = field(default_factory=Counter)
    language_counts: Counter = field(default_factory=Counter)
    tag_counts: Counter = field(default_factory=Counter)
    exact_dupes_removed: int = 0
    duplicate_body_groups: int = 0


def nfkc(s: str) -> str:
    return unicodedata.normalize("NFKC", s or "")


def fix_mojibake(s: str) -> str | None:
    """Attempt latin1↔utf8 round-trip repair; return None if still garbled."""
    text = s
    if any(m in text for m in MOJIBAKE_MARKERS) or "�" in text:
        try:
            repaired = text.encode("latin-1", errors="strict").decode("utf-8")
            if repaired and "�" not in repaired:
                text = repaired
        except (UnicodeEncodeError, UnicodeDecodeError):
            pass
    if "�" in text or text.count("Ã") > 3:
        return None
    return text


def normalize_whitespace_md(s: str) -> str:
    s = nfkc(s)
    s = s.replace("\r\n", "\n").replace("\r", "\n")
    s = s.replace("\u00a0", " ").replace("\t", " ")
    # Collapse weird unicode spaces
    s = re.sub(r"[\u2000-\u200b\u202f\u205f\u3000]", " ", s)
    lines = []
    for line in s.split("\n"):
        line = line.rstrip()
        # Normalize bullet markers
        line = re.sub(r"^[\u2022\u2023\u25E6\u2043]\s*", "- ", line)
        line = re.sub(r"^\*\s+", "- ", line)
        # Normalize heading spacing
        line = re.sub(r"^(#{1,6})([^\s#])", r"\1 \2", line)
        # Collapse internal multi-spaces (keep leading indent ≤2 for lists)
        if line.lstrip().startswith(("- ", "* ", "#")):
            lead = len(line) - len(line.lstrip(" "))
            lead = min(lead, 2)
            rest = re.sub(r" {2,}", " ", line.lstrip(" "))
            line = (" " * lead) + rest
        else:
            line = re.sub(r" {2,}", " ", line.strip()) if line.strip() else ""
        lines.append(line)
    # Collapse 3+ blank lines
    out: list[str] = []
    blank = 0
    for line in lines:
        if line == "":
            blank += 1
            if blank <= 2:
                out.append(line)
        else:
            blank = 0
            out.append(line)
    return "\n".join(out).strip()


def map_category(category_id: str | None, category: str | None) -> str | None:
    cid = (category_id or "").strip().lower()
    if cid in CATEGORY_MAP:
        return CATEGORY_MAP[cid]
    name = (category or "").strip().lower()
    return CATEGORY_ALIASES.get(name)


def detect_language(text: str) -> str:
    if ZH_RE.search(text):
        return "zh"
    if JA_RE.search(text):
        return "ja"
    if KO_RE.search(text):
        return "ko"
    if AR_RE.search(text):
        return "ar"
    if CYR_RE.search(text):
        return "ru"
    tokens = re.findall(r"[a-zà-üãõáéíóúâêôç]+", text.casefold())
    if not tokens:
        return "und"
    def hit(markers: set[str]) -> int:
        return sum(1 for t in tokens if t in markers)
    scores = {
        "en": hit(EN_MARKERS),
        "pt": hit(PT_MARKERS),
        "es": hit(ES_MARKERS),
        "de": hit(DE_MARKERS),
        "fr": hit(FR_MARKERS),
    }
    lang, n = max(scores.items(), key=lambda kv: kv[1])
    if n == 0:
        return "und"
    # pt vs es disambiguation
    if lang in {"pt", "es"} and scores["pt"] == scores["es"]:
        if any(t in tokens for t in ("não", "são", "próximos", "reunião")):
            return "pt"
        if any(t in tokens for t in ("reunión", "también", "acción")):
            return "es"
    return lang


def strip_pii_and_branding(text: str, stats: Stats) -> str:
    original = text

    def _url_sub(_m: re.Match) -> str:
        stats.stripped_fields["url"] += 1
        return ""

    text = RE_MD_LINK.sub(lambda m: m.group(1) or "", text)
    text = RE_URL.sub(_url_sub, text)
    if RE_EMAIL.search(text):
        stats.stripped_fields["email"] += 1
        text = RE_EMAIL.sub("", text)
    # Phones: only strip when looking like contact lines to avoid killing section numbers
    def phone_sub(m: re.Match) -> str:
        raw = m.group(0)
        digits = re.sub(r"\D", "", raw)
        if len(digits) >= 10:
            stats.stripped_fields["phone"] += 1
            return ""
        return raw

    text = RE_PHONE.sub(phone_sub, text)
    if RE_PERSONAL_HANDLE.search(text):
        stats.stripped_fields["handle"] += 1
        text = RE_PERSONAL_HANDLE.sub(" ", text)
    if RE_BYLINE.search(text):
        stats.stripped_fields["byline"] += 1
        text = RE_BYLINE.sub("", text)
    if RE_PLAUID.search(text) or RE_PLAUID_UI.search(text):
        stats.stripped_fields["plaud_brand"] += 1
        text = RE_PLAUID_UI.sub("", text)
        text = RE_PLAUID.sub("Velum", text)
    # Soften leftover product phrases
    text = re.sub(r"(?i)\bnote\s*pro\b", "recorder", text)
    text = normalize_whitespace_md(text)
    if text != original:
        stats.stripped_fields["normalized"] += 1
    return text


def is_gibberish(text: str) -> bool:
    if RE_GIBBERISH.search(text):
        return True
    letters = re.findall(r"[A-Za-zÀ-ü]", text)
    if len(letters) >= 40:
        # High consonant-only ratio without spaces → garbage
        words = re.findall(r"[A-Za-zÀ-ü]{6,}", text)
        weird = sum(1 for w in words if not re.search(r"[aeiouyà-ü]", w, re.I))
        if weird >= 4 and weird >= len(words) * 0.6:
            return True
    return False


def parse_bool(v) -> bool:
    if isinstance(v, bool):
        return v
    return str(v).strip().lower() in {"1", "true", "yes", "y"}


def exact_key(name: str, body: str) -> str:
    return norm_key(name) + "\n" + norm_key(body)


def norm_key(s: str) -> str:
    s = nfkc(s).casefold()
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"[^\w\s]+", "", s, flags=re.UNICODE)
    return s.strip()


def stable_id(category: str, name: str, body: str, preferred: str | None) -> str:
    if preferred:
        p = preferred.strip()
        if p and p.lower() not in {"none", "null"}:
            slug = re.sub(r"[^a-zA-Z0-9._-]+", "-", p).strip("-").lower()
            if not slug.startswith("plaud."):
                slug = "plaud." + slug
            return slug[:120]
    digest = hashlib.sha1(exact_key(name, body).encode("utf-8")).hexdigest()[:12]
    slug = re.sub(r"[^a-z0-9]+", "-", norm_key(name))[:48].strip("-") or "template"
    return f"plaud.{category}.{slug}.{digest}"


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    if not path.exists():
        return rows
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return rows


def reject_reason(name: str, body: str, description: str, body_type: str | None) -> str | None:
    if not name or not body:
        return "empty"
    if len(body) < 40:
        return "too_short"
    if RE_TEST.match(name.strip()):
        return "test_placeholder"
    if body_type is None:
        return "invalid_body_type"
    if RE_SPAM.search(name) or RE_SPAM.search(body) or RE_SPAM.search(description):
        return "spam_promo"
    if RE_PAID.search(body) or RE_PAID.search(description):
        return "paid_service_promo"
    if is_gibberish(name) or is_gibberish(body):
        return "gibberish"
    # Non-functional: body is only a single short sentence with no structure and no instructions
    if body.count("\n") == 0 and len(body) < 90 and not re.search(
        r"(?i)\b(summar|extract|list|include|write|section|heading|bullet)\b", body
    ):
        return "non_functional"
    return None


def clean_row(raw: dict, stats: Stats) -> dict | None:
    stats.before_total += 1
    cat = map_category(raw.get("categoryId"), raw.get("category"))
    if cat is None:
        stats.removed["bad_category"] += 1
        return None
    stats.before_per_category[cat] += 1

    name = raw.get("name")
    body = raw.get("body")
    description = raw.get("description") or ""
    if not isinstance(name, str) or not isinstance(body, str):
        stats.removed["empty"] += 1
        return None

    name_f = fix_mojibake(name)
    body_f = fix_mojibake(body)
    desc_f = fix_mojibake(description)
    if name_f is None or body_f is None or desc_f is None:
        stats.removed["broken_encoding"] += 1
        return None

    name_n = normalize_whitespace_md(name_f).replace("\n", " ").strip()
    body_n = normalize_whitespace_md(body_f)
    desc_n = normalize_whitespace_md(desc_f).replace("\n", " ").strip()

    name_n = strip_pii_and_branding(name_n, stats).replace("\n", " ").strip()
    body_n = strip_pii_and_branding(body_n, stats)
    desc_n = strip_pii_and_branding(desc_n, stats).replace("\n", " ").strip()

    # After stripping, re-check emptiness
    if not name_n or not body_n or len(body_n) < 40:
        stats.removed["empty_after_strip"] += 1
        return None

    bt_raw = str(raw.get("bodyType") or "").strip().lower()
    body_type = bt_raw if bt_raw in VALID_BODY_TYPES else None
    if body_type is None:
        body_type = "prompt" if re.search(r"(?m)^(#{1,6} |\- )", body_n) else "outline"

    reason = reject_reason(name_n, body_n, desc_n, body_type)
    if reason:
        stats.removed[reason] += 1
        return None

    # Residual PII hard-fail (should already be stripped; if still present, drop)
    if RE_EMAIL.search(body_n) or RE_EMAIL.search(name_n) or RE_EMAIL.search(desc_n):
        stats.removed["pii_email"] += 1
        return None
    if RE_URL.search(body_n) or RE_URL.search(desc_n):
        stats.removed["pii_url"] += 1
        return None
    if RE_PLAUID.search(body_n) or RE_PLAUID.search(name_n) or RE_PLAUID.search(desc_n):
        # Second pass replacement failed somehow
        stats.removed["plaud_brand_residual"] += 1
        return None

    lang = detect_language(f"{name_n}\n{desc_n}\n{body_n}")
    stats.language_counts[lang] += 1

    tags = assign_tags(name_n, desc_n, body_n, cat)
    for tag in tags:
        stats.tag_counts[tag] += 1

    preferred = raw.get("id") or raw.get("templateId") or raw.get("sourceTemplateId")
    tid = stable_id(cat, name_n, body_n, str(preferred) if preferred else None)
    official = parse_bool(raw.get("official"))
    # Never ship dump author/brand; templates read as generic Velum catalog entries.
    uses = int(raw.get("usageCount") or raw.get("uses") or 0)

    return {
        "id": tid,
        "categoryId": CATEGORY_IDS[cat],
        "category": cat,
        "name": name_n,
        "author": "Velum",
        "official": official,
        "usageCount": uses,
        "description": desc_n,
        "bodyType": body_type,
        "body": body_n,
        "lang": lang,
        "tags": tags,
        "source": "Velum template catalog",
        "attribution": "Community template (cleaned for Velum)",
        "sourceTemplateId": str(preferred).strip()
        if preferred and str(preferred).strip().lower() not in {"none", "null"}
        else None,
    }


def dedupe_exact(rows: list[dict], stats: Stats) -> list[dict]:
    """Collapse exact duplicate bodies (see body_dedupe.py); keep one survivor."""
    result = dedupe_by_exact_body(rows)
    stats.exact_dupes_removed = result.removed_rows
    stats.duplicate_body_groups = result.duplicate_body_groups
    if result.removed_rows:
        stats.removed["exact_duplicate_body"] = result.removed_rows
    return result.survivors


def write_outputs(out_dir: Path, rows: list[dict]) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    by_cat: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_cat[row["category"]].append(row)
    # Also write a combined file
    combined = out_dir / "all.jsonl"
    with combined.open("w", encoding="utf-8") as all_f:
        for cat in CATEGORY_ORDER:
            cat_rows = sorted(by_cat.get(cat, []), key=lambda r: (r["name"].casefold(), r["id"]))
            path = out_dir / f"cat_{cat}.jsonl"
            with path.open("w", encoding="utf-8") as f:
                for row in cat_rows:
                    line = json.dumps(row, ensure_ascii=False, separators=(",", ":"))
                    f.write(line + "\n")
                    all_f.write(line + "\n")


def write_report(path: Path, stats: Stats) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    lines.append("# Template hygiene report")
    lines.append("")
    lines.append("Generated by `tools/templates/clean.py`.")
    lines.append("")
    lines.append("## Totals")
    lines.append("")
    lines.append(f"- Input rows considered: **{stats.before_total}**")
    lines.append(f"- Duplicate body groups: **{stats.duplicate_body_groups}**")
    lines.append(f"- Removed rows (exact duplicate bodies): **{stats.exact_dupes_removed}**")
    lines.append(f"- Output rows: **{stats.after_total}**")
    lines.append("")
    lines.append(
        "Exact duplicate = same `body` after CRLF→LF + per-line rstrip + strip "
        "(case preserved; `tools/templates/body_dedupe.py`)."
    )
    lines.append("")
    lines.append("## Removals by reason")
    lines.append("")
    lines.append("| Reason | Count |")
    lines.append("|---|---:|")
    for reason, n in sorted(stats.removed.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"| `{reason}` | {n} |")
    lines.append("")
    lines.append("## Field scrubbing")
    lines.append("")
    lines.append("| Kind | Hits |")
    lines.append("|---|---:|")
    for kind, n in sorted(stats.stripped_fields.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"| `{kind}` | {n} |")
    lines.append("")
    lines.append("## Per-category before → after")
    lines.append("")
    lines.append("| Category | Before | After |")
    lines.append("|---|---:|---:|")
    for cat in CATEGORY_ORDER:
        lines.append(
            f"| `{cat}` | {stats.before_per_category.get(cat, 0)} | {stats.after_per_category.get(cat, 0)} |"
        )
    lines.append("")
    lines.append("## Detected languages")
    lines.append("")
    lines.append("| Lang | Count |")
    lines.append("|---|---:|")
    for lang, n in sorted(stats.language_counts.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"| `{lang}` | {n} |")
    lines.append("")
    lines.append(f"## Tags (vocabulary size: {len(VOCABULARY)})")
    lines.append("")
    lines.append("| Tag | Count |")
    lines.append("|---|---:|")
    for tag, n in sorted(stats.tag_counts.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"| `{tag}` | {n} |")
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", type=Path, required=True, help="Directory with cat_*.jsonl")
    p.add_argument("--existing", type=Path, default=None, help="Optional existing JSONL to merge")
    p.add_argument("--out-dir", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    args = p.parse_args(argv)

    stats = Stats()
    cleaned: list[dict] = []

    paths = sorted(args.input.glob("cat_*.jsonl"))
    if not paths:
        print(f"No cat_*.jsonl under {args.input}", file=sys.stderr)
        return 1

    for path in paths:
        for raw in load_jsonl(path):
            row = clean_row(raw, stats)
            if row:
                cleaned.append(row)

    if args.existing and args.existing.exists():
        for raw in load_jsonl(args.existing):
            row = clean_row(raw, stats)
            if row:
                cleaned.append(row)

    cleaned = dedupe_exact(cleaned, stats)

    # Ensure unique ids
    used: set[str] = set()
    for row in cleaned:
        base = row["id"]
        if base not in used:
            used.add(base)
            continue
        digest = hashlib.sha1(exact_key(row["name"], row["body"]).encode()).hexdigest()[:10]
        row["id"] = f"{base}-{digest}"
        used.add(row["id"])

    for row in cleaned:
        stats.after_per_category[row["category"]] += 1
    stats.after_total = len(cleaned)

    write_outputs(args.out_dir, cleaned)
    write_report(args.report, stats)
    print(
        json.dumps(
            {
                "input_rows": stats.before_total,
                "duplicate_body_groups": stats.duplicate_body_groups,
                "removed_rows": stats.exact_dupes_removed,
                "output_rows": stats.after_total,
                "removed": dict(stats.removed),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
