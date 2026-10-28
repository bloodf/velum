#!/usr/bin/env python3
"""
Exact body deduplication for Velum template JSONL catalogs (#99).

Exact duplicate definition
-------------------------
Two rows are exact duplicates when their ``body`` field strings are equal
with **no** normalization: no whitespace trim, no line-ending rewrite, no
case folding, and no Unicode NFKC. Bodies that differ only in trailing
spaces or letter case are **not** duplicates.

Survivor selection (deterministic, lowest key wins)
---------------------------------------------------
1. Official rows first (``official=true``).
2. Then existing Featured rows (``featured=true``).
3. Then lexicographic ``name`` (Unicode code-point order).
4. Then canonical JSON serialization of the row (sorted keys, compact
   separators, ``ensure_ascii=False``) as the final tie-breaker.

When collapsing a group, the survivor keeps its identity/fields except:
- ``tags`` become the sorted union of all group tags
- ``usageCount`` becomes the max across the group
- ``official`` / ``featured`` become true if any row in the group had them

Output ordering is stable: category catalog order, then name casefold, then id.
Shuffling the input therefore yields identical survivors and identical order.
Running the pass twice is a no-op.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

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


def canonical_row(row: dict[str, Any]) -> str:
    """Stable serialization used only as a survivor tie-breaker."""
    return json.dumps(row, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def survivor_sort_key(row: dict[str, Any]) -> tuple:
    """Lower is better — official → featured → name → canonical JSON."""
    return (
        0 if row.get("official") else 1,
        0 if row.get("featured") else 1,
        str(row.get("name") or ""),
        canonical_row(row),
    )


def output_sort_key(row: dict[str, Any]) -> tuple:
    cat = str(row.get("category") or "")
    idx = CATEGORY_ORDER.index(cat) if cat in CATEGORY_ORDER else 99
    return (idx, str(row.get("name") or "").casefold(), str(row.get("id") or ""))


def _as_tag_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return sorted({str(t).strip() for t in value if str(t).strip()})


def merge_into_survivor(survivor: dict[str, Any], group: list[dict[str, Any]]) -> dict[str, Any]:
    """Return a copy of survivor with tags/flags/usage reconciled from the group."""
    out = dict(survivor)
    tags: set[str] = set()
    max_uses = 0
    any_official = False
    any_featured = False
    for row in group:
        tags.update(_as_tag_list(row.get("tags")))
        max_uses = max(max_uses, int(row.get("usageCount") or row.get("uses") or 0))
        any_official = any_official or bool(row.get("official"))
        any_featured = any_featured or bool(row.get("featured"))
    out["tags"] = sorted(tags)
    out["usageCount"] = max_uses
    out["official"] = any_official
    if "featured" in survivor or any(isinstance(r.get("featured"), bool) for r in group):
        out["featured"] = any_featured
    return out


@dataclass
class BodyDedupeResult:
    survivors: list[dict[str, Any]]
    remap: dict[str, str] = field(default_factory=dict)
    input_rows: int = 0
    duplicate_body_groups: int = 0
    removed_rows: int = 0
    output_rows: int = 0
    before_per_category: Counter = field(default_factory=Counter)
    after_per_category: Counter = field(default_factory=Counter)
    removed_ids: list[str] = field(default_factory=list)


def dedupe_by_exact_body(rows: Iterable[dict[str, Any]]) -> BodyDedupeResult:
    """
    Collapse rows that share an exact ``body`` string.

    Rows without a string body are kept as-is (treated as unique empty keys only
    when body is missing — callers should not pass empty bodies from hygiene).
    """
    materialised = [dict(r) for r in rows]
    before_per = Counter(str(r.get("category") or "") for r in materialised)

    by_body: dict[str, list[dict[str, Any]]] = defaultdict(list)
    no_body: list[dict[str, Any]] = []
    for row in materialised:
        body = row.get("body")
        if not isinstance(body, str):
            no_body.append(row)
            continue
        by_body[body].append(row)

    survivors: list[dict[str, Any]] = list(no_body)
    remap: dict[str, str] = {}
    removed_ids: list[str] = []
    dup_groups = 0
    removed = 0

    # Process groups in stable order by first-seen survivor key of the winner,
    # but final list is re-sorted via output_sort_key.
    for body, group in by_body.items():
        if len(group) == 1:
            survivors.append(dict(group[0]))
            continue
        dup_groups += 1
        removed += len(group) - 1
        winner = min(group, key=survivor_sort_key)
        merged = merge_into_survivor(winner, group)
        survivors.append(merged)
        win_id = str(merged.get("id") or "")
        for row in group:
            rid = str(row.get("id") or "")
            if rid and rid != win_id:
                remap[rid] = win_id
                removed_ids.append(rid)

    survivors.sort(key=output_sort_key)
    after_per = Counter(str(r.get("category") or "") for r in survivors)
    return BodyDedupeResult(
        survivors=survivors,
        remap=dict(sorted(remap.items())),
        input_rows=len(materialised),
        duplicate_body_groups=dup_groups,
        removed_rows=removed,
        output_rows=len(survivors),
        before_per_category=before_per,
        after_per_category=after_per,
        removed_ids=sorted(removed_ids),
    )


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            rows.append(json.loads(line))
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            f.write("\n")


def write_kotlin_remap(path: Path, remap: dict[str, str]) -> None:
    entries = "\n".join(f'        "{o}" to "{n}",' for o, n in sorted(remap.items()))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "package dev.velum.core.templates\n"
        "\n"
        "/**\n"
        " * Removed exact-duplicate body ids → retained survivor ids (#99).\n"
        " *\n"
        " * Mirrors `docs/templates/body_dedupe_remap.json` /\n"
        " * classpath `template_body_dedupe_remap.json` after\n"
        " * `tools/templates/body_dedupe.py`. Used to reconcile existing installs that\n"
        " * already imported the pre-dedupe full catalog: remap AutoFlow / summary-version\n"
        " * references, then delete the dropped builtin rows. User-created templates\n"
        " * (`builtin = 0`) are never deleted.\n"
        " */\n"
        "object TemplateBodyDedupeRemap {\n"
        '    const val META_KEY: String = "template_body_dedupe_v1"\n'
        "\n"
        "    val OldToSurvivor: Map<String, String> = mapOf(\n"
        f"{entries}\n"
        "    )\n"
        "}\n",
        encoding="utf-8",
    )


def write_report(path: Path, result: BodyDedupeResult) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Exact body dedupe report",
        "",
        "Generated by `tools/templates/body_dedupe.py` (#99).",
        "",
        "## Exact duplicate definition",
        "",
        "Rows share an exact `body` string with **no** whitespace, line-ending,",
        "case, or Unicode normalization. Differing only in trailing spaces or",
        "letter case does **not** count as a duplicate.",
        "",
        "## Totals",
        "",
        f"- Input rows: **{result.input_rows}**",
        f"- Duplicate body groups: **{result.duplicate_body_groups}**",
        f"- Removed rows: **{result.removed_rows}**",
        f"- Output rows: **{result.output_rows}**",
        "",
        "## Per-category before → after",
        "",
        "| Category | Before | After |",
        "|---|---:|---:|",
    ]
    cats = list(CATEGORY_ORDER)
    for cat in sorted(set(cats) | set(result.before_per_category) | set(result.after_per_category)):
        if cat in cats:
            continue
        cats.append(cat)
    for cat in cats:
        lines.append(
            f"| `{cat}` | {result.before_per_category.get(cat, 0)} | "
            f"{result.after_per_category.get(cat, 0)} |"
        )
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", type=Path, required=True, help="Input JSONL (full catalog)")
    p.add_argument("--output", type=Path, required=True, help="Deduped JSONL output")
    p.add_argument("--report", type=Path, required=True, help="Markdown report path")
    p.add_argument(
        "--remap",
        type=Path,
        default=None,
        help="Optional JSON map of removed id → survivor id",
    )
    p.add_argument(
        "--kotlin-remap",
        type=Path,
        default=None,
        help="Optional Kotlin object path (TemplateBodyDedupeRemap.kt)",
    )
    args = p.parse_args(argv)

    rows = load_jsonl(args.input)
    result = dedupe_by_exact_body(rows)
    write_jsonl(args.output, result.survivors)
    write_report(args.report, result)
    if args.remap is not None:
        args.remap.parent.mkdir(parents=True, exist_ok=True)
        args.remap.write_text(
            json.dumps(result.remap, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
    if args.kotlin_remap is not None:
        write_kotlin_remap(args.kotlin_remap, result.remap)

    summary = {
        "input_rows": result.input_rows,
        "duplicate_body_groups": result.duplicate_body_groups,
        "removed_rows": result.removed_rows,
        "output_rows": result.output_rows,
        "before_per_category": dict(result.before_per_category),
        "after_per_category": dict(result.after_per_category),
        "remap_entries": len(result.remap),
    }
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
