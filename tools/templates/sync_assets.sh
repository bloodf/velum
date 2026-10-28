#!/usr/bin/env bash
# Sync curated Featured JSONL + gzip Full cleaned set into classpath resources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCS="$ROOT/docs/templates"
RES="$ROOT/core/src/commonMain/resources"
FEATURED="$DOCS/plaud_templates.jsonl"
FULL="$DOCS/plaud_templates_full.jsonl"
CONTRIB="$DOCS/velum_contributed.jsonl"

mkdir -p "$RES" "$DOCS"
touch "$FEATURED" "$CONTRIB"

if [[ ! -f "$FULL" ]]; then
  echo "Missing $FULL — run curate.py first" >&2
  exit 1
fi

cp "$FEATURED" "$RES/plaud_templates.jsonl"
cp "$CONTRIB" "$RES/velum_contributed.jsonl"

# Gzip full set for lazy on-device import (keep uncompressed docs copy for audits).
gzip -c -9 "$FULL" > "$RES/plaud_templates_full.jsonl.gz"

FEATURED_N=$(grep -c '[^[:space:]]' "$FEATURED" || true)
FULL_N=$(grep -c '[^[:space:]]' "$FULL" || true)
GZ_BYTES=$(wc -c < "$RES/plaud_templates_full.jsonl.gz" | tr -d ' ')

# Regenerate credits via existing sync helper (Featured only — full set is too large for markdown).
"$ROOT/scripts/sync-plaud-templates.sh"

echo "Synced Featured=$FEATURED_N → resources/plaud_templates.jsonl"
echo "Synced Full=$FULL_N → resources/plaud_templates_full.jsonl.gz (${GZ_BYTES} bytes)"
