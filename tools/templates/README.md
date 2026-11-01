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

# 4) Exact-body dedupe of the shipped assets + alias map (#99); idempotent
(cd tools/templates && python3 dedupe_catalog.py)

# 5) Assert hygiene + Featured gate (#101); --full may be the .gz
python3 tools/templates/assert_hygiene.py \
  --featured core/src/commonMain/resources/plaud_templates.jsonl \
  --full core/src/commonMain/resources/plaud_templates_full.jsonl.gz \
  --official-baseline docs/templates/official_baseline.txt

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
| `docs/templates/official_baseline.txt` | Official ids that must stay official + Featured |
| `core/.../templates/PlaudTemplateAliases.kt` | Generated removed-duplicate → survivor id map |

## Hygiene rules (summary)

1. Normalize Unicode (NFKC), line endings (`\n`), whitespace, markdown bullets/headings.
2. Fix mojibake / broken encoding when recoverable; otherwise drop.
3. Trim titles and descriptions; drop empty / too-short / non-functional prompts.
4. Drop test, placeholder, spam, advertising, paid-service promo, gibberish.
5. Strip emails, phones, URLs, personal-page links; scrub author/brand name drops in body.
6. Remove Plaud product/UI branding so copy reads as generic Velum templates.
7. Tag language (`lang`); fix or drop wrong/missing category.
8. Assign discoverability `tags` (controlled vocabulary; always ≥1 via category default).
9. Delete exact-body duplicates — keep exactly one row per identical `body` (see below).

## Exact-body dedupe (#99)

- Key: the `body` string exactly as stored. No whitespace, case or Unicode folding — bodies
  differing only in whitespace or case are distinct.
- Survivor (first wins): `official` → existing Featured → `name` (plain codepoint order, so
  `"B…"` sorts before `"a…"`) → canonical row JSON (`sort_keys`, compact separators).
- Survivor inherits `official` / `featured` if any collapsed row had it. The generator fails
  (writing nothing) on a dangling or cyclic alias. Output order does
  not depend on input order; a second run removes nothing.
- `dedupe_catalog.py` rewrites the shipped gzip / Featured JSONL and appends removed → survivor
  ids to the generated `core/.../templates/PlaudTemplateAliases.kt` (never pruned).
- Existing installs: `VelumRepository` init deletes builtin rows (`builtin = 1` only) whose id
  is a removed alias, drops their FTS rows and repoints `autoflow.template_id` to the survivor.
  `BuiltinTemplateCatalog.resolveId` maps removed ids for queued jobs and old summary versions.

## Featured gate (#101)

`assert_hygiene.py` fails when any of the 15 categories has fewer than `--min-per-category`
distinct exact bodies in Featured (renamed copies do not count twice), a Featured id is absent
from Full, or a template in `docs/templates/official_baseline.txt` is missing / no longer
official / not Featured. That baseline is the 41 rows flagged official at `1f30a0f`; it is
**not** an authoritative upstream inventory. Without `--official-inventory` the script prints
an explicit `BLOCKER` line — official completeness stays unverified until such an inventory
is cited. Tests: `python3 -m unittest discover -s tools/templates -p "test_*.py"` (run by
`run_all.sh`). The Gradle build needs no Python: `TemplateHygieneCatalogTest` (`:core:jvmTest`)
re-checks the shipped assets — no duplicate bodies, Featured ⊆ Full, ≥100 distinct bodies per
category, and every `official_baseline.txt` id official + Featured.

Display rules in the app (unchanged): no official badge, no author credit (except Velum PR `contributor`).
