#!/usr/bin/env bash
# Full hygiene → curate → asset sync pipeline.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INPUT="${1:-/tmp/template-collection/templates}"

if [[ ! -d "$INPUT" ]]; then
  echo "Input directory missing: $INPUT" >&2
  echo "Usage: $0 [/path/to/templates]" >&2
  exit 1
fi

# Dump-only clean (no --existing) so Featured is not re-ingested without tags.
python3 "$ROOT/tools/templates/clean.py" \
  --input "$INPUT" \
  --out-dir "$ROOT/docs/templates/cleaned" \
  --report "$ROOT/docs/audit/templates-hygiene.md"

python3 "$ROOT/tools/templates/curate.py" \
  --cleaned "$ROOT/docs/templates/cleaned" \
  --out-featured "$ROOT/docs/templates/plaud_templates.jsonl" \
  --out-full "$ROOT/docs/templates/plaud_templates_full.jsonl" \
  --report "$ROOT/docs/templates/catalog_report.json"

"$ROOT/tools/templates/sync_assets.sh"

python3 "$ROOT/tools/templates/assert_hygiene.py" \
  --featured "$ROOT/docs/templates/plaud_templates.jsonl" \
  --full "$ROOT/docs/templates/plaud_templates_full.jsonl" \
  --min-per-category 100

echo "Pipeline complete."
