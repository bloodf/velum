# Built-in template catalog

Velum ships three built-in sets:

1. **Velum originals** — `BuiltinTemplateCatalog` (`velum.*`).
2. **Velum PR contributions** — `docs/templates/velum_contributed.jsonl` (optional `contributor` credit in the app).
3. **Plaud official + community** — curated JSONL below. Author / official stay in the file and `docs/TEMPLATES_CREDITS.md`; the **app UI does not show Plaud author or official badges**.

Assets are classpath resources under `core/src/commonMain/resources/` (lazy-loaded — not embedded as a Kotlin string).

## Sync

```bash
./scripts/sync-plaud-templates.sh
```

Copies both JSONL files into resources and regenerates `docs/TEMPLATES_CREDITS.md`.

## Plaud JSONL schema (one object per line)

```json
{
  "id": "construction-project-hand-off-de12acbb",
  "categoryId": "cat_construction",
  "category": "Construction",
  "name": "Project Hand Off",
  "author": "Plaud",
  "official": true,
  "usageCount": 12,
  "description": "Create bulleted hand-off lists.",
  "bodyType": "outline",
  "body": "…",
  "source": "Plaud template community",
  "attribution": "by Plaud via Plaud template community"
}
```

| field | notes |
|---|---|
| `author` / `official` / `attribution` | Docs / credits only — **not shown in the app** |
| `bodyType` | `prompt` = output skeleton to fill; `outline` = instructions |
| `usageCount` | Popularity sort in Discover |

## Velum contributed schema

See `CONTRIBUTING.md` → Template contributions. `contributor` is the only in-app credit.

User-local JSON import remains available in the Templates screen (`TemplateJsonImporter`) for personal exports.
