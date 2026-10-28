#!/usr/bin/env bash
# Pack templates/autoflow/<category>/*.json → classpath JSONL + optional credits.
# Also used by the Gradle validateAutoflowTemplates task.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/templates/autoflow"
RES_DIR="$ROOT/core/src/commonMain/resources"
OUT_JSONL="$RES_DIR/autoflow_templates.jsonl"
SCHEMA="$SRC/schema/autoflow-template.schema.json"

mkdir -p "$RES_DIR"

python3 - "$SRC" "$OUT_JSONL" "$SCHEMA" <<'PY'
import json, sys
from pathlib import Path

src, out_jsonl, schema_path = map(Path, sys.argv[1:4])
schema = json.loads(schema_path.read_text(encoding="utf-8"))
allowed_categories = set(schema["properties"]["category"]["enum"])
allowed_icons = set(schema["properties"]["icon"]["enum"])
allowed_sources = set(schema["properties"]["definition"]["properties"]["source_filter"]["enum"])
allowed_langs = set(schema["properties"]["definition"]["properties"]["language"]["enum"])
allowed_posts = {None, "", "share", "calendar_insert", "folder_export", "webhook"}
velum_note_ids = {
    "velum.general", "velum.meeting", "velum.call", "velum.sales", "velum.interview",
    "velum.consulting", "velum.education", "velum.speech", "velum.medical", "velum.legal",
    "velum.financial", "velum.estate", "velum.construction", "velum.it", "velum.extractive",
}

files = sorted(p for p in src.rglob("*.json") if "schema" not in p.parts)
if not files:
    raise SystemExit(f"No AutoFlow templates under {src}")

rows = []
ids = set()
errors = []

def need(obj, *keys):
    for k in keys:
        if k not in obj:
            return False
    return True

for path in files:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        errors.append(f"{path}: invalid JSON ({e})")
        continue

    rel = path.relative_to(src)
    if data.get("schema_version") != 1:
        errors.append(f"{rel}: schema_version must be 1")
    tid = data.get("id")
    if not isinstance(tid, str) or not tid:
        errors.append(f"{rel}: missing id")
        continue
    if tid in ids:
        errors.append(f"{rel}: duplicate id {tid}")
    ids.add(tid)
    cat = data.get("category")
    if cat not in allowed_categories:
        errors.append(f"{rel}: invalid category {cat!r}")
    elif path.parent.name != cat:
        errors.append(f"{rel}: file folder {path.parent.name!r} != category {cat!r}")
    if data.get("icon") not in allowed_icons:
        errors.append(f"{rel}: invalid icon {data.get('icon')!r}")
    for field in ("name", "description", "preview"):
        loc = data.get(field)
        if not isinstance(loc, dict) or "en" not in loc or "pt-BR" not in loc:
            errors.append(f"{rel}: {field} must include en and pt-BR")
        elif not str(loc.get("en", "")).strip() or not str(loc.get("pt-BR", "")).strip():
            errors.append(f"{rel}: {field} locales must be non-empty")
    author = data.get("author", None)
    if author is not None:
        if not isinstance(author, dict) or not str(author.get("name", "")).strip():
            errors.append(f"{rel}: author must be null or {{name, github?}}")
    definition = data.get("definition")
    if not isinstance(definition, dict) or not need(
        definition,
        "source_filter", "duration_min_seconds", "duration_max_seconds",
        "template_id", "auto_label_voice", "language", "notify", "post_action",
    ):
        errors.append(f"{rel}: incomplete definition")
        continue
    if definition["source_filter"] not in allowed_sources:
        errors.append(f"{rel}: invalid source_filter")
    if definition["language"] not in allowed_langs:
        errors.append(f"{rel}: invalid language")
    post = definition.get("post_action")
    if post not in allowed_posts:
        errors.append(f"{rel}: invalid post_action {post!r}")
    dmin = definition["duration_min_seconds"]
    dmax = definition["duration_max_seconds"]
    if not isinstance(dmin, int) or not isinstance(dmax, int) or dmin < 0 or dmax < 1 or dmin > dmax:
        errors.append(f"{rel}: invalid duration range")
    note_id = definition["template_id"]
    if note_id not in velum_note_ids:
        # Allow Plaud / contrib ids if present in bundled note catalogs.
        plaud = Path(src).parents[1] / "core/src/commonMain/resources/plaud_templates.jsonl"
        contrib = Path(src).parents[1] / "core/src/commonMain/resources/velum_contributed.jsonl"
        known = set(velum_note_ids)
        for catalog in (plaud, contrib):
            if catalog.is_file():
                for line in catalog.read_text(encoding="utf-8").splitlines():
                    line = line.strip()
                    if not line or line.startswith("#"):
                        continue
                    try:
                        known.add(json.loads(line)["id"])
                    except Exception:
                        pass
        # Also accept velum.contrib.* form
        if note_id not in known and not str(note_id).startswith("velum.contrib."):
            errors.append(f"{rel}: unknown note template_id {note_id!r}")
    rows.append(data)

if len(rows) < 100:
    errors.append(f"expected at least 100 templates, found {len(rows)}")

if errors:
    print("AutoFlow template validation failed:", file=sys.stderr)
    for e in errors:
        print(f"  - {e}", file=sys.stderr)
    raise SystemExit(1)

# Stable order: category then id
rows.sort(key=lambda r: (r["category"], r["id"]))
out_jsonl.write_text(
    "".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n" for r in rows),
    encoding="utf-8",
)
print(f"Synced {len(rows)} AutoFlow templates → {out_jsonl}")
from collections import Counter
counts = Counter(r["category"] for r in rows)
for cat, n in sorted(counts.items()):
    print(f"  {cat}: {n}")
PY
