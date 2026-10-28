#!/usr/bin/env python3
"""
Select Featured (≥100 / category) from cleaned JSONL and emit Featured + Full bundles.

Input must already be hygiene-cleaned (tools/templates/clean.py). Exact duplicates are
assumed gone; this step only soft-collapses near-dupes for Featured selection.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

CATEGORY_ORDER = [
    "general",
    "meeting",
    "call",
    "sales",
    "interview",
    "consulting",
    "education",
    "speech",
    "medical",
    "legal",
    "financial",
    "estate",
    "construction",
    "it",
    "extractive",
]


def nfkc(s: str) -> str:
    return unicodedata.normalize("NFKC", s or "")


def norm_key(s: str) -> str:
    s = nfkc(s).casefold()
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"[^\w\s]+", "", s, flags=re.UNICODE)
    return s.strip()


def soft_key(name: str, body: str) -> str:
    return norm_key(name) + "|" + norm_key(body)[:480]


def load_cleaned(cleaned_dir: Path) -> list[dict]:
    combined = cleaned_dir / "all.jsonl"
    paths = [combined] if combined.exists() else sorted(cleaned_dir.glob("cat_*.jsonl"))
    rows: list[dict] = []
    for path in paths:
        with path.open(encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                rows.append(json.loads(line))
    return rows


def quality_score(row: dict) -> float:
    score = 0.0
    if row.get("official"):
        score += 1000.0
    body = row.get("body") or ""
    desc = row.get("description") or ""
    if row.get("bodyType") in {"prompt", "outline"}:
        score += 50.0
    score += min(len(desc) / 8.0, 40.0)
    score += min(len(body) / 40.0, 60.0)
    if "\n" in body:
        score += 25.0
    if re.search(r"(?m)^(#+ |\- |\* |\d+\. )", body):
        score += 30.0
    generic = {
        "meeting",
        "meeting minutes",
        "meeting summary",
        "meeting notes",
        "interview",
        "report",
    }
    if norm_key(row.get("name") or "") in generic:
        score -= 15.0
    # Prefer English for Featured when scores tie-ish (primary UI locales EN/pt-BR).
    lang = (row.get("lang") or "und").lower()
    if lang in {"en", "pt"}:
        score += 15.0
    elif lang == "und":
        score -= 5.0
    score += float(row.get("usageCount") or 0) * 0.01
    return score


def select_featured(rows: list[dict], min_per_cat: int) -> list[dict]:
    soft_best: dict[str, dict] = {}
    for row in rows:
        key = row["category"] + "::" + soft_key(row["name"], row["body"])
        prev = soft_best.get(key)
        if prev is None or quality_score(row) > quality_score(prev):
            soft_best[key] = row

    by_cat: dict[str, list[dict]] = defaultdict(list)
    for row in soft_best.values():
        by_cat[row["category"]].append(row)

    featured: list[dict] = []
    for cat in CATEGORY_ORDER:
        group = by_cat.get(cat, [])
        group.sort(key=quality_score, reverse=True)
        officials = [r for r in group if r.get("official")]
        rest = [r for r in group if not r.get("official")]
        chosen: list[dict] = []
        seen: set[str] = set()
        for r in officials + rest:
            if r["id"] in seen:
                continue
            chosen.append(r)
            seen.add(r["id"])
            if len(chosen) >= min_per_cat:
                for o in officials:
                    if o["id"] not in seen:
                        chosen.append(o)
                        seen.add(o["id"])
                break
        featured.extend(chosen)
    return featured


def to_output(row: dict, *, featured: bool) -> dict:
    tags = row.get("tags") or []
    if not isinstance(tags, list):
        tags = []
    tags = sorted({str(t).strip() for t in tags if str(t).strip()})
    out = {
        "id": row["id"],
        "categoryId": row.get("categoryId") or f"cat_{row['category']}",
        "category": row["category"],
        "name": row["name"],
        "author": row.get("author") or "Velum",
        "official": bool(row.get("official")),
        "featured": featured,
        "usageCount": int(row.get("usageCount") or 0),
        "description": row.get("description") or "",
        "bodyType": row["bodyType"],
        "body": row["body"],
        "lang": row.get("lang") or "und",
        "tags": tags,
        "source": row.get("source") or "Velum template catalog",
        "attribution": row.get("attribution") or "Community template (cleaned for Velum)",
    }
    if row.get("sourceTemplateId"):
        out["sourceTemplateId"] = row["sourceTemplateId"]
    return out


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            f.write("\n")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--cleaned", type=Path, required=True)
    p.add_argument("--out-featured", type=Path, required=True)
    p.add_argument("--out-full", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--min-per-category", type=int, default=100)
    args = p.parse_args(argv)

    rows = load_cleaned(args.cleaned)
    if not rows:
        print(f"No cleaned rows under {args.cleaned}", file=sys.stderr)
        return 1

    # Ensure unique ids across full set
    used: set[str] = set()
    for row in rows:
        base = row["id"]
        if base not in used:
            used.add(base)
            continue
        digest = hashlib.sha1(
            (norm_key(row["name"]) + "\n" + norm_key(row["body"])).encode()
        ).hexdigest()[:10]
        row["id"] = f"{base}-{digest}"
        used.add(row["id"])

    featured_rows = select_featured(rows, args.min_per_category)
    featured_ids = {r["id"] for r in featured_rows}

    def sort_key(r: dict) -> tuple:
        cat = r["category"]
        idx = CATEGORY_ORDER.index(cat) if cat in CATEGORY_ORDER else 99
        return (idx, -quality_score(r), r["id"])

    featured_out = [to_output(r, featured=True) for r in sorted(featured_rows, key=sort_key)]
    full_out = [
        to_output(r, featured=(r["id"] in featured_ids)) for r in sorted(rows, key=sort_key)
    ]

    write_jsonl(args.out_featured, featured_out)
    write_jsonl(args.out_full, full_out)

    def counts(items: list[dict]) -> dict[str, int]:
        c = {k: 0 for k in CATEGORY_ORDER}
        for r in items:
            c[r["category"]] = c.get(r["category"], 0) + 1
        return c

    featured_counts = counts(featured_out)
    full_counts = counts(full_out)
    shortfalls = {k: v for k, v in featured_counts.items() if v < args.min_per_category}

    report = {
        "featured_total": len(featured_out),
        "featured_per_category": featured_counts,
        "full_total": len(full_out),
        "full_per_category": full_counts,
        "min_per_category": args.min_per_category,
        "featured_shortfalls": shortfalls,
        "official_in_featured": sum(1 for r in featured_out if r.get("official")),
        "official_in_full": sum(1 for r in full_out if r.get("official")),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if shortfalls:
        print(f"WARNING: categories below {args.min_per_category}: {shortfalls}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
