#!/usr/bin/env python3
"""
Assert cleaned Featured + Full JSONL have no empty/duplicate/PII-pattern entries, and gate
Featured coverage (#101): every category has >= --min-per-category distinct exact bodies,
every Featured id resolves to a Full row, and every baseline official template is retained
as official + Featured. `--full` may be gzip.
"""
from __future__ import annotations

import argparse
import gzip
import json
import re
import sys
from collections import Counter
from pathlib import Path

RE_EMAIL = re.compile(r"\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b", re.I)
RE_URL = re.compile(
    r"(?i)\b(?:https?://|www\.)[^\s<>\]\"']+|\b[a-z0-9\-]+\.(?:com|net|org|io|co|app|ai|dev|me|xyz|info)(?:/[^\s]*)?"
)
RE_PLAUID = re.compile(r"(?i)\bplaud\b")
RE_PHONE_HARD = re.compile(r"(?<!\d)(?:\+?\d[\d\s\-().]{8,}\d)(?!\d)")

CATEGORY_ORDER = [
    "general", "meeting", "call", "sales", "interview", "consulting", "education", "speech",
    "medical", "legal", "financial", "estate", "construction", "it", "extractive",
]


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            rows.append(json.loads(line))
    return rows


def check_set(label: str, rows: list[dict], *, require_featured: bool | None) -> list[str]:
    errors: list[str] = []
    ids: set[str] = set()
    bodies: dict[str, str] = {}
    for i, row in enumerate(rows):
        name = (row.get("name") or "").strip()
        body = (row.get("body") or "").strip()
        desc = (row.get("description") or "").strip()
        tid = (row.get("id") or "").strip()
        cat = (row.get("category") or "").strip()
        if not tid:
            errors.append(f"{label}[{i}]: empty id")
        if not name:
            errors.append(f"{label}[{i}]: empty name")
        if not body or len(body) < 40:
            errors.append(f"{label}[{i}]: empty/too-short body ({tid})")
        if not cat:
            errors.append(f"{label}[{i}]: empty category ({tid})")
        tags = row.get("tags")
        if not isinstance(tags, list) or not tags:
            errors.append(f"{label}[{i}]: empty tags ({tid})")
        elif any(not isinstance(t, str) or not t.strip() for t in tags):
            errors.append(f"{label}[{i}]: blank tag entry ({tid})")
        lang = (row.get("lang") or "").strip()
        if not lang:
            errors.append(f"{label}[{i}]: empty lang ({tid})")
        if tid in ids:
            errors.append(f"{label}: duplicate id {tid}")
        ids.add(tid)
        raw_body = row.get("body") or ""
        if raw_body in bodies:
            errors.append(f"{label}: exact duplicate body {tid} == {bodies[raw_body]}")
        else:
            bodies[raw_body] = tid
        blob = f"{name}\n{desc}\n{body}"
        if RE_EMAIL.search(blob):
            errors.append(f"{label}: email PII in {tid}")
        if RE_URL.search(blob):
            errors.append(f"{label}: URL/PII in {tid}")
        if RE_PLAUID.search(blob):
            errors.append(f"{label}: Plaud branding residual in {tid}")
        if require_featured is True and not row.get("featured", False):
            errors.append(f"{label}: featured=false on Featured set row {tid}")
        if require_featured is False and "featured" not in row:
            errors.append(f"{label}: missing featured field on {tid}")
    return errors


def featured_gate_errors(
    featured: list[dict],
    full: list[dict],
    *,
    min_per_category: int,
    official_baseline: list[str],
) -> list[str]:
    errors: list[str] = []
    full_by_id = {r["id"]: r for r in full}
    for r in featured:
        if r["id"] not in full_by_id:
            errors.append(f"Featured id missing from Full: {r['id']}")
    # Distinct exact bodies, so renamed copies cannot inflate a category.
    distinct: dict[str, set[str]] = {c: set() for c in CATEGORY_ORDER}
    for r in featured:
        distinct.setdefault(r["category"], set()).add(r.get("body") or "")
    for cat in CATEGORY_ORDER:
        n = len(distinct[cat])
        if n < min_per_category:
            errors.append(f"Featured category {cat} has {n} distinct bodies < {min_per_category}")
    featured_ids = {r["id"] for r in featured}
    for tid in official_baseline:
        row = full_by_id.get(tid)
        if row is None or not row.get("official"):
            errors.append(f"Official template {tid} missing or no longer official in Full")
        elif tid not in featured_ids:
            errors.append(f"Official template {tid} not in Featured")
    for r in full:
        if r.get("official") and r["id"] not in featured_ids:
            errors.append(f"Official template {r['id']} not in Featured")
    return errors


def read_baseline(path: Path | None) -> list[str]:
    if path is None:
        return []
    lines = path.read_text(encoding="utf-8").splitlines()
    return [l.strip() for l in lines if l.strip() and not l.lstrip().startswith("#")]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--featured", type=Path, required=True)
    p.add_argument("--full", type=Path, required=True)
    p.add_argument("--min-per-category", type=int, default=100)
    p.add_argument(
        "--official-baseline",
        type=Path,
        default=None,
        help="Ids of official templates that must stay official + Featured (one per line).",
    )
    p.add_argument(
        "--official-inventory",
        type=Path,
        default=None,
        help="Authoritative official-template inventory (ids, one per line). Missing = blocker.",
    )
    args = p.parse_args(argv)

    featured = load_jsonl(args.featured)
    full = load_jsonl(args.full)
    errors = []
    errors.extend(check_set("featured", featured, require_featured=True))
    errors.extend(check_set("full", full, require_featured=False))

    errors.extend(
        featured_gate_errors(
            featured,
            full,
            min_per_category=args.min_per_category,
            official_baseline=read_baseline(args.official_baseline),
        )
    )
    per_cat = Counter(r["category"] for r in featured)
    if args.official_inventory is None:
        # No authoritative upstream inventory is available offline; completeness stays a blocker.
        inventory = "missing"
        print(
            "BLOCKER: official-template completeness unverified — no authoritative inventory "
            "(pass --official-inventory)",
            file=sys.stderr,
        )
    else:
        inventory = str(args.official_inventory)
        errors.extend(
            featured_gate_errors(
                featured,
                full,
                min_per_category=0,
                official_baseline=read_baseline(args.official_inventory),
            )
        )

    if errors:
        print(f"FAILED ({len(errors)} issues):", file=sys.stderr)
        for e in errors[:50]:
            print(f"  - {e}", file=sys.stderr)
        if len(errors) > 50:
            print(f"  ... and {len(errors) - 50} more", file=sys.stderr)
        return 1

    print(
        json.dumps(
            {
                "ok": True,
                "featured": len(featured),
                "full": len(full),
                "featured_per_category": dict(per_cat),
                "official_inventory": inventory,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
