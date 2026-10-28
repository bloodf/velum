#!/usr/bin/env python3
"""Assert cleaned Featured + Full JSONL have no empty/duplicate/PII-pattern entries."""
from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

RE_EMAIL = re.compile(r"\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b", re.I)
RE_URL = re.compile(
    r"(?i)\b(?:https?://|www\.)[^\s<>\]\"']+|\b[a-z0-9\-]+\.(?:com|net|org|io|co|app|ai|dev|me|xyz|info)(?:/[^\s]*)?"
)
RE_PLAUID = re.compile(r"(?i)\bplaud\b")
RE_PHONE_HARD = re.compile(r"(?<!\d)(?:\+?\d[\d\s\-().]{8,}\d)(?!\d)")


def nfkc(s: str) -> str:
    return unicodedata.normalize("NFKC", s or "")


def norm_key(s: str) -> str:
    s = nfkc(s).casefold()
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"[^\w\s]+", "", s, flags=re.UNICODE)
    return s.strip()


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            rows.append(json.loads(line))
    return rows


def check_set(label: str, rows: list[dict], *, require_featured: bool | None) -> list[str]:
    errors: list[str] = []
    ids: set[str] = set()
    exact: dict[str, str] = {}
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
        key = norm_key(name) + "\n" + norm_key(body)
        if key in exact:
            errors.append(f"{label}: exact duplicate title+body {tid} == {exact[key]}")
        else:
            exact[key] = tid
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


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--featured", type=Path, required=True)
    p.add_argument("--full", type=Path, required=True)
    p.add_argument("--min-per-category", type=int, default=100)
    args = p.parse_args(argv)

    featured = load_jsonl(args.featured)
    full = load_jsonl(args.full)
    errors = []
    errors.extend(check_set("featured", featured, require_featured=True))
    errors.extend(check_set("full", full, require_featured=False))

    feat_ids = {r["id"] for r in featured}
    full_ids = {r["id"] for r in full}
    missing = feat_ids - full_ids
    if missing:
        errors.append(f"Featured ids missing from Full: {len(missing)} e.g. {next(iter(missing))}")

    per_cat = Counter(r["category"] for r in featured)
    for cat, n in sorted(per_cat.items()):
        if n < args.min_per_category:
            errors.append(f"Featured category {cat} has {n} < {args.min_per_category}")

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
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
