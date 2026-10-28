# Template collection hygiene & bundling

Reproducible pipeline that cleans the collected Plaud-dump templates, then builds
Velum’s two-tier built-in catalog.

## Inputs

- Collected dump: `templates/cat_*.jsonl` (from `template-collection.tar.gz`)
- Optional existing curated rows: `docs/templates/plaud_templates.jsonl`

## Pipeline

```bash
# 1) Hygiene (normalize, strip PII/branding, drop junk, exact-dedupe, tags)
# Prefer dump-only (no --existing) so Featured is not double-counted / re-cleaned.
python3 tools/templates/clean.py \
  --input /tmp/template-collection/templates \
  --out-dir docs/templates/cleaned \
  --report docs/audit/templates-hygiene.md

# 2) Curate Featured (≥100/category) + Full cleaned set (passes tags through)
python3 tools/templates/curate.py \
  --cleaned docs/templates/cleaned \
  --out-featured docs/templates/plaud_templates.jsonl \
  --out-full docs/templates/plaud_templates_full.jsonl \
  --report docs/templates/catalog_report.json

# 3) Sync classpath assets (gzip full set)
./tools/templates/sync_assets.sh

# 4) Assert hygiene (incl. non-empty tags + lang)
python3 tools/templates/assert_hygiene.py \
  --featured docs/templates/plaud_templates.jsonl \
  --full docs/templates/plaud_templates_full.jsonl

# Or everything (still uses --existing in run_all.sh — override clean step if needed):
./tools/templates/run_all.sh /tmp/template-collection/templates
```

### Tags

- Controlled vocabulary: `tools/templates/tags.py` (`VOCABULARY`, ~40–80 slugs).
- Assignment: rule-based match on name+description+body + category priors; 1–8 tags/row.
- Language stays on `lang` (not duplicated as `lang-*` tags).
- App labels: Kotlin `TemplateTagVocabulary` (en / pt-BR), offline.
- Docs mirror: `docs/templates/tag_vocabulary.json`.

## Outputs

| Path | Role |
|------|------|
| `docs/templates/cleaned/*.jsonl` | Per-category cleaned rows (exact-deduped) |
| `docs/audit/templates-hygiene.md` | Removals per reason + before/after counts |
| `docs/templates/plaud_templates.jsonl` | Featured set (shipped; seeded at first launch) |
| `docs/templates/plaud_templates_full.jsonl` | Full cleaned set (gzip’d into resources; lazy import) |
| `docs/templates/catalog_report.json` | Featured/full counts |

## Hygiene rules (summary)

1. Normalize Unicode (NFKC), line endings (`\n`), whitespace, markdown bullets/headings.
2. Fix mojibake / broken encoding when recoverable; otherwise drop.
3. Trim titles and descriptions; drop empty / too-short / non-functional prompts.
4. Drop test, placeholder, spam, advertising, paid-service promo, gibberish.
5. Strip emails, phones, URLs, personal-page links; scrub author/brand name drops in body.
6. Remove Plaud product/UI branding so copy reads as generic Velum templates.
7. Tag language (`lang`); fix or drop wrong/missing category.
8. Assign discoverability `tags` (controlled vocabulary; always ≥1 via category default).
9. Delete identical duplicates (normalized title + prompt) during early hygiene.
10. **Exact body dedupe (#99):** collapse rows whose `body` strings match after
    conservative whitespace/line-ending normalization (CRLF→LF, per-line rstrip,
    strip ends; **no** case folding) via `tools/templates/body_dedupe.py`.
    Survivor order: official → Featured → lexicographic name → canonical JSON.
    Tags/usage/official/featured flags from dropped copies merge into the
    survivor. Letter-case-only variants stay distinct.

```bash
# Re-run body dedupe on an already-curated full JSONL (idempotent):
python3 tools/templates/body_dedupe.py \
  --input docs/templates/plaud_templates_full.jsonl \
  --output docs/templates/plaud_templates_full.jsonl \
  --report docs/audit/templates-body-dedupe.md \
  --remap docs/templates/body_dedupe_remap.json \
  --kotlin-remap core/src/commonMain/kotlin/dev/velum/core/templates/TemplateBodyDedupeRemap.kt

cp docs/templates/body_dedupe_remap.json \
  core/src/commonMain/resources/template_body_dedupe_remap.json
./tools/templates/sync_assets.sh

python3 tools/templates/test_body_dedupe.py
```

Display rules in the app (unchanged): no official badge, no author credit (except Velum PR `contributor`).
