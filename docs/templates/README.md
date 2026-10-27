# Built-in template catalog

Velum ships two built-in sets:

1. **Velum originals** — `docs/velum_templates.json` / `BuiltinTemplateCatalog` (`velum.*`).
2. **Plaud official + community** — JSONL below, mirrored into `PlaudTemplateCatalogData` for on-device seed.

## Plaud JSONL schema (one object per line)

```json
{
  "id": "plaud.standup-daily",
  "categoryId": "meeting",
  "category": "meeting",
  "name": "Daily standup",
  "author": "Jane Doe",
  "sourceTemplateId": "tpl_abc123",
  "official": false,
  "description": "Wins, blockers, plans",
  "bodyType": "prompt",
  "body": "Write a standup summary in Markdown…",
  "source": "Plaud template community",
  "attribution": "By Jane Doe · Plaud template community · id tpl_abc123"
}
```

| field | notes |
|---|---|
| `id` | Stable Velum id (`plaud.*` preferred) |
| `categoryId` / `category` | Plaud category key when known |
| `author` | Credit shown in picker + detail |
| `sourceTemplateId` | Upstream Plaud id |
| `official` | Plaud first-party vs community |
| `bodyType` | `prompt` or `outline` |
| `body` | LLM instruction or outline |
| `source` | Always `"Plaud template community"` for community; official may use the same + `official: true` |
| `attribution` | Full credit line for UI |

Drop collected lines into `plaud_templates.jsonl`, then run:

```bash
./scripts/sync-plaud-templates.sh
```

That regenerates `PlaudTemplateCatalogData.JSONL` and `docs/TEMPLATES_CREDITS.md`.

User-local JSON import remains available in the Templates screen (`TemplateJsonImporter`) for personal exports.
